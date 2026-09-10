package ua.bobster.defence.strategicstates.construction;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.economy.ResourceType;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.economy.WarehouseManager;
import ua.bobster.defence.strategicstates.model.StatePosition;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.territory.TerritoryMapHook;
import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;
import ua.bobster.defence.strategicstates.combat.StrategicWeaponType;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ConstructionManager {

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final TerritoryMapHook territories;
    private final CitizenManager citizens;
    private final StructureTemplateAdapter templates;
    private final MarineFoundationManager marineFoundations = new MarineFoundationManager();
    private final Map<UUID, StateBuilding> buildings = new LinkedHashMap<>();
    private final Map<UUID, StructureTemplatePlan> activePlans = new LinkedHashMap<>();
    private final Map<UUID, Double> placementBudgets = new LinkedHashMap<>();
    private WarehouseManager warehouses;
    private Predicate<UUID> stateOperational = ignored -> true;
    private Consumer<StrategicState> territoryExpansion = state -> {
    };

    private long durationMillis;
    private double blocksPerSecond;
    private double builderMovementSpeed;
    private boolean visualBuilders;
    private int minimumBuildingSpacing;
    private long repairDelayMillis;
    private long rebuildDelayMillis;
    private BukkitTask materializationTask;

    public ConstructionManager(BobsterDefence plugin, StateRepository repository, TerritoryMapHook territories, CitizenManager citizens) {
        this.plugin = plugin;
        this.repository = repository;
        this.territories = territories;
        this.citizens = citizens;
        this.templates = new PaperStructureTemplateAdapter(plugin);
        reload();
    }

    public void reload() {
        durationMillis = Math.max(10L, plugin.getConfig().getLong("strategic-states.construction.virtual-duration-seconds", 300L)) * 1000L;
        blocksPerSecond = Math.max(0.1D, plugin.getConfig().getDouble("strategic-states.construction.blocks-per-second", 4.0D));
        builderMovementSpeed = Math.clamp(plugin.getConfig().getDouble("strategic-states.construction.builder-movement-speed", 0.35D), 0.05D, 1.0D);
        visualBuilders = plugin.getConfig().getBoolean("strategic-states.construction.visual-builders", true);
        minimumBuildingSpacing = Math.max(1, plugin.getConfig().getInt("strategic-states.construction.minimum-building-spacing", 6));
        repairDelayMillis = Math.max(10L, plugin.getConfig().getLong("strategic-states.construction.repair-delay-seconds", 60L)) * 1000L;
        rebuildDelayMillis = Math.max(30L, plugin.getConfig().getLong("strategic-states.construction.rebuild-delay-seconds", 180L)) * 1000L;
    }

    public void warehouseManager(WarehouseManager warehouses) {
        this.warehouses = warehouses;
    }

    public void territoryExpansion(Consumer<StrategicState> territoryExpansion) {
        this.territoryExpansion = territoryExpansion == null ? state -> {
        } : territoryExpansion;
    }

    public void stateOperational(Predicate<UUID> stateOperational) {
        this.stateOperational = stateOperational == null ? ignored -> true : stateOperational;
    }

    public void start() {
        if (materializationTask != null) {
            materializationTask.cancel();
        }
        materializationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickMaterialization, 1L, 1L);
        plugin.getServer().getScheduler().runTask(plugin, this::resumeMaterialization);
    }

    public void shutdown() {
        if (materializationTask != null) {
            materializationTask.cancel();
            materializationTask = null;
        }
        activePlans.clear();
        placementBudgets.clear();
    }

    public void load(Collection<StateBuilding> loaded, long now) {
        buildings.clear();
        activePlans.clear();
        placementBudgets.clear();
        for (StateBuilding building : loaded) {
            if (building.status() == BuildingStatus.BUILDING) {
                building.lastProgress(now);
            }
            buildings.put(building.id(), building);
        }
    }

    public Collection<StateBuilding> all(UUID stateId) {
        List<StateBuilding> result = new ArrayList<>();
        for (StateBuilding building : buildings.values()) {
            if (building.stateId().equals(stateId)) {
                result.add(building);
            }
        }
        return List.copyOf(result);
    }

    public Collection<StateBuilding> allBuildings() {
        return List.copyOf(buildings.values());
    }

    public StateBuilding workplace(UUID stateId, BuildingType type) {
        List<StateBuilding> matches = workplaces(stateId, type);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    public List<StateBuilding> workplaces(UUID stateId, BuildingType type) {
        List<StateBuilding> result = new ArrayList<>();
        for (StateBuilding building : buildings.values()) {
            if (building.stateId().equals(stateId) && building.type() == type && building.integrity() > 0.25D
                    && (building.status() == BuildingStatus.MATERIALIZED || building.status() == BuildingStatus.DAMAGED)) {
                result.add(building);
            }
        }
        return List.copyOf(result);
    }

    public boolean operational(UUID stateId, BuildingType type) {
        for (StateBuilding building : buildings.values()) {
            if (building.stateId().equals(stateId) && building.type() == type && building.integrity() > 0.25D
                    && (building.status() == BuildingStatus.COMPLETE_VIRTUAL || building.status() == BuildingStatus.MATERIALIZING
                    || building.status() == BuildingStatus.MATERIALIZED || building.status() == BuildingStatus.DAMAGED)) {
                return true;
            }
        }
        return false;
    }

    public void tick(StrategicState state, StateEconomy economy, long now) {
        relocatePending(state);
        repair(state, economy, now);
        ensureFoundation(state, economy, now);
        for (StateBuilding building : all(state.id())) {
            if (building.status() != BuildingStatus.BUILDING) {
                continue;
            }
            long elapsed = Math.max(0L, now - building.lastProgress());
            building.progress(building.progress() + elapsed / (double) durationMillis);
            building.lastProgress(now);
            if (building.progress() >= 1.0D) {
                building.status(BuildingStatus.COMPLETE_VIRTUAL);
                applyEffects(building, economy);
                try {
                    repository.insertBuilding(building, economy);
                    tryMaterialize(building);
                } catch (SQLException ex) {
                    rollbackEffects(building, economy);
                    building.status(BuildingStatus.BUILDING);
                    building.progress(0.999D);
                    plugin.getLogger().warning("Не вдалося завершити будівлю " + building.id() + ": " + ex.getMessage());
                }
                continue;
            }
            repository.saveBuildingAsync(building);
        }
    }

    public void materializeChunk(Chunk chunk) {
        for (StateBuilding building : buildings.values()) {
            if (!stateOperational.test(building.stateId()) || (building.status() != BuildingStatus.COMPLETE_VIRTUAL && building.status() != BuildingStatus.MATERIALIZING) || !touches(building, chunk) || activePlans.containsKey(building.id())) {
                continue;
            }
            StructureTemplatePlan plan = templates.plan(building.template());
            if (plan == null) {
                block(building, "Шаблон відсутній або пошкоджений: " + building.template());
                continue;
            }
            int recovered = templates.recoverCompletedBlocks(building, plan, building.materializedBlocks());
            marineFoundations.prepare(building);
            StructureTemplateAdapter.MaterializationResult result = templates.validate(building, plan, recovered);
            if (result.waiting()) {
                continue;
            }
            if (!result.success()) {
                block(building, result.message());
                continue;
            }
            if (recovered == 0 && plugin.getConfig().getBoolean("strategic-states.construction.allow-terrain-overwrite", true)) {
                clearFootprint(building);
            }
            building.materializedBlocks(recovered);
            building.status(BuildingStatus.MATERIALIZING);
            building.blockedReason(null);
            try {
                repository.insertBuilding(building);
                activePlans.put(building.id(), plan);
                placementBudgets.put(building.id(), 0.0D);
            } catch (SQLException ex) {
                block(building, "Не вдалося зафіксувати MATERIALIZING: " + ex.getMessage());
            }
        }
    }

    private void clearFootprint(StateBuilding building) {
        World world = Bukkit.getWorld(building.origin().world());
        if (world == null) {
            return;
        }
        int originX = (int) building.origin().x();
        int originY = (int) building.origin().y();
        int originZ = (int) building.origin().z();
        for (int x = 0; x < building.sizeX(); x++) {
            for (int y = 0; y < building.sizeY(); y++) {
                for (int z = 0; z < building.sizeZ(); z++) {
                    org.bukkit.block.Block block = world.getBlockAt(originX + x, originY + y, originZ + z);
                    if (block.getType() != org.bukkit.Material.BEDROCK && block.getType() != org.bukkit.Material.BARRIER) {
                        block.setType(org.bukkit.Material.AIR, false);
                    }
                }
            }
        }
    }

    private void tryMaterialize(StateBuilding building) {
        if (!stateOperational.test(building.stateId())) {
            return;
        }
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(building.origin().world());
        if (world == null) {
            return;
        }
        int chunkX = (int) Math.floor(building.origin().x()) >> 4;
        int chunkZ = (int) Math.floor(building.origin().z()) >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            materializeChunk(world.getChunkAt(chunkX, chunkZ));
        }
    }

    private void resumeMaterialization() {
        for (StateBuilding building : new ArrayList<>(buildings.values())) {
            if (building.status() == BuildingStatus.COMPLETE_VIRTUAL || building.status() == BuildingStatus.MATERIALIZING) {
                tryMaterialize(building);
            }
        }
    }

    private void tickMaterialization() {
        for (Map.Entry<UUID, StructureTemplatePlan> entry : new ArrayList<>(activePlans.entrySet())) {
            StateBuilding building = buildings.get(entry.getKey());
            StructureTemplatePlan plan = entry.getValue();
            if (building == null || building.status() != BuildingStatus.MATERIALIZING) {
                stopMaterialization(entry.getKey());
                continue;
            }
            if (!allBuildingChunksLoaded(building)) {
                stopMaterialization(building.id());
                continue;
            }
            double budget = placementBudgets.getOrDefault(building.id(), 0.0D) + blocksPerSecond / 20.0D;
            while (budget >= 1.0D && building.materializedBlocks() < plan.blocks().size()) {
                StructureBlockPlacement placement = plan.blocks().get(building.materializedBlocks());
                if (!placementChunkLoaded(building, placement)) {
                    stopMaterialization(building.id());
                    break;
                }
                Location placed = templates.place(building, placement);
                if (placed == null) {
                    block(building, "Сторонній блок з'явився під час materialization");
                    stopMaterialization(building.id());
                    break;
                }
                building.materializedBlocks(building.materializedBlocks() + 1);
                repository.saveBuildingAsync(building);
                if (visualBuilders) {
                    citizens.animateBuilder(building.stateId(), building.id(), builderWorkPoint(building, placed), placed, placed.getBlock().getType(), builderMovementSpeed);
                }
                budget -= 1.0D;
            }
            placementBudgets.put(building.id(), budget);
            if (building.materializedBlocks() >= plan.blocks().size()) {
                finishMaterialization(building);
            }
        }
    }

    private void finishMaterialization(StateBuilding building) {
        building.status(BuildingStatus.MATERIALIZED);
        building.blockedReason(null);
        try {
            repository.insertBuilding(building);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не вдалося зафіксувати MATERIALIZED " + building.id() + ": " + ex.getMessage());
        }
        citizens.finishBuilding(building.stateId(), building.id());
        stopMaterialization(building.id());
    }

    private Location builderWorkPoint(StateBuilding building, Location action) {
        Location origin = building.origin().location();
        if (origin == null) {
            return action;
        }
        int side = Math.floorMod(building.id().hashCode(), 4);
        double x = side == 1 ? building.sizeX() + 2.0D : side == 3 ? -2.0D : Math.clamp(action.getX() - origin.getX(), 0.5D, building.sizeX() - 0.5D);
        double z = side == 0 ? -2.0D : side == 2 ? building.sizeZ() + 2.0D : Math.clamp(action.getZ() - origin.getZ(), 0.5D, building.sizeZ() - 0.5D);
        Location target = origin.add(x, 1.0D, z);
        World world = target.getWorld();
        if (world != null && world.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) {
            target.setY(world.getHighestBlockYAt(target) + 1.0D);
        }
        return target;
    }

    private void stopMaterialization(UUID buildingId) {
        activePlans.remove(buildingId);
        placementBudgets.remove(buildingId);
    }

    public void pauseState(UUID stateId) {
        for (StateBuilding building : all(stateId)) {
            stopMaterialization(building.id());
        }
    }

    private boolean placementChunkLoaded(StateBuilding building, StructureBlockPlacement placement) {
        World world = Bukkit.getWorld(building.origin().world());
        if (world == null) {
            return false;
        }
        int x = ((int) building.origin().x() + placement.x()) >> 4;
        int z = ((int) building.origin().z() + placement.z()) >> 4;
        return world.isChunkLoaded(x, z);
    }

    private boolean allBuildingChunksLoaded(StateBuilding building) {
        World world = Bukkit.getWorld(building.origin().world());
        if (world == null) {
            return false;
        }
        int minimumX = (int) building.origin().x() >> 4;
        int maximumX = ((int) building.origin().x() + building.sizeX() - 1) >> 4;
        int minimumZ = (int) building.origin().z() >> 4;
        int maximumZ = ((int) building.origin().z() + building.sizeZ() - 1) >> 4;
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void block(StateBuilding building, String reason) {
        building.status(BuildingStatus.BLOCKED);
        building.blockedReason(reason);
        repository.saveBuildingAsync(building);
        citizens.finishBuilding(building.stateId(), building.id());
    }

    public void damageAt(UUID stateId, Location location, StrategicWeaponType weapon, double power) {
        for (StateBuilding building : all(stateId)) {
            if (!building.containsHorizontal(location.getWorld().getName(), location.getX(), location.getZ()) || building.status() == BuildingStatus.DESTROYED) {
                continue;
            }
            double multiplier = weapon == StrategicWeaponType.BALLISTIC ? 0.08D : 0.04D;
            building.integrity(building.integrity() - power * multiplier);
            boolean incomplete = building.status() == BuildingStatus.BUILDING || building.status() == BuildingStatus.COMPLETE_VIRTUAL || building.status() == BuildingStatus.MATERIALIZING || building.status() == BuildingStatus.BLOCKED;
            if (incomplete && building.integrity() <= 0.0D) {
                building.integrity(0.05D);
            }
            building.status(building.integrity() <= 0.0D ? BuildingStatus.DESTROYED : BuildingStatus.DAMAGED);
            building.lastProgress(System.currentTimeMillis());
            stopMaterialization(building.id());
            citizens.finishBuilding(building.stateId(), building.id());
            repository.saveBuildingAsync(building);
        }
    }

    private void repair(StrategicState state, StateEconomy economy, long now) {
        for (StateBuilding building : all(state.id())) {
            if (building.status() != BuildingStatus.DAMAGED && building.status() != BuildingStatus.DESTROYED) {
                continue;
            }
            long delay = building.status() == BuildingStatus.DESTROYED ? rebuildDelayMillis : repairDelayMillis;
            if (now - building.lastProgress() < delay || building.status() == BuildingStatus.DESTROYED && replacementExists(building)) {
                continue;
            }
            double fraction = building.status() == BuildingStatus.DESTROYED ? 1.0D : Math.max(0.15D, 1.0D - building.integrity());
            double previousIntegrity = building.integrity();
            BuildingStatus previousStatus = building.status();
            Map<ResourceType, Double> repairCost = scaledCost(building.type(), fraction);
            if (warehouses != null && !warehouses.withdraw(state.id(), economy, repairCost)) {
                building.blockedReason("Ремонт очікує матеріали: " + repairCost);
                repository.saveBuildingAsync(building);
                continue;
            }
            if (warehouses == null && !consume(economy, repairCost)) {
                building.blockedReason("Ремонт очікує матеріали: " + repairCost);
                repository.saveBuildingAsync(building);
                continue;
            }
            building.integrity(1.0D);
            building.progress(1.0D);
            building.materializedBlocks(0);
            building.status(BuildingStatus.COMPLETE_VIRTUAL);
            building.blockedReason(null);
            building.lastProgress(now);
            try {
                repository.insertBuilding(building, economy);
                tryMaterialize(building);
            } catch (SQLException ex) {
                if (warehouses != null) {
                    warehouses.deposit(state.id(), economy, repairCost);
                } else {
                    repairCost.forEach(economy::add);
                }
                building.integrity(previousIntegrity);
                building.status(previousStatus);
                plugin.getLogger().warning("Не вдалося запустити ремонт " + building.id() + ": " + ex.getMessage());
            }
        }
    }

    private boolean replacementExists(StateBuilding damaged) {
        for (StateBuilding building : buildings.values()) {
            if (!building.id().equals(damaged.id()) && building.stateId().equals(damaged.stateId()) && building.template().equals(damaged.template()) && building.status() != BuildingStatus.DESTROYED) {
                return true;
            }
        }
        return false;
    }

    private Map<ResourceType, Double> scaledCost(BuildingType type, double fraction) {
        Map<ResourceType, Double> result = new EnumMap<>(ResourceType.class);
        for (Map.Entry<ResourceType, Double> entry : cost(type).entrySet()) {
            result.put(entry.getKey(), Math.max(1.0D, Math.ceil(entry.getValue() * fraction)));
        }
        return result;
    }

    private boolean consume(StateEconomy economy, Map<ResourceType, Double> resources) {
        for (Map.Entry<ResourceType, Double> entry : resources.entrySet()) {
            if (economy.amount(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        resources.forEach(economy::consume);
        return true;
    }

    public void destroyState(UUID stateId) {
        for (StateBuilding building : all(stateId)) {
            building.status(BuildingStatus.DESTROYED);
            building.integrity(0.0D);
            stopMaterialization(building.id());
            citizens.finishBuilding(building.stateId(), building.id());
            repository.saveBuildingAsync(building);
        }
    }

    public void saveAll() {
        for (StateBuilding building : buildings.values()) {
            repository.saveBuildingAsync(building);
        }
    }

    private void ensureFoundation(StrategicState state, StateEconomy economy, long now) {
        if (!hasTemplate(state.id(), BuildingType.HOUSE, "house_02.nbt")) {
            plan(state, economy, BuildingType.HOUSE, "house_02.nbt", now);
        } else if (economy.housingCapacity() - citizens.livingPopulation(state.id()) <= 2 && !pendingBuilding(state.id(), BuildingType.HOUSE)) {
            plan(state, economy, BuildingType.HOUSE, "house_02.nbt", now);
        }
        if (!hasTemplate(state.id(), BuildingType.FARM, "farm_02.nbt")) {
            plan(state, economy, BuildingType.FARM, "farm_02.nbt", now);
        }
        if (!hasTemplate(state.id(), BuildingType.WAREHOUSE, "warehouse_02.nbt")) {
            plan(state, economy, BuildingType.WAREHOUSE, "warehouse_02.nbt", now);
        }
        if (state.developmentLevel() >= 1 && !hasTemplate(state.id(), BuildingType.WORKSHOP, "workshop_02.nbt")) {
            plan(state, economy, BuildingType.WORKSHOP, "workshop_02.nbt", now);
        }
        if (state.developmentLevel() >= 3 && !hasTemplate(state.id(), BuildingType.BARRACKS, "barracks_02.nbt")) {
            plan(state, economy, BuildingType.BARRACKS, "barracks_02.nbt", now);
        }
        if (state.developmentLevel() >= 4 && !hasTemplate(state.id(), BuildingType.MILITARY_FACTORY, "military_factory_02.nbt")) {
            plan(state, economy, BuildingType.MILITARY_FACTORY, "military_factory_02.nbt", now);
        }
        if (state.developmentLevel() >= 1 && !hasTemplate(state.id(), BuildingType.DRONE_FACTORY, "drone_factory_02.nbt")) {
            plan(state, economy, BuildingType.DRONE_FACTORY, "drone_factory_02.nbt", now);
        }
        if (state.developmentLevel() >= 4 && !hasTemplate(state.id(), BuildingType.AIR_DEFENCE_SITE, "air_defence_02.nbt")) {
            plan(state, economy, BuildingType.AIR_DEFENCE_SITE, "air_defence_02.nbt", now);
        }
        if (state.developmentLevel() >= 4 && !hasTemplate(state.id(), BuildingType.MISSILE_FACTORY, "missile_factory_02.nbt")) {
            plan(state, economy, BuildingType.MISSILE_FACTORY, "missile_factory_02.nbt", now);
        }
    }

    private boolean hasBuilding(UUID stateId, BuildingType type) {
        for (StateBuilding building : buildings.values()) {
            if (building.stateId().equals(stateId) && building.type() == type && building.status() != BuildingStatus.DESTROYED) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTemplate(UUID stateId, BuildingType type, String template) {
        for (StateBuilding building : buildings.values()) {
            if (building.stateId().equals(stateId) && building.type() == type && building.template().equals(template) && building.status() != BuildingStatus.DESTROYED) {
                return true;
            }
        }
        return false;
    }

    private boolean pendingBuilding(UUID stateId, BuildingType type) {
        for (StateBuilding building : buildings.values()) {
            if (building.stateId().equals(stateId) && building.type() == type
                    && (building.status() == BuildingStatus.PLANNED || building.status() == BuildingStatus.BUILDING || building.status() == BuildingStatus.BLOCKED)) {
                return true;
            }
        }
        return false;
    }

    private void plan(StrategicState state, StateEconomy economy, BuildingType type, String template, long now) {
        StructureTemplateAdapter.TemplateInfo info = templates.inspect(template);
        if (info == null) {
            return;
        }
        StructureTemplatePlan blockPlan = templates.plan(template);
        if (blockPlan == null) {
            return;
        }
        StatePosition origin = findOrigin(state, type, info, blockPlan, template, null);
        if (origin == null) {
            territoryExpansion.accept(state);
            return;
        }
        Map<ResourceType, Double> cost = cost(type);
        if (warehouses != null) {
            if (!warehouses.withdraw(state.id(), economy, cost)) {
                return;
            }
        } else {
            for (Map.Entry<ResourceType, Double> entry : cost.entrySet()) {
                if (economy.amount(entry.getKey()) < entry.getValue()) {
                    return;
                }
            }
            for (Map.Entry<ResourceType, Double> entry : cost.entrySet()) {
                economy.consume(entry.getKey(), entry.getValue());
            }
        }
        StateBuilding building = new StateBuilding(UUID.randomUUID(), state.id(), type, template, origin, info.sizeX(), info.sizeY(), info.sizeZ(), now, BuildingStatus.BUILDING, 0.0D, 0, now, false, 1.0D, null);
        try {
            repository.insertBuilding(building, economy);
            buildings.put(building.id(), building);
        } catch (SQLException ex) {
            if (warehouses != null) {
                warehouses.deposit(state.id(), economy, cost);
            } else {
                for (Map.Entry<ResourceType, Double> entry : cost.entrySet()) {
                    economy.add(entry.getKey(), entry.getValue());
                }
            }
            plugin.getLogger().warning("Не вдалося запланувати " + type + " для " + state.name() + ": " + ex.getMessage());
        }
    }

    private StatePosition findOrigin(StrategicState state, BuildingType type, StructureTemplateAdapter.TemplateInfo info, StructureTemplatePlan blockPlan, String template, UUID excludedId) {
        int step = Math.max(info.sizeX(), info.sizeZ()) + minimumBuildingSpacing + 3;
        for (int ring = 1; ring <= 8; ring++) {
            for (int gridX = -ring; gridX <= ring; gridX++) {
                for (int gridZ = -ring; gridZ <= ring; gridZ++) {
                    if (Math.max(Math.abs(gridX), Math.abs(gridZ)) != ring) {
                        continue;
                    }
                    int x = (int) state.core().x() + gridX * step - info.sizeX() / 2;
                    int z = (int) state.core().z() + gridZ * step - info.sizeZ() / 2;
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(state.core().world());
            if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z) + 1;
                    StateBuilding candidate = new StateBuilding(UUID.randomUUID(), state.id(), type, template, new StatePosition(state.core().world(), x, y, z), info.sizeX(), info.sizeY(), info.sizeZ(), 0L, BuildingStatus.PLANNED, 0.0D, 0, 0L, false, 1.0D, null);
            if (!insideTerritory(state, candidate) || intersects(candidate, excludedId, minimumBuildingSpacing)) {
                continue;
            }
            StructureTemplateAdapter.MaterializationResult validation = templates.validate(candidate, blockPlan, 0);
            if (!validation.success()) {
                continue;
            }
            return candidate.origin();
                }
            }
        }
        return null;
    }

    private boolean insideTerritory(StrategicState state, StateBuilding building) {
        double[][] corners = {{building.origin().x(), building.origin().z()}, {building.origin().x() + building.sizeX() - 1, building.origin().z()}, {building.origin().x(), building.origin().z() + building.sizeZ() - 1}, {building.origin().x() + building.sizeX() - 1, building.origin().z() + building.sizeZ() - 1}};
        for (double[] corner : corners) {
            TerritorySnapshot territory = territories.at(building.origin().world(), corner[0], corner[1]);
            if (territory == null || territory.ownerKind() != TerritoryOwnerKind.STRATEGIC_STATE || !state.id().equals(territory.ownerId())) {
                return false;
            }
        }
        return true;
    }

    private boolean intersects(StateBuilding candidate, UUID excludedId, int spacing) {
        for (StateBuilding building : buildings.values()) {
            if (!building.id().equals(excludedId) && building.status() != BuildingStatus.DESTROYED && horizontalIntersection(candidate, building, spacing)) {
                return true;
            }
        }
        return false;
    }

    private boolean horizontalIntersection(StateBuilding first, StateBuilding second, int spacing) {
        if (!first.origin().world().equals(second.origin().world())) {
            return false;
        }
        return first.origin().x() < second.origin().x() + second.sizeX() + spacing
                && first.origin().x() + first.sizeX() + spacing > second.origin().x()
                && first.origin().z() < second.origin().z() + second.sizeZ() + spacing
                && first.origin().z() + first.sizeZ() + spacing > second.origin().z();
    }

    private void relocatePending(StrategicState state) {
        for (StateBuilding building : all(state.id())) {
            if (building.materializedBlocks() > 0 || building.status() != BuildingStatus.BUILDING && building.status() != BuildingStatus.COMPLETE_VIRTUAL || !conflictsWithPriority(building)) {
                continue;
            }
            StructureTemplateAdapter.TemplateInfo info = templates.inspect(building.template());
            StructureTemplatePlan plan = templates.plan(building.template());
            if (info == null || plan == null) {
                continue;
            }
            StatePosition origin = findOrigin(state, building.type(), info, plan, building.template(), building.id());
            if (origin != null) {
                building.origin(origin);
                building.blockedReason(null);
                repository.saveBuildingAsync(building);
                if (building.status() == BuildingStatus.COMPLETE_VIRTUAL) {
                    tryMaterialize(building);
                }
            }
        }
    }

    private boolean conflictsWithPriority(StateBuilding candidate) {
        for (StateBuilding other : buildings.values()) {
            if (candidate.id().equals(other.id()) || !candidate.stateId().equals(other.stateId()) || other.status() == BuildingStatus.DESTROYED) {
                continue;
            }
            boolean materialized = other.materializedBlocks() > 0 || other.status() == BuildingStatus.MATERIALIZED || other.status() == BuildingStatus.DAMAGED;
            boolean earlier = other.createdAt() < candidate.createdAt() || other.createdAt() == candidate.createdAt() && other.id().compareTo(candidate.id()) < 0;
            if ((materialized || earlier) && horizontalIntersection(candidate, other, minimumBuildingSpacing)) {
                return true;
            }
        }
        return false;
    }

    private Map<ResourceType, Double> cost(BuildingType type) {
        Map<ResourceType, Double> result = new EnumMap<>(ResourceType.class);
        if (type == BuildingType.HOUSE) {
            result.put(ResourceType.WOOD, 30.0D);
            result.put(ResourceType.STONE, 15.0D);
        } else if (type == BuildingType.FARM) {
            result.put(ResourceType.WOOD, 30.0D);
            result.put(ResourceType.STONE, 20.0D);
        } else if (type == BuildingType.WAREHOUSE) {
            result.put(ResourceType.WOOD, 90.0D);
            result.put(ResourceType.STONE, 120.0D);
            result.put(ResourceType.IRON, 12.0D);
        } else if (type == BuildingType.WORKSHOP) {
            result.put(ResourceType.WOOD, 60.0D);
            result.put(ResourceType.STONE, 100.0D);
            result.put(ResourceType.IRON, 25.0D);
        } else if (type == BuildingType.BARRACKS) {
            result.put(ResourceType.WOOD, 80.0D);
            result.put(ResourceType.STONE, 120.0D);
            result.put(ResourceType.IRON, 30.0D);
        } else if (type == BuildingType.MILITARY_FACTORY || type == BuildingType.DRONE_FACTORY || type == BuildingType.AIR_DEFENCE_SITE) {
            result.put(ResourceType.STONE, 240.0D);
            result.put(ResourceType.IRON, 120.0D);
            result.put(ResourceType.REDSTONE, 40.0D);
        } else if (type == BuildingType.MISSILE_FACTORY) {
            result.put(ResourceType.STONE, 320.0D);
            result.put(ResourceType.IRON, 180.0D);
            result.put(ResourceType.REDSTONE, 70.0D);
        }
        return result;
    }

    private void applyEffects(StateBuilding building, StateEconomy economy) {
        if (building.effectsApplied()) {
            return;
        }
        if (building.type() == BuildingType.HOUSE) {
            economy.housingCapacity(economy.housingCapacity() + 10);
        }
        building.effectsApplied(true);
    }

    private void rollbackEffects(StateBuilding building, StateEconomy economy) {
        if (!building.effectsApplied()) {
            return;
        }
        if (building.type() == BuildingType.HOUSE) {
            economy.housingCapacity(economy.housingCapacity() - 10);
        }
        building.effectsApplied(false);
    }

    private boolean touches(StateBuilding building, Chunk chunk) {
        if (!building.origin().world().equals(chunk.getWorld().getName())) {
            return false;
        }
        int minimumX = (int) building.origin().x() >> 4;
        int maximumX = ((int) building.origin().x() + building.sizeX() - 1) >> 4;
        int minimumZ = (int) building.origin().z() >> 4;
        int maximumZ = ((int) building.origin().z() + building.sizeZ() - 1) >> 4;
        return chunk.getX() >= minimumX && chunk.getX() <= maximumX && chunk.getZ() >= minimumZ && chunk.getZ() <= maximumZ;
    }
}
