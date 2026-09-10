package ua.bobster.defence.strategicstates.arsenal;

import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.construction.BuildingType;
import ua.bobster.defence.strategicstates.construction.ConstructionManager;
import ua.bobster.defence.strategicstates.economy.ResourceType;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.economy.WarehouseManager;
import ua.bobster.defence.strategicstates.model.StateStatus;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenProfession;

import java.sql.SQLException;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public class MilitaryProductionManager {

    private record Recipe(int level, BuildingType building, CitizenProfession operator, double seconds, int cap, Map<ResourceType, Double> cost) {
    }

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final ConstructionManager construction;
    private final WarehouseManager warehouses;
    private final ArmamentStorageManager armaments;
    private final CitizenManager citizens;
    private final Supplier<Collection<StrategicState>> states;
    private final Function<UUID, StateEconomy> economies;
    private final Map<UUID, StateArsenal> arsenals = new LinkedHashMap<>();
    private final Map<ArsenalItem, Recipe> recipes = new EnumMap<>(ArsenalItem.class);

    private BukkitTask task;
    private long intervalTicks;

    public MilitaryProductionManager(BobsterDefence plugin, StateRepository repository, ConstructionManager construction, WarehouseManager warehouses, ArmamentStorageManager armaments, CitizenManager citizens, Supplier<Collection<StrategicState>> states, Function<UUID, StateEconomy> economies) {
        this.plugin = plugin;
        this.repository = repository;
        this.construction = construction;
        this.warehouses = warehouses;
        this.armaments = armaments;
        this.citizens = citizens;
        this.states = states;
        this.economies = economies;
        reload();
    }

    public void reload() {
        intervalTicks = Math.max(20L, plugin.getConfig().getLong("strategic-states.production.tick-interval-ticks", 100L));
        recipes.clear();
        recipes.put(ArsenalItem.SMALL_ARMS, new Recipe(1, BuildingType.WORKSHOP, CitizenProfession.ENGINEER, seconds("small-arms", 15.0D), cap("small-arms", 200), Map.of(ResourceType.IRON, 4.0D, ResourceType.WOOD, 2.0D)));
        recipes.put(ArsenalItem.AIR_DEFENCE_MISSILE, new Recipe(4, BuildingType.AIR_DEFENCE_SITE, CitizenProfession.AIR_DEFENCE_OPERATOR, seconds("air-defence-missile", 180.0D), cap("air-defence-missile", 12), Map.of(ResourceType.IRON, 5.0D, ResourceType.GUNPOWDER, 3.0D, ResourceType.REDSTONE, 2.0D, ResourceType.FUEL, 1.0D, ResourceType.ADVANCED_COMPONENTS, 1.0D)));
        recipes.put(ArsenalItem.STRIKE_DRONE, new Recipe(1, BuildingType.DRONE_FACTORY, CitizenProfession.DRONE_OPERATOR, seconds("strike-drone", 30.0D), cap("strike-drone", 128), Map.of(ResourceType.IRON, 4.0D, ResourceType.GUNPOWDER, 4.0D, ResourceType.REDSTONE, 3.0D, ResourceType.FUEL, 2.0D, ResourceType.ADVANCED_COMPONENTS, 2.0D)));
        recipes.put(ArsenalItem.BALLISTIC_MISSILE, new Recipe(4, BuildingType.MISSILE_FACTORY, CitizenProfession.MISSILE_OPERATOR, seconds("ballistic-missile", 180.0D), cap("ballistic-missile", 32), Map.of(ResourceType.IRON, 12.0D, ResourceType.GUNPOWDER, 10.0D, ResourceType.REDSTONE, 6.0D, ResourceType.FUEL, 8.0D, ResourceType.ADVANCED_COMPONENTS, 4.0D)));
    }

    public void load(Map<UUID, StateArsenal> loaded, long now) {
        arsenals.clear();
        arsenals.putAll(loaded);
        for (StateArsenal arsenal : arsenals.values()) {
            for (ArsenalItem item : ArsenalItem.values()) {
                arsenal.lastTick(item, now);
            }
        }
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(), intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public StateArsenal arsenal(UUID stateId) {
        return arsenals.get(stateId);
    }

    public synchronized boolean available(UUID stateId, ArsenalItem item) {
        StateArsenal arsenal = arsenals.get(stateId);
        if (arsenal == null || item == null) {
            return false;
        }
        armaments.synchronize(stateId, arsenal);
        return arsenal.amount(item) > 0;
    }

    public boolean add(UUID stateId, ArsenalItem item, int amount) {
        if (item == null || amount == 0) {
            return false;
        }
        StateArsenal arsenal = arsenals.computeIfAbsent(stateId, StateArsenal::new);
        armaments.synchronize(stateId, arsenal);
        arsenal.amount(item, arsenal.amount(item) + amount);
        arsenal.lastTick(item, System.currentTimeMillis());
        repository.saveArsenalAsync(arsenal, item);
        armaments.store(stateId, arsenal, item);
        return true;
    }

    public synchronized boolean consume(UUID stateId, ArsenalItem item) {
        StateArsenal arsenal = arsenals.get(stateId);
        if (arsenal == null || item == null || !armaments.consume(stateId, arsenal, item)) {
            return false;
        }
        arsenal.lastTick(item, System.currentTimeMillis());
        repository.saveArsenalAsync(arsenal, item);
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (StrategicState state : states.get()) {
            if (state.status() == StateStatus.DESTROYED) {
                continue;
            }
            StateEconomy economy = economies.apply(state.id());
            if (economy == null) {
                continue;
            }
            StateArsenal arsenal = arsenals.computeIfAbsent(state.id(), StateArsenal::new);
            warehouses.pull(state.id(), economy);
            armaments.synchronize(state.id(), arsenal);
            for (Map.Entry<ArsenalItem, Recipe> entry : recipes.entrySet()) {
                produce(state, economy, arsenal, entry.getKey(), entry.getValue(), now);
            }
            warehouses.push(state.id(), economy);
        }
    }

    private void produce(StrategicState state, StateEconomy economy, StateArsenal arsenal, ArsenalItem item, Recipe recipe, long now) {
        long previous = arsenal.lastTick(item);
        arsenal.lastTick(item, now);
        if (previous == 0L || state.developmentLevel() < recipe.level() || !construction.operational(state.id(), recipe.building())
                || !citizens.hasLivingProfession(state.id(), recipe.operator())) {
            if (arsenal.progress(item) > 0.0D) {
                repository.saveArsenalAsync(arsenal, item);
            }
            return;
        }
        if (arsenal.progress(item) <= 0.0D) {
            if (arsenal.amount(item) >= recipe.cap() || !warehouses.withdraw(state.id(), economy, recipe.cost())) {
                return;
            }
            arsenal.progress(item, 0.000001D);
            try {
                repository.saveProductionStart(arsenal, item, economy);
            } catch (SQLException ex) {
                warehouses.deposit(state.id(), economy, recipe.cost());
                arsenal.progress(item, 0.0D);
                plugin.getLogger().warning("Не вдалося почати виробництво " + item + " для " + state.name() + ": " + ex.getMessage());
            }
            return;
        }
        double focus = 0.75D + state.personality().technologyFocus() / 200.0D;
        double elapsedSeconds = Math.max(0L, now - previous) / 1000.0D;
        arsenal.progress(item, arsenal.progress(item) + elapsedSeconds * focus / recipe.seconds());
        if (arsenal.progress(item) >= 1.0D) {
            arsenal.amount(item, arsenal.amount(item) + 1);
            arsenal.progress(item, 0.0D);
            armaments.store(state.id(), arsenal, item);
        }
        repository.saveArsenalAsync(arsenal, item);
    }

    private double seconds(String key, double fallback) {
        return Math.max(1.0D, plugin.getConfig().getDouble("strategic-states.production.recipes." + key + ".seconds", fallback));
    }

    private int cap(String key, int fallback) {
        return Math.max(1, plugin.getConfig().getInt("strategic-states.production.recipes." + key + ".cap", fallback));
    }
}
