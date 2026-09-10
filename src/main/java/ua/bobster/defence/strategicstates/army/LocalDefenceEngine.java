package ua.bobster.defence.strategicstates.army;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Arrow;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenNavigator;
import ua.bobster.defence.strategicstates.population.CitizenProfession;
import ua.bobster.defence.strategicstates.population.StateCitizen;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.territory.TerritoryMapHook;
import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

public class LocalDefenceEngine {

    private record Threat(UUID stateId, UUID playerId, Location anchor, long expiresAt) {
    }

    private final BobsterDefence plugin;
    private final CitizenManager citizens;
    private final TerritoryMapHook territories;
    private final Function<UUID, StrategicState> states;
    private final BiPredicate<UUID, UUID> allied;
    private final BiConsumer<UUID, UUID> intrusion;
    private final Map<UUID, Threat> threats = new HashMap<>();
    private final Map<UUID, UUID> authorizedTargets = new HashMap<>();
    private final Map<UUID, Long> lastAttacks = new HashMap<>();
    private final CitizenNavigator navigator = new CitizenNavigator();

    private BukkitTask task;
    private double radius;
    private long memoryMillis;
    private final Map<UUID, Long> lastBells = new HashMap<>();
    private boolean defendCreative;
    private long meleeCooldownMillis;
    private long creativeShotCooldownMillis;

    public LocalDefenceEngine(BobsterDefence plugin, CitizenManager citizens, TerritoryMapHook territories, Function<UUID, StrategicState> states, BiPredicate<UUID, UUID> allied, BiConsumer<UUID, UUID> intrusion) {
        this.plugin = plugin;
        this.citizens = citizens;
        this.territories = territories;
        this.states = states;
        this.allied = allied;
        this.intrusion = intrusion;
        reload();
    }

    public void reload() {
        radius = Math.max(4.0D, plugin.getConfig().getDouble("strategic-states.army.local-defence-radius", 16.0D));
        memoryMillis = Math.max(1L, plugin.getConfig().getLong("strategic-states.army.local-defence-seconds", 15L)) * 1000L;
        defendCreative = plugin.getConfig().getBoolean("strategic-states.army.defend-against-creative", true);
        meleeCooldownMillis = Math.max(250L, plugin.getConfig().getLong("strategic-states.army.melee-cooldown-millis", 1000L));
        creativeShotCooldownMillis = Math.max(500L, plugin.getConfig().getLong("strategic-states.army.creative-shot-cooldown-millis", 2000L));
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        threats.clear();
        clearAll();
    }

    public void attacked(Entity victim, Player attacker) {
        StateCitizen citizen = citizens.citizenByEntity(victim);
        if (citizen == null || attacker == null) {
            return;
        }
        threats.put(attacker.getUniqueId(), new Threat(citizen.stateId(), attacker.getUniqueId(), victim.getLocation().clone(), System.currentTimeMillis() + memoryMillis));
    }

    public boolean authorized(Entity attacker, LivingEntity target) {
        return attacker != null && target != null && target.getUniqueId().equals(authorizedTargets.get(attacker.getUniqueId()));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        detectIntruders(now);
        Set<UUID> refreshed = new HashSet<>();
        for (Threat threat : new ArrayList<>(threats.values())) {
            Player player = plugin.getServer().getPlayer(threat.playerId());
            if (expired(threat, player, now)) {
                threats.remove(threat.playerId());
                continue;
            }
            defend(threat, player, refreshed);
        }
        clearMissing(refreshed);
    }

    private void detectIntruders(long now) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE && !defendCreative) {
                continue;
            }
            TerritorySnapshot territory = territories.at(player.getWorld().getName(), player.getX(), player.getZ());
            if (territory == null || territory.ownerKind() != TerritoryOwnerKind.STRATEGIC_STATE || territory.ownerId() == null || allied.test(territory.ownerId(), player.getUniqueId())) {
                continue;
            }
            Threat previous = threats.put(player.getUniqueId(), new Threat(territory.ownerId(), player.getUniqueId(), player.getLocation().clone(), now + memoryMillis));
            if (previous == null || !previous.stateId().equals(territory.ownerId())) {
                intrusion.accept(territory.ownerId(), player.getUniqueId());
            }
            StrategicState state = states.apply(territory.ownerId());
            if (state != null && now - lastBells.getOrDefault(state.id(), 0L) >= 5000L) {
                Location core = state.core().location();
                if (core != null) {
                    core.getWorld().playSound(core, Sound.BLOCK_BELL_USE, 4.0F, 0.8F);
                    lastBells.put(state.id(), now);
                }
            }
        }
    }

    private boolean expired(Threat threat, Player player, long now) {
        if (now >= threat.expiresAt() || player == null || player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE && !defendCreative) {
            return true;
        }
        TerritorySnapshot territory = territories.at(player.getWorld().getName(), player.getX(), player.getZ());
        return territory == null || !threat.stateId().equals(territory.ownerId());
    }

    private void defend(Threat threat, Player player, Set<UUID> refreshed) {
        List<StateCitizen> candidates = citizens.physicalCitizens().stream()
                .filter(citizen -> citizen.stateId().equals(threat.stateId()))
                .filter(citizen -> citizen.currentTask() == null || !citizen.currentTask().startsWith("WAR:"))
                .filter(citizen -> {
                    Pillager entity = citizens.physicalEntity(citizen);
                    return entity != null && entity.getWorld() == player.getWorld();
                })
                .sorted(Comparator.comparingInt(citizen -> military(citizen.profession()) ? 0 : 1))
                .toList();
        for (StateCitizen citizen : candidates) {
            Pillager entity = citizens.physicalEntity(citizen);
            if (entity == null) {
                continue;
            }
            authorizedTargets.put(entity.getUniqueId(), player.getUniqueId());
            refreshed.add(entity.getUniqueId());
            entity.setAware(true);
            Material weapon = weapon(citizen);
            entity.getEquipment().setItemInMainHand(new ItemStack(weapon));
            if (!player.equals(entity.getTarget())) {
                entity.setTarget(player);
            }
            if (weapon == Material.CROSSBOW) {
                creativeShot(entity, player);
            } else {
                melee(entity, player, weapon);
            }
        }
    }

    private Material weapon(StateCitizen citizen) {
        return switch (citizen.profession()) {
            case CROSSBOWMAN, GUARD, FARMER, DRONE_OPERATOR, MISSILE_OPERATOR, AIR_DEFENCE_OPERATOR -> Material.CROSSBOW;
            case SOLDIER, COMMANDER, LUMBERJACK, MINER -> Material.IRON_AXE;
            default -> Material.IRON_SWORD;
        };
    }

    private void melee(Pillager entity, Player player, Material weapon) {
        navigator.move(entity, player.getLocation(), weapon == Material.IRON_AXE ? 0.42D : 0.46D);
        if (entity.getLocation().distanceSquared(player.getLocation()) > 7.84D) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttacks.getOrDefault(entity.getUniqueId(), 0L) < meleeCooldownMillis) {
            return;
        }
        lastAttacks.put(entity.getUniqueId(), now);
        entity.lookAt(player);
        entity.swingMainHand();
        player.damage(weapon == Material.IRON_AXE ? 7.0D : 5.0D, entity);
    }

    private void creativeShot(Pillager entity, Player player) {
        if (player.getGameMode() != GameMode.CREATIVE || !entity.hasLineOfSight(player)) {
            return;
        }
        double distance = entity.getEyeLocation().distanceSquared(player.getEyeLocation());
        if (distance > 1024.0D) {
            return;
        }
        if (distance < 36.0D) {
            double angle = Math.floorMod(entity.getUniqueId().hashCode(), 360) * Math.PI / 180.0D;
            Location position = player.getLocation().clone().add(Math.cos(angle) * 8.0D, 0.0D, Math.sin(angle) * 8.0D);
            navigator.move(entity, position, 0.38D);
        }
        long now = System.currentTimeMillis();
        if (now - lastAttacks.getOrDefault(entity.getUniqueId(), 0L) < creativeShotCooldownMillis) {
            return;
        }
        lastAttacks.put(entity.getUniqueId(), now);
        Location origin = entity.getEyeLocation();
        Vector direction = player.getEyeLocation().toVector().subtract(origin.toVector()).normalize();
        Arrow arrow = entity.getWorld().spawnArrow(origin, direction, 1.8F, 3.0F);
        arrow.setShooter(entity);
        arrow.setPierceLevel(10);
        arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
        entity.lookAt(player);
        entity.swingMainHand();
    }

    private void clearMissing(Set<UUID> refreshed) {
        for (UUID fighterId : new ArrayList<>(authorizedTargets.keySet())) {
            if (!refreshed.contains(fighterId)) {
                clear(fighterId);
            }
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

    private double horizontalDistanceSquared(Location first, Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }
}
