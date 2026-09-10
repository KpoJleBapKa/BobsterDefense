package ua.bobster.defence.strategicstates;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.combat.StateThreat;
import ua.bobster.defence.strategicstates.combat.StrategicImpactConfig;
import ua.bobster.defence.strategicstates.combat.StrategicWeaponType;
import ua.bobster.defence.strategicstates.combat.StrategicStrikeManager;
import ua.bobster.defence.strategicstates.combat.StateAirDefenceManager;
import ua.bobster.defence.strategicstates.combat.StateInstallationManager;
import ua.bobster.defence.strategicstates.core.StateCoreItem;
import ua.bobster.defence.strategicstates.construction.ConstructionManager;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.economy.ResourceType;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.economy.WarehouseManager;
import ua.bobster.defence.strategicstates.model.StatePosition;
import ua.bobster.defence.strategicstates.model.StateStatus;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.StateCitizen;
import ua.bobster.defence.strategicstates.population.PhysicalTaskEngine;
import ua.bobster.defence.strategicstates.population.CitizenProfession;
import ua.bobster.defence.strategicstates.capture.CaptureSector;
import ua.bobster.defence.strategicstates.capture.CaptureSectorManager;
import ua.bobster.defence.strategicstates.capture.CaptureEngine;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarManager;
import ua.bobster.defence.strategicstates.war.WarStatus;
import ua.bobster.defence.strategicstates.army.ArmyCombatEngine;
import ua.bobster.defence.strategicstates.army.ArmyManager;
import ua.bobster.defence.strategicstates.army.StateArmy;
import ua.bobster.defence.strategicstates.army.LocalDefenceEngine;
import ua.bobster.defence.strategicstates.arsenal.ArsenalItem;
import ua.bobster.defence.strategicstates.arsenal.MilitaryProductionManager;
import ua.bobster.defence.strategicstates.arsenal.StateArsenal;
import ua.bobster.defence.strategicstates.arsenal.ArmamentStorageManager;
import ua.bobster.defence.strategicstates.diplomacy.DiplomacyAI;
import ua.bobster.defence.strategicstates.diplomacy.StateRelation;
import ua.bobster.defence.strategicstates.territory.TerritoryHookException;
import ua.bobster.defence.strategicstates.territory.TerritoryMapHook;
import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;
import ua.bobster.defence.strategicstates.territory.TerritoryPartSnapshot;
import ua.bobster.defence.strategicstates.territory.TerritoryPointSnapshot;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public class StrategicStateManager {

    public record CreationResult(boolean success, String message, StrategicState state) {
    }

    public record ImpactResult(StrategicState state, boolean groundImpact, double threat, boolean retaliationEligible) {
    }

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final TerritoryMapHook territories;
    private final StateIdentityFactory identities = new StateIdentityFactory();
    private final StateCoreItem coreItem;
    private final Map<UUID, StrategicState> states = new LinkedHashMap<>();
    private final Map<UUID, StateEconomy> economies = new LinkedHashMap<>();
    private final Map<UUID, Map<CombatPrincipal, StateThreat>> threats = new LinkedHashMap<>();
    private final Set<UUID> protectedOwners = new LinkedHashSet<>();
    private final Map<UUID, Long> lastTerritoryExpansion = new LinkedHashMap<>();
    private final CitizenManager citizens;
    private final StateSimulationEngine simulation = new StateSimulationEngine();
    private final ConstructionManager construction;
    private final WarehouseManager warehouses;
    private final ArmamentStorageManager armaments;
    private final PhysicalTaskEngine physicalTasks;
    private final CaptureSectorManager sectors;
    private final WarManager wars;
    private final CaptureEngine capture;
    private final ArmyManager armies;
    private final ArmyCombatEngine armyCombat;
    private final LocalDefenceEngine localDefence;
    private final MilitaryProductionManager production;
    private final StrategicStrikeManager strikes;
    private final StateAirDefenceManager airDefence;
    private final StateInstallationManager installations;
    private final DiplomacyAI diplomacy;

    private StrategicStateConfig config;
    private StrategicImpactConfig impactConfig;
    private BukkitTask simulationTask;
    private boolean available;
    private boolean attacksEnabled;
    private long lastSectorSynchronization;

    public StrategicStateManager(BobsterDefence plugin) {
        this.plugin = plugin;
        this.config = StrategicStateConfig.load(plugin);
        this.impactConfig = StrategicImpactConfig.load(plugin.getConfig());
        this.repository = new StateRepository(plugin);
        this.territories = new TerritoryMapHook(plugin);
        this.coreItem = new StateCoreItem(plugin);
        this.citizens = new CitizenManager(plugin, repository, this::populationChanged);
        this.construction = new ConstructionManager(plugin, repository, territories, citizens);
        this.construction.stateOperational(stateId -> {
            StrategicState state = states.get(stateId);
            return state != null && state.status() != StateStatus.PAUSED && state.status() != StateStatus.DESTROYED;
        });
        this.construction.territoryExpansion(this::expandForConstruction);
        this.warehouses = new WarehouseManager(plugin, repository, construction);
        this.armaments = new ArmamentStorageManager(plugin, construction);
        this.construction.warehouseManager(warehouses);
        this.physicalTasks = new PhysicalTaskEngine(plugin, citizens, construction, states::get);
        this.sectors = new CaptureSectorManager(plugin, repository);
        this.wars = new WarManager(plugin, repository, this);
        this.capture = new CaptureEngine(plugin, this, citizens, sectors, wars, territories);
        this.armies = new ArmyManager(plugin, repository, citizens, wars, sectors, this::active, states::get);
        this.armyCombat = new ArmyCombatEngine(plugin, citizens, wars);
        this.localDefence = new LocalDefenceEngine(plugin, citizens, territories, states::get, this::alliedPlayer, this::intrusion);
        this.production = new MilitaryProductionManager(plugin, repository, construction, warehouses, armaments, citizens, this::active, economies::get);
        this.installations = new StateInstallationManager(plugin);
        this.strikes = new StrategicStrikeManager(plugin, this, production, repository, citizens, installations);
        this.airDefence = new StateAirDefenceManager(plugin, construction, citizens, production, installations, this::active);
        this.diplomacy = new DiplomacyAI(plugin, repository, this);
    }

    public void start() {
        if (!config.enabled()) {
            return;
        }
        migrateTerritoryRadiusConfig();
        config = StrategicStateConfig.load(plugin);
        try {
            repository.initialize();
            long now = System.currentTimeMillis();
            for (StrategicState state : repository.loadStates()) {
                state.lastSimulation(now);
                states.put(state.id(), state);
            }
            citizens.load(repository.loadCitizens());
            construction.load(repository.loadBuildings(), now);
            sectors.load(repository.loadCaptureSectors());
            wars.load(repository.loadWars());
            armies.load(repository.loadArmies(), now);
            production.load(repository.loadArsenals(now), now);
            strikes.load();
            diplomacy.load(repository.loadRelations());
            for (StateThreat threat : repository.loadThreats()) {
                if (states.containsKey(threat.stateId())) {
                    threats.computeIfAbsent(threat.stateId(), ignored -> new LinkedHashMap<>()).put(threat.attacker(), threat);
                }
            }
            Map<UUID, StateEconomy> loadedEconomies = repository.loadEconomies();
            for (StrategicState state : states.values()) {
                StateEconomy economy = loadedEconomies.get(state.id());
                if (economy == null) {
                    economy = StateEconomy.initial(state.id(), initialHousingCapacity(state), now);
                }
                economy.lastEconomyTick(now);
                economy.lastPopulationTick(now);
                economies.put(state.id(), economy);
                repository.saveEconomyAsync(economy);
            }
            attacksEnabled = repository.settingBoolean("attacksEnabled", config.attacksEnabledOnFirstStart());
            protectedOwners.addAll(repository.loadProtectedOwners());
            if (!repository.settingBoolean("protectedOwnersSeeded", false)) {
                seedProtectedOwners();
                repository.setBoolean("protectedOwnersSeeded", true);
            }
            if (!attacksEnabled) {
                wars.ceasefireAll("ATTACKS_DISABLED_ON_START");
            }
            for (UUID owner : protectedOwners) {
                wars.ceasefireTarget(owner, "TARGET_PROTECTED_ON_START");
            }
            available = true;
            sectors.synchronize(territories.all());
            reconcileTerritoryOwners();
            compactOversizedTerritories();
            lastSectorSynchronization = now;
            construction.start();
            physicalTasks.start();
            capture.start();
            armies.start();
            armyCombat.start();
            localDefence.start();
            wars.start();
            production.start();
            strikes.start();
            airDefence.start();
            diplomacy.start();
            plugin.allegiance().strategicAlliance(diplomacy::allied);
            startSimulation();
            plugin.getServer().getScheduler().runTask(plugin, this::materializeLoadedChunks);
            plugin.getLogger().info("Strategic States: завантажено " + states.size() + " держав");
        } catch (SQLException ex) {
            available = false;
            plugin.getLogger().log(Level.SEVERE, "Strategic States вимкнено: states.db недоступна", ex);
        }
    }

    public void reload() {
        config = StrategicStateConfig.load(plugin);
        impactConfig = StrategicImpactConfig.load(plugin.getConfig());
        construction.reload();
        warehouses.reload();
        physicalTasks.reload();
        sectors.reload();
        wars.reload();
        capture.reload();
        armies.reload();
        armyCombat.reload();
        localDefence.reload();
        production.reload();
        strikes.reload();
        airDefence.reload();
        diplomacy.reload();
        territories.reload();
        if (!available) {
            if (config.enabled()) {
                start();
            }
            return;
        }
        if (!config.enabled()) {
            if (simulationTask != null) {
                simulationTask.cancel();
                simulationTask = null;
            }
            construction.shutdown();
            physicalTasks.shutdown();
            capture.shutdown();
            armies.shutdown();
            armyCombat.shutdown();
            localDefence.shutdown();
            production.shutdown();
            strikes.shutdown();
            airDefence.shutdown();
            diplomacy.shutdown();
            citizens.virtualizeAll();
            return;
        }
        construction.start();
        physicalTasks.start();
        capture.start();
        armies.start();
        armyCombat.start();
        localDefence.start();
        wars.start();
        production.start();
        strikes.start();
        airDefence.start();
        diplomacy.start();
        sectors.synchronize(territories.all());
        reconcileTerritoryOwners();
        startSimulation();
    }

    public void shutdown() {
        if (simulationTask != null) {
            simulationTask.cancel();
            simulationTask = null;
        }
        if (!available) {
            return;
        }
        physicalTasks.shutdown();
        capture.shutdown();
        armies.shutdown();
        armyCombat.shutdown();
        localDefence.shutdown();
        wars.shutdown();
        production.shutdown();
        strikes.shutdown();
        airDefence.shutdown();
        diplomacy.shutdown();
        citizens.virtualizeAll();
        for (StrategicState state : states.values()) {
            repository.saveStateAsync(state);
        }
        for (StateEconomy economy : economies.values()) {
            repository.saveEconomyAsync(economy);
        }
        construction.saveAll();
        construction.shutdown();
        repository.close();
        available = false;
    }

    public CreationResult createState(Block core) {
        if (!available || !config.enabled()) {
            return new CreationResult(false, "Strategic States вимкнено", null);
        }
        if (!territories.writeAvailable()) {
            return new CreationResult(false, "TerritoryMap write API недоступний", null);
        }
        if (activeStates() >= config.maxStates()) {
            return new CreationResult(false, "Досягнуто ліміт держав: " + config.maxStates(), null);
        }
        if (!config.worldAllowed(core.getWorld().getName())) {
            return new CreationResult(false, "У цьому світі створення держав заборонено", null);
        }
        if (nearestCoreDistance(core.getLocation()) < config.minimumCoreDistance()) {
            return new CreationResult(false, "Інший State Core розташований надто близько", null);
        }
        UUID stateId = UUID.randomUUID();
        StateIdentityFactory.Identity identity;
        try {
            identity = identities.create(stateId, config.names(), active());
        } catch (IllegalStateException ex) {
            return new CreationResult(false, ex.getMessage(), null);
        }
        UUID territoryId;
        try {
            territoryId = territories.createStateTerritory(stateId, identity.name(), core.getWorld().getName(),
                    core.getX() + 0.5D, core.getZ() + 0.5D, config.startingTerritoryRadius(), identity.color().hex());
        } catch (TerritoryHookException ex) {
            return new CreationResult(false, "Не вдалося створити територію: " + ex.getMessage(), null);
        }
        long now = System.currentTimeMillis();
        StatePosition corePosition = new StatePosition(core.getWorld().getName(), core.getX(), core.getY(), core.getZ());
        StatePosition capital = new StatePosition(core.getWorld().getName(), core.getX() + 0.5D, core.getY() + 1.0D, core.getZ() + 0.5D);
        StrategicState state = new StrategicState(stateId, identity.name(), identity.shortName(), identity.color(), capital,
                corePosition, now, identity.personality(), territoryId, StateStatus.ACTIVE, 1, now);
        int population = ThreadLocalRandom.current().nextInt(config.startingPopulationMin(), config.startingPopulationMax() + 1);
        List<StateCitizen> created = citizens.createInitial(state, population);
        StateEconomy economy = StateEconomy.initial(state.id(), Math.max(10, population + 4), now);
        try {
            repository.insertState(state, created, economy);
        } catch (SQLException ex) {
            territories.deleteStateTerritory(territoryId, stateId);
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти нову державу", ex);
            return new CreationResult(false, "Помилка запису states.db", null);
        }
        states.put(state.id(), state);
        economies.put(state.id(), economy);
        citizens.addAll(created);
        citizens.materializeState(state);
        sectors.synchronize(territories.all());
        return new CreationResult(true, "Створено державу " + state.name() + " з населенням " + population, state);
    }

    public void materializeChunk(Chunk chunk) {
        if (available) {
            citizens.materializeChunk(chunk, states::get);
            construction.materializeChunk(chunk);
        }
    }

    public void virtualizeChunk(Chunk chunk) {
        if (available) {
            citizens.virtualizeChunk(chunk);
        }
    }

    public boolean citizenDied(org.bukkit.entity.Entity entity) {
        return available && citizens.handleDeath(entity);
    }

    public void citizenAttacked(org.bukkit.entity.Entity victim, org.bukkit.entity.Player attacker) {
        if (available) {
            localDefence.attacked(victim, attacker);
        }
    }

    public boolean citizenTargetAllowed(org.bukkit.entity.Entity attacker, org.bukkit.entity.LivingEntity target) {
        return available && (armyCombat.authorized(attacker, target) || localDefence.authorized(attacker, target));
    }

    private boolean alliedPlayer(UUID stateId, UUID playerId) {
        return diplomacy != null && diplomacy.alliedPlayer(stateId, playerId);
    }

    private void intrusion(UUID stateId, UUID playerId) {
        if (!attacksEnabled || protectedOwners.contains(playerId)) {
            return;
        }
        StrategicState state = states.get(stateId);
        TerritorySnapshot target = territories.all().stream().filter(territory -> territory.ownerKind() == TerritoryOwnerKind.PLAYER && playerId.equals(territory.ownerId())).findFirst().orElse(null);
        if (state != null && target != null) {
            sectors.synchronize(List.of(target));
            wars.retaliate(state, target);
        }
    }

    public void citizenCombatHit(org.bukkit.entity.Entity attacker, org.bukkit.entity.LivingEntity target, double damage) {
        StateCitizen citizen = citizens.citizenByEntity(attacker);
        if (citizen != null && citizenTargetAllowed(attacker, target)) {
            citizens.recordCombatHit(citizen, damage);
        }
    }

    public StrategicState coreAt(Block block) {
        for (StrategicState state : states.values()) {
            StatePosition core = state.core();
            if (core.world().equals(block.getWorld().getName()) && (int) core.x() == block.getX()
                    && (int) core.y() == block.getY() && (int) core.z() == block.getZ()) {
                return state;
            }
        }
        return null;
    }

    public void coreDestroyed(StrategicState state) {
        if (state.status() == StateStatus.DESTROYED || state.status() == StateStatus.PAUSED) {
            return;
        }
        state.status(StateStatus.DEGRADED);
        repository.saveStateAsync(state);
    }

    public Collection<StrategicState> all() {
        return List.copyOf(states.values());
    }

    public Collection<StrategicState> active() {
        List<StrategicState> result = new ArrayList<>();
        for (StrategicState state : states.values()) {
            if (state.status() != StateStatus.DESTROYED && state.status() != StateStatus.PAUSED) {
                result.add(state);
            }
        }
        return List.copyOf(result);
    }

    public void save(StrategicState state) {
        if (available && state != null) {
            repository.saveStateAsync(state);
        }
    }

    public boolean pause(StrategicState state) {
        if (!available || state == null || state.status() == StateStatus.DESTROYED || state.status() == StateStatus.PAUSED) {
            return false;
        }
        for (StateWar war : wars.active()) {
            if (war.attackerStateId().equals(state.id()) || war.target().id().equals(state.id())) {
                wars.finish(war, WarStatus.ENDED, "STATE_PAUSED");
            }
        }
        state.status(StateStatus.PAUSED);
        citizens.virtualizeState(state.id());
        construction.pauseState(state.id());
        repository.saveStateAsync(state);
        return true;
    }

    public boolean resume(StrategicState state) {
        if (!available || state == null || state.status() != StateStatus.PAUSED) {
            return false;
        }
        state.status(StateStatus.ACTIVE);
        long now = System.currentTimeMillis();
        state.lastSimulation(now);
        StateEconomy economy = economies.get(state.id());
        if (economy != null) {
            economy.lastEconomyTick(now);
            economy.lastPopulationTick(now);
            repository.saveEconomyAsync(economy);
        }
        repository.saveStateAsync(state);
        citizens.materializeState(state);
        return true;
    }

    public boolean destroy(StrategicState state) {
        if (!available || state == null || state.status() == StateStatus.DESTROYED) {
            return false;
        }
        citizens.destroyState(state.id());
        construction.destroyState(state.id());
        for (StateWar war : wars.active()) {
            if (war.attackerStateId().equals(state.id()) || war.target().id().equals(state.id())) {
                wars.finish(war, WarStatus.ENDED, "STATE_DESTROYED");
            }
        }
        state.status(StateStatus.DESTROYED);
        repository.saveStateAsync(state);
        territories.deleteStateTerritory(state.territoryId(), state.id());
        Location core = state.core().location();
        if (core != null && core.getWorld().isChunkLoaded(core.getBlockX() >> 4, core.getBlockZ() >> 4)
                && core.getBlock().getType() == org.bukkit.Material.BEDROCK) {
            core.getBlock().setType(org.bukkit.Material.AIR, false);
        }
        return true;
    }

    public StrategicState byId(UUID id) {
        return states.get(id);
    }

    public StrategicState find(String query) {
        if (query == null) {
            return null;
        }
        try {
            StrategicState byId = states.get(UUID.fromString(query));
            if (byId != null) {
                return byId;
            }
        } catch (IllegalArgumentException ignored) {
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        for (StrategicState state : states.values()) {
            if (state.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || state.shortName().toLowerCase(Locale.ROOT).equals(normalized)) {
                return state;
            }
        }
        return null;
    }

    public int population(UUID stateId) {
        return citizens.livingPopulation(stateId);
    }

    public int physicalPopulation(UUID stateId) {
        return citizens.physicalPopulation(stateId);
    }

    public StateCitizen citizen(org.bukkit.entity.Entity entity) {
        return citizens.citizenByEntity(entity);
    }

    public Map<CitizenProfession, Integer> professions(UUID stateId) {
        return Map.copyOf(citizens.professions(stateId));
    }

    public StateEconomy economy(UUID stateId) {
        return economies.get(stateId);
    }

    public Collection<StateThreat> threats(UUID stateId) {
        return List.copyOf(threats.getOrDefault(stateId, Map.of()).values());
    }

    public Collection<StateRelation> relations(UUID stateId) {
        return diplomacy.relations(stateId);
    }

    public Collection<StateBuilding> buildings(UUID stateId) {
        return construction.all(stateId);
    }

    public Collection<CaptureSector> sectors(UUID territoryId) {
        return sectors.forTerritory(territoryId);
    }

    public int controlledSectors(UUID stateId) {
        return sectors.controlledBy(stateId);
    }

    public Collection<StateWar> wars() {
        return wars.all();
    }

    public double warReadiness(StrategicState state) {
        return wars.readiness(state);
    }

    public CaptureSector warFrontline(StateWar war) {
        StrategicState attacker = war == null ? null : states.get(war.attackerStateId());
        return attacker == null ? null : sectors.frontline(war.territoryId(), attacker.id(), attacker.core());
    }

    public StateArmy army(UUID stateId) {
        return armies.army(stateId);
    }

    public StateArsenal arsenal(UUID stateId) {
        return production.arsenal(stateId);
    }

    public boolean addArsenal(StrategicState state, ArsenalItem item, int amount) {
        return state != null && production.add(state.id(), item, amount);
    }

    public boolean mobilizeSoldier(StrategicState state) {
        StateEconomy economy = state == null ? null : economies.get(state.id());
        if (economy == null) {
            return false;
        }
        warehouses.pull(state.id(), economy);
        double food = plugin.getConfig().getDouble("strategic-states.war.mobilization-food-per-soldier", 20.0D);
        double iron = plugin.getConfig().getDouble("strategic-states.war.mobilization-iron-per-soldier", 2.0D);
        if (economy.amount(ResourceType.FOOD) < food || economy.amount(ResourceType.IRON) < iron || !production.available(state.id(), ArsenalItem.SMALL_ARMS)) {
            return false;
        }
        if (!production.consume(state.id(), ArsenalItem.SMALL_ARMS)) {
            return false;
        }
        economy.consume(ResourceType.FOOD, food);
        economy.consume(ResourceType.IRON, iron);
        citizens.recruit(state, CitizenProfession.SOLDIER);
        warehouses.push(state.id(), economy);
        repository.saveEconomyAsync(economy);
        return true;
    }

    public WarManager.StartResult startWar(StrategicState attacker, String territoryQuery) {
        TerritorySnapshot territory = findTerritory(territoryQuery);
        if (territory != null) {
            sectors.synchronize(List.of(territory));
            if (sectors.forTerritory(territory.id()).isEmpty()) {
                return new WarManager.StartResult(false, "Геометрія території не створила жодного capture sector", null);
            }
        }
        return wars.start(attacker, territory);
    }

    public boolean stopWar(UUID warId) {
        StateWar war = wars.all().stream().filter(candidate -> candidate.id().equals(warId)).findFirst().orElse(null);
        return wars.finish(war, WarStatus.ENDED, "ADMIN_STOP");
    }

    public ImpactResult recordImpact(Location location, CombatPrincipal attacker, StrategicWeaponType weapon, double power) {
        if (!available || location == null || location.getWorld() == null || attacker == null || weapon == null || !Double.isFinite(power) || power <= 0.0D) {
            return null;
        }
        TerritorySnapshot territory = territories.at(location.getWorld().getName(), location.getX(), location.getZ());
        if (territory == null || territory.ownerKind() != TerritoryOwnerKind.STRATEGIC_STATE || territory.ownerId() == null) {
            return null;
        }
        StrategicState state = states.get(territory.ownerId());
        if (state == null || state.status() == StateStatus.DESTROYED
                || (attacker.type() == CombatPrincipalType.STRATEGIC_STATE && attacker.id().equals(state.id()))) {
            return null;
        }
        long now = System.currentTimeMillis();
        Map<CombatPrincipal, StateThreat> stateThreats = threats.computeIfAbsent(state.id(), ignored -> new LinkedHashMap<>());
        StateThreat threat = stateThreats.computeIfAbsent(attacker, ignored -> new StateThreat(state.id(), attacker, 0.0D, 0L, 0, 0));
        threat.record(weapon, power, now, impactConfig.threatHalfLifeHours());
        boolean groundImpact = groundImpact(location);
        if (groundImpact) {
            applyStrategicDamage(state, weapon, power);
            construction.damageAt(state.id(), location, weapon, power);
        }
        repository.saveImpactAsync(threat, weapon, power, groundImpact, now);
        boolean retaliationEligible = threat.threat() >= impactConfig.retaliationThreat() && attacksEnabled && !protectedAttacker(attacker);
        return new ImpactResult(state, groundImpact, threat.threat(), retaliationEligible);
    }

    public boolean addResource(StrategicState state, ResourceType type, double amount) {
        StateEconomy economy = state == null ? null : economies.get(state.id());
        if (!available || economy == null || !Double.isFinite(amount) || amount == 0.0D) {
            return false;
        }
        warehouses.pull(state.id(), economy);
        economy.add(type, amount);
        warehouses.push(state.id(), economy);
        repository.saveEconomyAsync(economy);
        return true;
    }

    public StateCoreItem coreItem() {
        return coreItem;
    }

    public void synchronizeWarehouse(Location location) {
        UUID stateId = warehouses.stateAt(location);
        StateEconomy economy = stateId == null ? null : economies.get(stateId);
        if (economy != null && warehouses.pull(stateId, economy)) {
            repository.saveEconomyAsync(economy);
        }
        UUID armamentStateId = armaments.stateAt(location);
        StateArsenal arsenal = armamentStateId == null ? null : production.arsenal(armamentStateId);
        if (arsenal != null) {
            armaments.synchronize(armamentStateId, arsenal);
            for (ArsenalItem item : ArsenalItem.values()) {
                repository.saveArsenalAsync(arsenal, item);
            }
        }
    }

    public TerritoryMapHook territories() {
        return territories;
    }

    public StrategicStateConfig config() {
        return config;
    }

    public boolean available() {
        return available;
    }

    public boolean attacksEnabled() {
        return attacksEnabled;
    }

    public boolean attacksEnabled(boolean enabled) {
        if (!available) {
            return false;
        }
        try {
            repository.setBoolean("attacksEnabled", enabled);
            attacksEnabled = enabled;
            if (!enabled) {
                wars.ceasefireAll("ATTACKS_DISABLED");
                cancelAutonomousWeapons();
            }
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти attacksEnabled", ex);
            return false;
        }
    }

    public boolean isProtected(UUID owner) {
        return protectedOwners.contains(owner);
    }

    public Set<UUID> protectedOwners() {
        return Set.copyOf(protectedOwners);
    }

    public boolean protect(UUID owner) {
        if (!available || protectedOwners.contains(owner)) {
            return false;
        }
        try {
            repository.protectOwner(owner);
            protectedOwners.add(owner);
            wars.ceasefireTarget(owner, "TARGET_PROTECTED");
            if (plugin.drones() != null) {
                plugin.drones().cancelAutonomous();
            }
            if (plugin.ballistic() != null) {
                plugin.ballistic().cancelAutonomous();
            }
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не вдалося захистити TerritoryMap owner", ex);
            return false;
        }
    }

    private void cancelAutonomousWeapons() {
        if (plugin.getConfig().getBoolean("strategic-states.attacks.on-disable.cancel-active-drones", true) && plugin.drones() != null) {
            plugin.drones().cancelAutonomous();
        }
        if (plugin.getConfig().getBoolean("strategic-states.attacks.on-disable.destroy-flying-missiles", false) && plugin.ballistic() != null) {
            plugin.ballistic().cancelAutonomous();
        }
    }

    public boolean unprotect(UUID owner) {
        if (!available || !protectedOwners.contains(owner)) {
            return false;
        }
        try {
            repository.unprotectOwner(owner);
            protectedOwners.remove(owner);
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зняти захист TerritoryMap owner", ex);
            return false;
        }
    }

    public UUID resolvePlayerOwner(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            UUID direct = UUID.fromString(query);
            for (TerritorySnapshot territory : territories.all()) {
                if (direct.equals(territory.ownerId()) && territory.ownerKind() == TerritoryOwnerKind.PLAYER) {
                    return direct;
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        for (TerritorySnapshot territory : territories.all()) {
            if (territory.ownerKind() != TerritoryOwnerKind.PLAYER || territory.ownerId() == null) {
                continue;
            }
            if (territory.name().equalsIgnoreCase(query)
                    || territory.ownerName() != null && territory.ownerName().equalsIgnoreCase(query)) {
                return territory.ownerId();
            }
        }
        return null;
    }

    public String ownerName(UUID owner) {
        for (TerritorySnapshot territory : territories.all()) {
            if (owner.equals(territory.ownerId())) {
                return territory.ownerName() == null ? owner.toString() : territory.ownerName();
            }
        }
        return owner.toString();
    }

    private void startSimulation() {
        if (simulationTask != null) {
            simulationTask.cancel();
        }
        long period = config.stateTickSeconds() * 20L;
        simulationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::simulate, period, period);
    }

    private void simulate() {
        long now = System.currentTimeMillis();
        if (now - lastSectorSynchronization >= 60_000L) {
            sectors.synchronize(territories.all());
            reconcileTerritoryOwners();
            lastSectorSynchronization = now;
        }
        for (StrategicState state : states.values()) {
            if (state.status() == StateStatus.DESTROYED || state.status() == StateStatus.PAUSED) {
                continue;
            }
            StateEconomy economy = economies.get(state.id());
            if (economy == null) {
                economy = StateEconomy.initial(state.id(), initialHousingCapacity(state), now);
                economies.put(state.id(), economy);
            }
            citizens.rebalanceProfessions(state);
            warehouses.pull(state.id(), economy);
            simulation.tick(state, economy, citizens, config, now);
            advanceDevelopment(state, economy);
            ensureTerritorySize(state);
            construction.tick(state, economy, now);
            warehouses.push(state.id(), economy);
            repository.saveStateAsync(state);
            repository.saveEconomyAsync(economy);
        }
    }

    private void populationChanged(UUID stateId) {
        StrategicState state = states.get(stateId);
        if (state == null || citizens.livingPopulation(stateId) > 0) {
            return;
        }
        state.status(StateStatus.DESTROYED);
        repository.saveStateAsync(state);
    }

    private int activeStates() {
        int count = 0;
        for (StrategicState state : states.values()) {
            if (state.status() != StateStatus.DESTROYED) {
                count++;
            }
        }
        return count;
    }

    private int initialHousingCapacity(StrategicState state) {
        return Math.max(10, citizens.livingPopulation(state.id()) + 4 + (state.developmentLevel() - 1) * 5);
    }

    private void advanceDevelopment(StrategicState state, StateEconomy economy) {
        if (!plugin.getConfig().getBoolean("strategic-states.development.auto-levels", true)) {
            return;
        }
        int population = citizens.livingPopulation(state.id());
        int target = 1;
        if (population >= plugin.getConfig().getInt("strategic-states.development.level-2-population", 8)) {
            target = 2;
        }
        if (population >= plugin.getConfig().getInt("strategic-states.development.level-3-population", 15)) {
            target = 3;
        }
        if (population >= plugin.getConfig().getInt("strategic-states.development.level-4-population", 24)
                && economy.stability() >= plugin.getConfig().getDouble("strategic-states.development.level-4-stability", 65.0D)) {
            target = 4;
        }
        if (population >= plugin.getConfig().getInt("strategic-states.development.level-5-population", 35)
                && economy.stability() >= plugin.getConfig().getDouble("strategic-states.development.level-5-stability", 75.0D)) {
            target = 5;
        }
        if (target > state.developmentLevel()) {
            state.developmentLevel(state.developmentLevel() + 1);
            plugin.getLogger().info(state.name() + " досягла рівня " + state.developmentLevel());
        }
    }

    private void ensureTerritorySize(StrategicState state) {
        double target = plugin.getConfig().getDouble("strategic-states.creation.level-radii.level-" + state.developmentLevel(), defaultRadius(state.developmentLevel()));
        resizeTerritory(state, Math.max(32.0D, target), false);
    }

    private void migrateTerritoryRadiusConfig() {
        double[] legacy = {96.0D, 128.0D, 168.0D, 216.0D, 288.0D};
        for (int level = 1; level <= 5; level++) {
            double value = plugin.getConfig().getDouble("strategic-states.creation.level-radii.level-" + level, legacy[level - 1]);
            if (Math.abs(value - legacy[level - 1]) > 0.01D) {
                return;
            }
        }
        plugin.getConfig().set("strategic-states.creation.starting-territory-radius", 48.0D);
        for (int level = 1; level <= 5; level++) {
            plugin.getConfig().set("strategic-states.creation.level-radii.level-" + level, defaultRadius(level));
        }
        plugin.getConfig().set("strategic-states.creation.maximum-emergency-radius", 160.0D);
        plugin.getConfig().set("strategic-states.creation.emergency-expansion-step", 16.0D);
        plugin.saveConfig();
        plugin.getLogger().info("Профіль радіусів територій оновлено до компактного 3.0.1");
    }

    private void compactOversizedTerritories() throws SQLException {
        if (repository.settingBoolean("territoryRadius301Compacted", false)) {
            return;
        }
        boolean complete = true;
        for (StrategicState state : active()) {
            double radius = Math.max(32.0D, plugin.getConfig().getDouble("strategic-states.creation.level-radii.level-" + state.developmentLevel(), defaultRadius(state.developmentLevel())));
            if (territoryRadius(state) <= radius + 1.0D) {
                continue;
            }
            if (!compactableCircle(state)) {
                plugin.getLogger().warning("Автоматичне зменшення " + state.name() + " пропущено: геометрія містить не лише базове коло");
                continue;
            }
            try {
                if (!territories.compactStateTerritory(state.territoryId(), state.id(), state.core().x() + 0.5D, state.core().z() + 0.5D, radius)) {
                    complete = false;
                }
            } catch (TerritoryHookException ex) {
                complete = false;
                plugin.getLogger().warning("Не вдалося зменшити територію " + state.name() + ": " + ex.getMessage());
            }
        }
        if (complete) {
            repository.setBoolean("territoryRadius301Compacted", true);
            sectors.synchronize(territories.all());
            plugin.getLogger().info("Надмірні території Strategic States приведено до радіусів 3.0.1");
        }
    }

    private boolean compactableCircle(StrategicState state) {
        TerritorySnapshot territory = territories.byId(state.territoryId());
        if (territory == null || territory.parts().size() != 1 || !territory.parts().getFirst().holes().isEmpty() || territory.parts().getFirst().outer().size() < 24) {
            return false;
        }
        double centerX = state.core().x() + 0.5D;
        double centerZ = state.core().z() + 0.5D;
        double minimum = Double.MAX_VALUE;
        double maximum = 0.0D;
        for (TerritoryPointSnapshot point : territory.parts().getFirst().outer()) {
            double distance = Math.hypot(point.x() - centerX, point.z() - centerZ);
            minimum = Math.min(minimum, distance);
            maximum = Math.max(maximum, distance);
        }
        return maximum > 0.0D && maximum - minimum <= maximum * 0.08D;
    }

    private void expandForConstruction(StrategicState state) {
        long now = System.currentTimeMillis();
        if (now - lastTerritoryExpansion.getOrDefault(state.id(), 0L) < 30_000L) {
            return;
        }
        double maximum = Math.max(112.0D, plugin.getConfig().getDouble("strategic-states.creation.maximum-emergency-radius", 160.0D));
        double step = Math.max(8.0D, plugin.getConfig().getDouble("strategic-states.creation.emergency-expansion-step", 16.0D));
        double current = territoryRadius(state);
        if (current >= maximum - 1.0D) {
            return;
        }
        lastTerritoryExpansion.put(state.id(), now);
        resizeTerritory(state, Math.min(maximum, Math.max(defaultRadius(state.developmentLevel()), current + step)), true);
    }

    private void resizeTerritory(StrategicState state, double radius, boolean force) {
        double current = territoryRadius(state);
        if (!force && current >= radius - 1.0D) {
            return;
        }
        try {
            if (territories.resizeStateTerritory(state.territoryId(), state.id(), state.core().x() + 0.5D, state.core().z() + 0.5D, radius)) {
                sectors.synchronize(territories.all());
                lastSectorSynchronization = System.currentTimeMillis();
                plugin.getLogger().info(state.name() + " розширила круглу територію до радіуса " + Math.round(radius));
            }
        } catch (TerritoryHookException ex) {
            if (force) {
                plugin.getLogger().warning("Не вдалося розширити територію " + state.name() + ": " + ex.getMessage());
            }
        }
    }

    private double territoryRadius(StrategicState state) {
        TerritorySnapshot territory = territories.byId(state.territoryId());
        if (territory == null) {
            return 0.0D;
        }
        double centerX = state.core().x() + 0.5D;
        double centerZ = state.core().z() + 0.5D;
        double maximum = 0.0D;
        for (TerritoryPartSnapshot part : territory.parts()) {
            for (TerritoryPointSnapshot point : part.outer()) {
                double x = point.x() - centerX;
                double z = point.z() - centerZ;
                maximum = Math.max(maximum, Math.sqrt(x * x + z * z));
            }
        }
        return maximum;
    }

    private double defaultRadius(int level) {
        return switch (level) {
            case 1 -> 48.0D;
            case 2 -> 64.0D;
            case 3 -> 80.0D;
            case 4 -> 96.0D;
            default -> 112.0D;
        };
    }

    private boolean groundImpact(Location location) {
        int surface = location.getWorld().getHighestBlockYAt(location);
        return location.getY() <= surface + impactConfig.maximumGroundDistance();
    }

    private void applyStrategicDamage(StrategicState state, StrategicWeaponType weapon, double power) {
        StateEconomy economy = economies.get(state.id());
        if (economy == null) {
            return;
        }
        warehouses.pull(state.id(), economy);
        double stabilityPerPower = weapon == StrategicWeaponType.BALLISTIC ? impactConfig.ballisticStabilityPerPower() : impactConfig.droneStabilityPerPower();
        double resourcePerPower = weapon == StrategicWeaponType.BALLISTIC ? impactConfig.ballisticResourceLossPerPower() : impactConfig.droneResourceLossPerPower();
        economy.stability(economy.stability() - power * stabilityPerPower);
        double loss = Math.min(impactConfig.maximumResourceLoss(), power * resourcePerPower);
        for (ResourceType resource : ResourceType.values()) {
            economy.add(resource, -economy.amount(resource) * loss);
        }
        warehouses.push(state.id(), economy);
        repository.saveEconomyAsync(economy);
    }

    private boolean protectedAttacker(CombatPrincipal attacker) {
        return attacker.type() == CombatPrincipalType.PLAYER && protectedOwners.contains(attacker.id());
    }

    private double nearestCoreDistance(Location location) {
        double nearest = Double.MAX_VALUE;
        for (StrategicState state : states.values()) {
            Location other = state.core().location();
            if (other == null || other.getWorld() != location.getWorld()) {
                continue;
            }
            nearest = Math.min(nearest, other.distance(location));
        }
        return nearest;
    }

    private void materializeLoadedChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                materializeChunk(chunk);
            }
        }
    }

    private void seedProtectedOwners() throws SQLException {
        for (String raw : plugin.getConfig().getStringList("strategic-states.protected-owner-uuids")) {
            try {
                UUID owner = UUID.fromString(raw);
                repository.protectOwner(owner);
                protectedOwners.add(owner);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private TerritorySnapshot findTerritory(String query) {
        if (query == null) {
            return null;
        }
        for (TerritorySnapshot territory : territories.all()) {
            if (territory.name().equalsIgnoreCase(query) || territory.id().toString().equalsIgnoreCase(query)) {
                return territory;
            }
        }
        return null;
    }

    private void reconcileTerritoryOwners() {
        if (!territories.writeAvailable()) {
            return;
        }
        for (TerritorySnapshot territory : territories.all()) {
            UUID controller = sectors.completedController(territory.id());
            StrategicState state = controller == null ? null : states.get(controller);
            if (state == null || controller.equals(territory.ownerId())) {
                continue;
            }
            try {
                if (!territories.transferOwner(territory.id(), state.id(), state.name(), TerritoryOwnerKind.STRATEGIC_STATE)) {
                    plugin.getLogger().warning("TerritoryMap відхилив reconciliation owner для " + territory.id());
                }
            } catch (TerritoryHookException ex) {
                plugin.getLogger().warning("Не вдалося reconciliate TerritoryMap owner " + territory.id() + ": " + ex.getMessage());
            }
        }
    }
}
