package ua.bobster.defence.strategicstates.army;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenProfession;
import ua.bobster.defence.strategicstates.population.CitizenNavigator;
import ua.bobster.defence.strategicstates.population.StateCitizen;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ArmyCombatEngine {

    private final BobsterDefence plugin;
    private final CitizenManager citizens;
    private final WarManager wars;
    private final Map<UUID, UUID> authorizedTargets = new HashMap<>();
    private final Map<UUID, Long> lastAttacks = new HashMap<>();
    private final CitizenNavigator navigator = new CitizenNavigator();

    private BukkitTask task;
    private long intervalTicks;
    private double targetRange;

    public ArmyCombatEngine(BobsterDefence plugin, CitizenManager citizens, WarManager wars) {
        this.plugin = plugin;
        this.citizens = citizens;
        this.wars = wars;
        reload();
    }

    public void reload() {
        intervalTicks = Math.max(2L, plugin.getConfig().getLong("strategic-states.army.combat-tick-interval-ticks", 5L));
        targetRange = Math.max(8.0D, plugin.getConfig().getDouble("strategic-states.army.native-target-range", 32.0D));
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearAll();
    }

    public boolean authorized(Entity attacker, LivingEntity target) {
        return attacker != null && target != null && target.getUniqueId().equals(authorizedTargets.get(attacker.getUniqueId()));
    }

    private void tick() {
        Set<UUID> refreshed = new HashSet<>();
        for (StateWar war : wars.active()) {
            List<Fighter> attackers = fighters(war.attackerStateId());
            List<LivingEntity> defenders = defenders(war);
            assign(attackers, defenders, refreshed);
            if (war.target().type() == CombatPrincipalType.STRATEGIC_STATE) {
                List<Fighter> defendingFighters = fighters(war.target().id());
                List<LivingEntity> attackingEntities = attackers.stream().map(Fighter::entity).map(entity -> (LivingEntity) entity).toList();
                assign(defendingFighters, attackingEntities, refreshed);
            }
        }
        clearMissing(refreshed);
    }

    private void assign(List<Fighter> fighters, List<LivingEntity> targets, Set<UUID> refreshed) {
        for (Fighter fighter : fighters) {
            LivingEntity target = nearest(fighter.entity(), targets);
            if (target == null) {
                continue;
            }
            UUID fighterId = fighter.entity().getUniqueId();
            authorizedTargets.put(fighterId, target.getUniqueId());
            refreshed.add(fighterId);
            fighter.entity().setAware(true);
            Material weapon = ranged(fighter.citizen().profession()) ? Material.CROSSBOW : fighter.citizen().profession() == CitizenProfession.COMMANDER ? Material.IRON_SWORD : Material.IRON_AXE;
            fighter.entity().getEquipment().setItemInMainHand(new ItemStack(weapon));
            if (!target.equals(fighter.entity().getTarget())) {
                fighter.entity().setTarget(target);
            }
            if (weapon != Material.CROSSBOW) {
                melee(fighter.entity(), target, weapon);
            }
        }
    }

    private void melee(Pillager fighter, LivingEntity target, Material weapon) {
        navigator.move(fighter, target.getLocation(), 0.44D);
        if (fighter.getLocation().distanceSquared(target.getLocation()) > 7.84D) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttacks.getOrDefault(fighter.getUniqueId(), 0L) < 1000L) {
            return;
        }
        lastAttacks.put(fighter.getUniqueId(), now);
        fighter.lookAt(target);
        fighter.swingMainHand();
        target.damage(weapon == Material.IRON_AXE ? 7.0D : 5.0D, fighter);
    }

    private boolean ranged(CitizenProfession profession) {
        return profession == CitizenProfession.CROSSBOWMAN || profession == CitizenProfession.GUARD;
    }

    private LivingEntity nearest(Pillager fighter, List<LivingEntity> targets) {
        LivingEntity current = fighter.getTarget();
        if (current != null && targets.contains(current) && !current.isDead() && current.getWorld() == fighter.getWorld()
                && current.getLocation().distanceSquared(fighter.getLocation()) <= targetRange * targetRange) {
            return current;
        }
        LivingEntity nearest = null;
        double nearestDistance = targetRange * targetRange;
        for (LivingEntity target : targets) {
            if (target == null || target.isDead() || target.getWorld() != fighter.getWorld()) {
                continue;
            }
            double distance = target.getLocation().distanceSquared(fighter.getLocation());
            if (distance <= nearestDistance) {
                nearest = target;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private List<Fighter> fighters(UUID stateId) {
        List<Fighter> result = new ArrayList<>();
        for (StateCitizen citizen : citizens.physicalCitizens()) {
            if (!citizen.stateId().equals(stateId) || !military(citizen.profession())) {
                continue;
            }
            Pillager entity = citizens.physicalEntity(citizen);
            if (entity != null && !entity.isDead()) {
                result.add(new Fighter(citizen, entity));
            }
        }
        return result;
    }

    private List<LivingEntity> defenders(StateWar war) {
        if (war.target().type() == CombatPrincipalType.STRATEGIC_STATE) {
            return fighters(war.target().id()).stream().map(Fighter::entity).map(entity -> (LivingEntity) entity).toList();
        }
        Player player = Bukkit.getPlayer(war.target().id());
        if (player == null || player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) {
            return List.of();
        }
        return List.of(player);
    }

    private void clearMissing(Set<UUID> refreshed) {
        for (UUID fighterId : new ArrayList<>(authorizedTargets.keySet())) {
            if (refreshed.contains(fighterId)) {
                continue;
            }
            clear(fighterId);
        }
    }

    private void clearAll() {
        for (UUID fighterId : new ArrayList<>(authorizedTargets.keySet())) {
            clear(fighterId);
        }
    }

    private void clear(UUID fighterId) {
        authorizedTargets.remove(fighterId);
        lastAttacks.remove(fighterId);
        Entity entity = findEntity(fighterId);
        if (entity instanceof Pillager pillager) {
            pillager.setTarget(null);
        }
    }

    private Entity findEntity(UUID id) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            Entity entity = world.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private boolean military(CitizenProfession profession) {
        return profession == CitizenProfession.SOLDIER || profession == CitizenProfession.CROSSBOWMAN
                || profession == CitizenProfession.GUARD || profession == CitizenProfession.COMMANDER;
    }

    private record Fighter(StateCitizen citizen, Pillager entity) {
    }
}
