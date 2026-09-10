package ua.bobster.defence.strategicstates.combat;

import org.bukkit.Location;
import org.bukkit.entity.Pillager;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.ballistic.BallisticLauncher;
import ua.bobster.defence.ballistic.LauncherTier;
import ua.bobster.defence.ballistic.MissileLaunchRequest;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.drone.AutonomousDroneMission;
import ua.bobster.defence.drone.DroneType;
import ua.bobster.defence.strategicstates.StrategicStateManager;
import ua.bobster.defence.strategicstates.arsenal.ArsenalItem;
import ua.bobster.defence.strategicstates.arsenal.MilitaryProductionManager;
import ua.bobster.defence.strategicstates.construction.BuildingStatus;
import ua.bobster.defence.strategicstates.construction.BuildingType;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.policy.ActionContext;
import ua.bobster.defence.strategicstates.policy.AttackPolicyManager;
import ua.bobster.defence.strategicstates.policy.StrategicAction;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenProfession;
import ua.bobster.defence.strategicstates.population.StateCitizen;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarStatus;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class StrategicStrikeManager {

    private static final class StrikePlan {

        private int drones;
        private int missiles;
        private long nextSalvo;

        private StrikePlan(int drones, int missiles, long nextSalvo) {
            this.drones = drones;
            this.missiles = missiles;
            this.nextSalvo = nextSalvo;
        }
    }

    private record LaunchSite(Location origin, Pillager operator, String tierId) {
    }

    private final BobsterDefence plugin;
    private final StrategicStateManager states;
    private final MilitaryProductionManager production;
    private final StateRepository repository;
    private final CitizenManager citizens;
    private final StateInstallationManager installations;
    private final StrategicTargetScanner scanner;
    private final AttackPolicyManager policy;
    private final Map<UUID, StrikePlan> plans = new HashMap<>();
    private BukkitTask task;
    private long intervalTicks;
    private long salvoIntervalMillis;

    public StrategicStrikeManager(BobsterDefence plugin, StrategicStateManager states, MilitaryProductionManager production, StateRepository repository, CitizenManager citizens, StateInstallationManager installations) {
        this.plugin = plugin;
        this.states = states;
        this.production = production;
        this.repository = repository;
        this.citizens = citizens;
        this.installations = installations;
        this.scanner = new StrategicTargetScanner(plugin, states.territories(), repository);
        this.policy = new AttackPolicyManager(states);
        reload();
    }

    public void load() throws java.sql.SQLException {
        scanner.load(repository.loadStrategicTargets());
    }

    public void reload() {
        intervalTicks = Math.max(20L, plugin.getConfig().getLong("strategic-states.strikes.tick-interval-ticks", 100L));
        salvoIntervalMillis = Math.max(5L, plugin.getConfig().getLong("strategic-states.strikes.salvo-interval-seconds", 30L)) * 1000L;
    }

    public void start() {
        shutdown();
        if (!plugin.getConfig().getBoolean("strategic-states.strikes.enabled", true)) {
            return;
        }
        scanner.start();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void shutdown() {
        scanner.shutdown();
        plans.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        plans.keySet().removeIf(id -> states.wars().stream().noneMatch(war -> war.id().equals(id) && war.status() == WarStatus.ACTIVE));
        for (StateWar war : states.wars()) {
            if (war.status() != WarStatus.ACTIVE) {
                continue;
            }
            StrategicState attacker = states.byId(war.attackerStateId());
            if (attacker == null) {
                continue;
            }
            StrikePlan plan = plans.computeIfAbsent(war.id(), ignored -> createPlan(attacker, now));
            if (now >= plan.nextSalvo) {
                salvo(war, attacker, plan, now);
            }
        }
    }

    private StrikePlan createPlan(StrategicState state, long now) {
        int level = state.developmentLevel();
        int drones = switch (level) {
            case 1 -> randomized(5);
            case 2 -> randomized(10);
            case 3 -> randomized(15);
            case 4 -> randomized(50);
            default -> Integer.MAX_VALUE;
        };
        int missiles = level == 4 ? randomized(5) : level >= 5 ? Integer.MAX_VALUE : 0;
        return new StrikePlan(drones, missiles, now);
    }

    private int randomized(int base) {
        return Math.max(1, (int) Math.round(base * ThreadLocalRandom.current().nextDouble(0.5D, 1.5000001D)));
    }

    private void salvo(StateWar war, StrategicState attacker, StrikePlan plan, long now) {
        CombatPrincipal principal = CombatPrincipal.state(attacker.id());
        if (!policy.canAct(principal, war.target(), ActionContext.OFFENSIVE, StrategicAction.LAUNCH_DRONE)) {
            return;
        }
        TerritorySnapshot territory = states.territories().byId(war.territoryId());
        if (territory == null || territory.ownerId() == null || !territory.ownerId().equals(war.target().id())) {
            return;
        }
        Location target = scanner.target(territory, attacker.id(), states.sectors(territory.id()));
        if (target == null) {
            return;
        }
        int droneBatch = Math.min(plan.drones, batch(attacker.developmentLevel(), true));
        int launched = 0;
        for (int index = 0; index < droneBatch && production.available(attacker.id(), ArsenalItem.STRIKE_DRONE); index++) {
            if (!launchDrone(attacker, principal, war, target)) {
                break;
            }
            production.consume(attacker.id(), ArsenalItem.STRIKE_DRONE);
            launched++;
        }
        if (plan.drones != Integer.MAX_VALUE) {
            plan.drones -= launched;
        }
        if ((plan.drones <= 0 || launched == 0) && attacker.developmentLevel() >= 4) {
            int missileBatch = Math.min(plan.missiles, batch(attacker.developmentLevel(), false));
            for (int index = 0; index < missileBatch && production.available(attacker.id(), ArsenalItem.BALLISTIC_MISSILE); index++) {
                if (!launchMissile(attacker, principal, war, target)) {
                    break;
                }
                production.consume(attacker.id(), ArsenalItem.BALLISTIC_MISSILE);
                if (plan.missiles != Integer.MAX_VALUE) {
                    plan.missiles--;
                }
            }
        }
        plan.nextSalvo = now + salvoIntervalMillis;
    }

    private int batch(int level, boolean drone) {
        if (drone) {
            return switch (level) {
                case 1 -> 1;
                case 2 -> 2;
                case 3 -> 3;
                case 4 -> 5;
                default -> 8;
            };
        }
        return level >= 5 ? 2 : 1;
    }

    private boolean launchMissile(StrategicState attacker, CombatPrincipal principal, StateWar war, Location target) {
        LaunchSite site = missileSite(attacker);
        if (site == null || !policy.canAct(principal, war.target(), ActionContext.OFFENSIVE, StrategicAction.LAUNCH_MISSILE)) {
            return false;
        }
        LauncherTier tier = plugin.ballistic().tier(site.tierId());
        if (tier == null || plugin.ballistic().horizontalDistance(site.origin(), target.getBlockX(), target.getBlockZ()) > tier.range()) {
            return false;
        }
        boolean launched = plugin.ballistic().launch(new MissileLaunchRequest(principal, site.origin(), target, tier.id()));
        animate(site, target, launched);
        return launched;
    }

    private boolean launchDrone(StrategicState attacker, CombatPrincipal principal, StateWar war, Location target) {
        LaunchSite site = factorySite(attacker.id(), BuildingType.DRONE_FACTORY, CitizenProfession.DRONE_OPERATOR);
        DroneType type = plugin.drones().types().stream().filter(candidate -> site != null && site.origin().getWorld().equals(target.getWorld()) && site.origin().distanceSquared(target) <= (double) candidate.maxDistance() * candidate.maxDistance()).max(Comparator.comparingDouble(DroneType::explosionPower)).orElse(null);
        if (site == null || type == null || !policy.canAct(principal, war.target(), ActionContext.OFFENSIVE, StrategicAction.LAUNCH_DRONE)) {
            return false;
        }
        boolean launched = plugin.drones().launch(new AutonomousDroneMission(principal, site.origin(), target, type.id()));
        animate(site, target, launched);
        return launched;
    }

    private LaunchSite missileSite(StrategicState state) {
        for (StateBuilding building : states.buildings(state.id())) {
            if (building.type() != BuildingType.MISSILE_FACTORY || building.status() != BuildingStatus.MATERIALIZED) {
                continue;
            }
            StateCitizen citizen = citizens.physicalProfession(state.id(), CitizenProfession.MISSILE_OPERATOR, building.origin().location(), 64.0D);
            Pillager operator = citizens.physicalEntity(citizen);
            if (operator == null) {
                continue;
            }
            for (BallisticLauncher launcher : installations.missileLaunchers(state, building)) {
                return new LaunchSite(launcher.block().getLocation().add(0.5D, 1.0D, 0.5D), operator, launcher.tierId());
            }
        }
        return null;
    }

    private LaunchSite factorySite(UUID stateId, BuildingType type, CitizenProfession profession) {
        for (StateBuilding building : states.buildings(stateId)) {
            if (building.type() != type || building.status() != BuildingStatus.MATERIALIZED) {
                continue;
            }
            Location location = building.origin().location();
            if (location == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                continue;
            }
            StateCitizen citizen = citizens.physicalProfession(stateId, profession, location, Math.max(24.0D, building.sizeX() + building.sizeZ()));
            Pillager operator = citizens.physicalEntity(citizen);
            if (operator != null) {
                return new LaunchSite(location.add(building.sizeX() / 2.0D, building.sizeY() + 2.0D, building.sizeZ() / 2.0D), operator, null);
            }
        }
        return null;
    }

    private void animate(LaunchSite site, Location target, boolean launched) {
        if (launched) {
            site.operator().lookAt(target);
            site.operator().swingMainHand();
        }
    }
}
