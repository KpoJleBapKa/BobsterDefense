package ua.bobster.defence.strategicstates.population;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Pillager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.construction.BuildingType;
import ua.bobster.defence.strategicstates.construction.ConstructionManager;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.model.StrategicState;

import java.util.UUID;
import java.util.List;
import java.util.function.Function;

public class PhysicalTaskEngine {

    private record Assignment(String task, StateBuilding home, StateBuilding work, Location target, Material tool) {
    }

    private final BobsterDefence plugin;
    private final CitizenManager citizens;
    private final ConstructionManager construction;
    private final Function<UUID, StrategicState> states;
    private final CitizenNavigator navigator = new CitizenNavigator();

    private BukkitTask task;
    private long intervalTicks;
    private double movementSpeed;

    public PhysicalTaskEngine(BobsterDefence plugin, CitizenManager citizens, ConstructionManager construction, Function<UUID, StrategicState> states) {
        this.plugin = plugin;
        this.citizens = citizens;
        this.construction = construction;
        this.states = states;
        reload();
    }

    public void reload() {
        intervalTicks = Math.max(1L, plugin.getConfig().getLong("strategic-states.population.physical-task-interval-ticks", 5L));
        movementSpeed = Math.clamp(plugin.getConfig().getDouble("strategic-states.population.physical-movement-speed", 0.35D), 0.05D, 1.0D);
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
    }

    private void tick() {
        for (StateCitizen citizen : citizens.physicalCitizens()) {
            if (citizen.currentTask() != null && (citizen.currentTask().startsWith("BUILD:") || citizen.currentTask().startsWith("WAR:"))) {
                continue;
            }
            StrategicState state = states.apply(citizen.stateId());
            Pillager pillager = citizens.physicalEntity(citizen);
            if (state == null || pillager == null) {
                continue;
            }
            if (pillager.getTarget() != null && !pillager.getTarget().isDead()) {
                continue;
            }
            Assignment assignment = assignment(citizen, state, pillager.getWorld());
            if (assignment == null || assignment.target() == null) {
                continue;
            }
            citizens.assignment(citizen, assignment.task(), id(assignment.home()), id(assignment.work()));
            CitizenNavigator.MoveResult result = navigator.move(pillager, assignment.target(), movementSpeed);
            if (result == CitizenNavigator.MoveResult.ARRIVED) {
                pillager.lookAt(assignment.target());
                pillager.swingMainHand();
                pillager.getEquipment().setItemInMainHand(assignment.tool().isAir() ? null : new ItemStack(assignment.tool()));
            }
        }
    }

    private Assignment assignment(StateCitizen citizen, StrategicState state, World world) {
        StateBuilding home = select(construction.workplaces(state.id(), BuildingType.HOUSE), citizen.id(), 0L);
        boolean workTime = world.getTime() < 13_000L;
        if (!workTime) {
            Location target = home == null ? roamingPoint(citizen, state, 8.0D, 12.0D, 2L) : workPoint(home, citizen.id(), 2L);
            return new Assignment("RESTING", home, null, target, Material.AIR);
        }
        return switch (citizen.profession()) {
            case FARMER -> workplace(citizen, state, home, BuildingType.FARM, "FARMING", Material.IRON_HOE);
            case LUMBERJACK -> roaming(citizen, state, home, "GATHERING_WOOD", Material.IRON_AXE, 18.0D, 28.0D);
            case MINER -> roaming(citizen, state, home, "MINING", Material.IRON_PICKAXE, 22.0D, 34.0D);
            case BUILDER -> workplace(citizen, state, home, BuildingType.HOUSE, "MAINTENANCE", Material.STONE_BRICKS);
            case ENGINEER -> workplace(citizen, state, home, BuildingType.WORKSHOP, "ENGINEERING", Material.REDSTONE);
            case SOLDIER, CROSSBOWMAN, GUARD, COMMANDER -> roaming(citizen, state, home, "PATROL", Material.CROSSBOW, 16.0D, 28.0D);
            case DRONE_OPERATOR -> workplace(citizen, state, home, BuildingType.DRONE_FACTORY, "DRONE_SHIFT", Material.PHANTOM_MEMBRANE);
            case MISSILE_OPERATOR -> workplace(citizen, state, home, BuildingType.MISSILE_FACTORY, "MISSILE_SHIFT", Material.FIREWORK_ROCKET);
            case AIR_DEFENCE_OPERATOR -> workplace(citizen, state, home, BuildingType.AIR_DEFENCE_SITE, "AIR_DEFENCE_SHIFT", Material.ARROW);
            default -> workplace(citizen, state, home, BuildingType.WAREHOUSE, "LOGISTICS", Material.CHEST);
        };
    }

    private Assignment workplace(StateCitizen citizen, StrategicState state, StateBuilding home, BuildingType type, String task, Material tool) {
        long phase = statePhase(state);
        StateBuilding work = select(construction.workplaces(state.id(), type), citizen.id(), phase / 6L);
        if (work == null) {
            return roaming(citizen, state, home, task, tool, 10.0D, 18.0D);
        }
        return new Assignment(task, home, work, workPoint(work, citizen.id(), phase), tool);
    }

    private Assignment roaming(StateCitizen citizen, StrategicState state, StateBuilding home, String task, Material tool, double minimumRadius, double maximumRadius) {
        return new Assignment(task, home, null, roamingPoint(citizen, state, minimumRadius, maximumRadius, statePhase(state)), tool);
    }

    private Location roamingPoint(StateCitizen citizen, StrategicState state, double minimumRadius, double maximumRadius, long phase) {
        Location core = state.core().location();
        if (core == null) {
            return null;
        }
        int mixed = citizen.id().hashCode() * 31 + Long.hashCode(phase);
        double angle = Math.floorMod(mixed, 360) * Math.PI / 180.0D;
        double range = Math.max(0.0D, maximumRadius - minimumRadius);
        double radius = minimumRadius + Math.floorMod(mixed >>> 8, 1000) / 999.0D * range;
        Location target = core.clone().add(Math.cos(angle) * radius, 1.0D, Math.sin(angle) * radius);
        return surface(target);
    }

    private Location workPoint(StateBuilding building, UUID citizenId, long phase) {
        Location origin = building.origin().location();
        if (origin == null) {
            return null;
        }
        int hash = citizenId.hashCode() * 31 + Long.hashCode(phase);
        int side = Math.floorMod(hash, 4);
        double x = side == 1 ? building.sizeX() + 1.5D : side == 3 ? -1.5D : 0.5D + Math.floorMod(hash >>> 3, Math.max(1, building.sizeX()));
        double z = side == 0 ? -1.5D : side == 2 ? building.sizeZ() + 1.5D : 0.5D + Math.floorMod(hash >>> 7, Math.max(1, building.sizeZ()));
        return surface(origin.add(x, 1.0D, z));
    }

    private StateBuilding select(List<StateBuilding> options, UUID citizenId, long phase) {
        if (options.isEmpty()) {
            return null;
        }
        return options.get(Math.floorMod(citizenId.hashCode() + Long.hashCode(phase), options.size()));
    }

    private long statePhase(StrategicState state) {
        Location core = state.core().location();
        return core == null ? 0L : core.getWorld().getFullTime() / 100L;
    }

    private Location surface(Location location) {
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return location;
        }
        location.setY(world.getHighestBlockYAt(location) + 1.0D);
        return location;
    }

    private String id(StateBuilding building) {
        return building == null ? null : building.id().toString();
    }
}
