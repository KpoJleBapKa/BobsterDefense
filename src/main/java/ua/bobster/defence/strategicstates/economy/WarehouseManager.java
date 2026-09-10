package ua.bobster.defence.strategicstates.economy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.construction.BuildingType;
import ua.bobster.defence.strategicstates.construction.ConstructionManager;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.persistence.StateRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WarehouseManager {

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final ConstructionManager construction;
    private final Map<ResourceType, Material> materials = new EnumMap<>(ResourceType.class);
    private final Map<Material, ResourceType> resources = new EnumMap<>(Material.class);
    private final Map<UUID, Boolean> initialized = new HashMap<>();
    private final Map<UUID, Long> overflowWarnings = new HashMap<>();

    public WarehouseManager(BobsterDefence plugin, StateRepository repository, ConstructionManager construction) {
        this.plugin = plugin;
        this.repository = repository;
        this.construction = construction;
        reload();
    }

    public void reload() {
        materials.clear();
        resources.clear();
        register(ResourceType.WOOD, configured(ResourceType.WOOD, Material.OAK_LOG));
        register(ResourceType.STONE, configured(ResourceType.STONE, Material.COBBLESTONE));
        register(ResourceType.IRON, configured(ResourceType.IRON, Material.IRON_INGOT));
        register(ResourceType.COAL, configured(ResourceType.COAL, Material.COAL));
        register(ResourceType.FOOD, configured(ResourceType.FOOD, Material.BREAD));
        register(ResourceType.GUNPOWDER, configured(ResourceType.GUNPOWDER, Material.GUNPOWDER));
        register(ResourceType.REDSTONE, configured(ResourceType.REDSTONE, Material.REDSTONE));
        register(ResourceType.FUEL, configured(ResourceType.FUEL, Material.DRIED_KELP_BLOCK));
        register(ResourceType.ADVANCED_COMPONENTS, configured(ResourceType.ADVANCED_COMPONENTS, Material.COMPARATOR));
        register(ResourceType.ENERGY, configured(ResourceType.ENERGY, Material.GLOWSTONE_DUST));
    }

    public boolean pull(UUID stateId, StateEconomy economy) {
        Storage storage = storage(stateId);
        if (storage == null) {
            return false;
        }
        if (storage.buildings().stream().anyMatch(building -> !initialized(building.id()))) {
            push(stateId, economy);
            for (StateBuilding building : storage.buildings()) {
                markInitialized(building.id());
            }
            return true;
        }
        Map<ResourceType, Integer> counts = count(storage.inventories());
        for (ResourceType type : ResourceType.values()) {
            double fraction = economy.amount(type) - Math.floor(economy.amount(type));
            economy.amount(type, counts.getOrDefault(type, 0) + fraction);
        }
        return true;
    }

    public boolean push(UUID stateId, StateEconomy economy) {
        Storage storage = storage(stateId);
        if (storage == null) {
            return false;
        }
        clearResources(storage.inventories());
        for (ResourceType type : ResourceType.values()) {
            int requested = Math.max(0, (int) Math.floor(economy.amount(type)));
            int stored = store(storage.inventories(), materials.get(type), requested);
            if (stored < requested) {
                double fraction = economy.amount(type) - Math.floor(economy.amount(type));
                economy.amount(type, stored + fraction);
                warnOverflow(stateId, type, requested - stored);
            }
        }
        return true;
    }

    public boolean withdraw(UUID stateId, StateEconomy economy, Map<ResourceType, Double> cost) {
        pull(stateId, economy);
        for (Map.Entry<ResourceType, Double> entry : cost.entrySet()) {
            if (economy.amount(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        for (Map.Entry<ResourceType, Double> entry : cost.entrySet()) {
            economy.consume(entry.getKey(), entry.getValue());
        }
        push(stateId, economy);
        return true;
    }

    public void deposit(UUID stateId, StateEconomy economy, Map<ResourceType, Double> resources) {
        for (Map.Entry<ResourceType, Double> entry : resources.entrySet()) {
            economy.add(entry.getKey(), entry.getValue());
        }
        push(stateId, economy);
    }

    public UUID stateAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        for (StateBuilding building : construction.allBuildings()) {
            if (building.type() == BuildingType.WAREHOUSE && building.contains(location.getWorld().getName(), location.getX(), location.getY(), location.getZ())) {
                return building.stateId();
            }
        }
        return null;
    }

    public Material material(ResourceType type) {
        return materials.get(type);
    }

    private Storage storage(UUID stateId) {
        List<StateBuilding> buildings = construction.workplaces(stateId, BuildingType.WAREHOUSE);
        if (buildings.isEmpty()) {
            return null;
        }
        List<Inventory> inventories = new ArrayList<>();
        for (StateBuilding building : buildings) {
            World world = Bukkit.getWorld(building.origin().world());
            if (world == null || !loaded(world, building)) {
                return null;
            }
            for (int x = 0; x < building.sizeX(); x++) {
                for (int y = 0; y < building.sizeY(); y++) {
                    for (int z = 0; z < building.sizeZ(); z++) {
                        if (world.getBlockAt((int) building.origin().x() + x, (int) building.origin().y() + y, (int) building.origin().z() + z).getState() instanceof Container container) {
                            inventories.add(container.getInventory());
                        }
                    }
                }
            }
        }
        return inventories.isEmpty() ? null : new Storage(buildings, inventories);
    }

    private Map<ResourceType, Integer> count(List<Inventory> inventories) {
        Map<ResourceType, Integer> counts = new EnumMap<>(ResourceType.class);
        for (Inventory inventory : inventories) {
            for (ItemStack item : inventory.getStorageContents()) {
                ResourceType type = item == null ? null : resources.get(item.getType());
                if (type != null) {
                    counts.merge(type, item.getAmount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    private void clearResources(List<Inventory> inventories) {
        for (Inventory inventory : inventories) {
            ItemStack[] contents = inventory.getStorageContents();
            for (int index = 0; index < contents.length; index++) {
                if (contents[index] != null && resources.containsKey(contents[index].getType())) {
                    contents[index] = null;
                }
            }
            inventory.setStorageContents(contents);
        }
    }

    private int store(List<Inventory> inventories, Material material, int requested) {
        int remaining = requested;
        for (Inventory inventory : inventories) {
            while (remaining > 0) {
                int amount = Math.min(material.getMaxStackSize(), remaining);
                Map<Integer, ItemStack> rejected = inventory.addItem(new ItemStack(material, amount));
                int rejectedAmount = rejected.values().stream().mapToInt(ItemStack::getAmount).sum();
                remaining -= amount - rejectedAmount;
                if (rejectedAmount > 0) {
                    break;
                }
            }
            if (remaining == 0) {
                break;
            }
        }
        return requested - remaining;
    }

    private boolean initialized(UUID buildingId) {
        return initialized.computeIfAbsent(buildingId, id -> {
            try {
                return repository.settingBoolean("warehouse.initialized." + id, false);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Не вдалося прочитати стан складу " + id + ": " + ex.getMessage());
                return false;
            }
        });
    }

    private void markInitialized(UUID buildingId) {
        initialized.put(buildingId, true);
        try {
            repository.setBoolean("warehouse.initialized." + buildingId, true);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не вдалося зберегти стан складу " + buildingId + ": " + ex.getMessage());
        }
    }

    private boolean loaded(World world, StateBuilding building) {
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

    private void warnOverflow(UUID stateId, ResourceType type, int lost) {
        long now = System.currentTimeMillis();
        if (now - overflowWarnings.getOrDefault(stateId, 0L) < 60_000L) {
            return;
        }
        overflowWarnings.put(stateId, now);
        plugin.getLogger().warning("Склад держави " + stateId + " переповнений: втрачено " + lost + " " + type);
    }

    private void register(ResourceType type, Material material) {
        materials.put(type, material);
        resources.put(material, type);
    }

    private Material configured(ResourceType type, Material fallback) {
        String path = "strategic-states.warehouse.resource-items." + type.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        Material configured = Material.matchMaterial(plugin.getConfig().getString(path, fallback.name()));
        if (configured == null || configured.isAir() || configured.getMaxStackSize() <= 0 || resources.containsKey(configured)) {
            return fallback;
        }
        for (ResourceType other : ResourceType.values()) {
            if (other != type && defaultMaterial(other) == configured) {
                return fallback;
            }
        }
        return configured;
    }

    private Material defaultMaterial(ResourceType type) {
        return switch (type) {
            case WOOD -> Material.OAK_LOG;
            case STONE -> Material.COBBLESTONE;
            case IRON -> Material.IRON_INGOT;
            case COAL -> Material.COAL;
            case FOOD -> Material.BREAD;
            case GUNPOWDER -> Material.GUNPOWDER;
            case REDSTONE -> Material.REDSTONE;
            case FUEL -> Material.DRIED_KELP_BLOCK;
            case ADVANCED_COMPONENTS -> Material.COMPARATOR;
            case ENERGY -> Material.GLOWSTONE_DUST;
        };
    }

    private record Storage(List<StateBuilding> buildings, List<Inventory> inventories) {
    }
}
