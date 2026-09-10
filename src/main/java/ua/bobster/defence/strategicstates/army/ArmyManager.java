package ua.bobster.defence.strategicstates.army;

import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.capture.CaptureSector;
import ua.bobster.defence.strategicstates.capture.CaptureSectorManager;
import ua.bobster.defence.strategicstates.model.StatePosition;
import ua.bobster.defence.strategicstates.model.StateStatus;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenMode;
import ua.bobster.defence.strategicstates.population.StateCitizen;
import ua.bobster.defence.strategicstates.population.CitizenNavigator;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarManager;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public class ArmyManager {

    private record Orders(ArmyMode mode, UUID warId, StatePosition destination) {
    }

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final CitizenManager citizens;
    private final WarManager wars;
    private final CaptureSectorManager sectors;
    private final Supplier<Collection<StrategicState>> states;
    private final Function<UUID, StrategicState> stateLookup;
    private final Map<UUID, StateArmy> armies = new LinkedHashMap<>();
    private final Map<UUID, Long> lastPersisted = new LinkedHashMap<>();
    private final CitizenNavigator navigator = new CitizenNavigator();

    private BukkitTask task;
    private BukkitTask navigationTask;
    private long intervalTicks;
    private long navigationIntervalTicks;
    private double virtualSpeedPerMinute;

    public ArmyManager(BobsterDefence plugin, StateRepository repository, CitizenManager citizens, WarManager wars, CaptureSectorManager sectors, Supplier<Collection<StrategicState>> states, Function<UUID, StrategicState> stateLookup) {
        this.plugin = plugin;
        this.repository = repository;
        this.citizens = citizens;
        this.wars = wars;
        this.sectors = sectors;
        this.states = states;
        this.stateLookup = stateLookup;
        reload();
    }

    public void reload() {
        intervalTicks = Math.max(5L, plugin.getConfig().getLong("strategic-states.army.tick-interval-ticks", 20L));
        navigationIntervalTicks = Math.max(1L, plugin.getConfig().getLong("strategic-states.army.navigation-interval-ticks", 5L));
        virtualSpeedPerMinute = Math.max(1.0D, plugin.getConfig().getDouble("strategic-states.army.virtual-speed-blocks-per-minute", 80.0D));
    }

    public void load(Collection<StateArmy> loaded, long now) {
        armies.clear();
        lastPersisted.clear();
        for (StateArmy army : loaded) {
            army.update(army.mode(), army.warId(), army.position(), army.destination(), army.strength(), now);
            armies.put(army.stateId(), army);
        }
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(), intervalTicks, intervalTicks);
        navigationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::navigatePhysical, navigationIntervalTicks, navigationIntervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (navigationTask != null) {
            navigationTask.cancel();
            navigationTask = null;
        }
    }

    public StateArmy army(UUID stateId) {
        return armies.get(stateId);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (StrategicState state : states.get()) {
            if (state.status() == StateStatus.DESTROYED) {
                continue;
            }
            StateArmy army = armies.computeIfAbsent(state.id(), ignored -> new StateArmy(state.id(), ArmyMode.PEACE, null, state.core(), state.core(), 0, now));
            Orders orders = orders(state);
            move(state, army, orders, now);
            Collection<StateCitizen> military = citizens.militaryCitizens(state.id());
            StatePosition position = average(military, state.core());
            army.update(orders.mode(), orders.warId(), position, orders.destination(), military.size(), now);
            if (now - lastPersisted.getOrDefault(state.id(), 0L) >= 1000L) {
                repository.saveArmyAsync(army);
                lastPersisted.put(state.id(), now);
            }
        }
    }

    private Orders orders(StrategicState state) {
        for (StateWar war : wars.active()) {
            if (war.attackerStateId().equals(state.id())) {
                return new Orders(ArmyMode.ATTACKING, war.id(), frontline(war, state));
            }
            if (war.target().type() == CombatPrincipalType.STRATEGIC_STATE && war.target().id().equals(state.id())) {
                return new Orders(ArmyMode.DEFENDING, war.id(), frontline(war, state));
            }
        }
        StateArmy army = armies.get(state.id());
        ArmyMode mode = army != null && distanceSquared(army.position(), state.core()) > 16.0D ? ArmyMode.RECOVERING : ArmyMode.PEACE;
        return new Orders(mode, null, state.core());
    }

    private StatePosition frontline(StateWar war, StrategicState state) {
        StrategicState attacker = stateLookup.apply(war.attackerStateId());
        CaptureSector sector = attacker == null ? null : sectors.frontline(war.territoryId(), attacker.id(), attacker.core());
        return sector == null ? state.core() : new StatePosition(sector.world(), sector.controlX(), state.core().y(), sector.controlZ());
    }

    private void move(StrategicState state, StateArmy army, Orders orders, long now) {
        StatePosition destination = orders.destination();
        if (destination == null || !destination.world().equals(state.core().world())) {
            return;
        }
        double distance = virtualSpeedPerMinute * Math.max(0L, now - army.lastUpdate()) / 60_000.0D;
        if (distance <= 0.0D) {
            return;
        }
        for (StateCitizen citizen : citizens.militaryCitizens(state.id())) {
            updateTask(citizen, orders);
            if (citizen.mode() != CitizenMode.VIRTUAL) {
                continue;
            }
            double angle = Math.floorMod(citizen.id().hashCode(), 360) * Math.PI / 180.0D;
            StatePosition formation = new StatePosition(destination.world(), destination.x() + Math.cos(angle) * 2.0D, destination.y(), destination.z() + Math.sin(angle) * 2.0D);
            StatePosition moved = step(citizen.position(), formation, distance);
            citizens.moveVirtual(citizen, moved);
            if (safeToMaterialize(moved, destination)) {
                citizens.materializeForTravel(citizen, state);
            }
        }
    }

    private void navigatePhysical() {
        for (StrategicState state : states.get()) {
            if (state.status() == StateStatus.DESTROYED) {
                continue;
            }
            Orders orders = orders(state);
            StatePosition destination = orders.destination();
            org.bukkit.World world = destination == null ? null : plugin.getServer().getWorld(destination.world());
            for (StateCitizen citizen : citizens.militaryCitizens(state.id())) {
                updateTask(citizen, orders);
                if (citizen.mode() != CitizenMode.PHYSICAL || world == null) {
                    continue;
                }
                org.bukkit.entity.Pillager entity = citizens.physicalEntity(citizen);
                if (entity == null || entity.getWorld() != world) {
                    continue;
                }
                if (entity.getTarget() != null && !entity.getTarget().isDead()) {
                    continue;
                }
                org.bukkit.Location target = new org.bukkit.Location(world, destination.x(), entity.getLocation().getY(), destination.z());
                CitizenNavigator.MoveResult result = navigator.move(entity, target, plugin.getConfig().getDouble("strategic-states.population.physical-movement-speed", 0.35D));
                if (result == CitizenNavigator.MoveResult.UNLOADED && orders.mode() != ArmyMode.PEACE) {
                    citizens.virtualizeForTravel(citizen);
                }
            }
        }
    }

    private void updateTask(StateCitizen citizen, Orders orders) {
        if (orders.warId() != null) {
            citizens.assignment(citizen, "WAR:" + orders.warId(), citizen.homeBuilding(), citizen.workBuilding());
        } else if (citizen.currentTask() != null && citizen.currentTask().startsWith("WAR:")) {
            citizens.assignment(citizen, "PATROL", citizen.homeBuilding(), null);
        }
    }

    private boolean safeToMaterialize(StatePosition position, StatePosition destination) {
        org.bukkit.World world = plugin.getServer().getWorld(position.world());
        if (world == null) {
            return false;
        }
        int chunkX = (int) Math.floor(position.x()) >> 4;
        int chunkZ = (int) Math.floor(position.z()) >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }
        double dx = destination.x() - position.x();
        double dz = destination.z() - position.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 96.0D) {
            return true;
        }
        double scale = 24.0D / Math.max(1.0D, length);
        int aheadX = (int) Math.floor(position.x() + dx * scale) >> 4;
        int aheadZ = (int) Math.floor(position.z() + dz * scale) >> 4;
        return world.isChunkLoaded(aheadX, aheadZ);
    }

    private StatePosition step(StatePosition current, StatePosition target, double maximum) {
        if (current == null || !current.world().equals(target.world())) {
            return target;
        }
        double x = target.x() - current.x();
        double z = target.z() - current.z();
        double length = Math.sqrt(x * x + z * z);
        if (length <= maximum || length == 0.0D) {
            return target;
        }
        double scale = maximum / length;
        return new StatePosition(current.world(), current.x() + x * scale, current.y(), current.z() + z * scale);
    }

    private StatePosition average(Collection<StateCitizen> military, StatePosition fallback) {
        if (military.isEmpty()) {
            return fallback;
        }
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        int count = 0;
        for (StateCitizen citizen : military) {
            StatePosition position = citizens.currentPosition(citizen);
            if (position == null || !fallback.world().equals(position.world())) {
                continue;
            }
            x += position.x();
            y += position.y();
            z += position.z();
            count++;
        }
        return count == 0 ? fallback : new StatePosition(fallback.world(), x / count, y / count, z / count);
    }

    private double distanceSquared(StatePosition first, StatePosition second) {
        if (first == null || second == null || !first.world().equals(second.world())) {
            return Double.MAX_VALUE;
        }
        double x = first.x() - second.x();
        double z = first.z() - second.z();
        return x * x + z * z;
    }
}
