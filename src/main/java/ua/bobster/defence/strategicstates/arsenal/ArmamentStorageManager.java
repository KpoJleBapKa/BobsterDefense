package ua.bobster.defence.strategicstates.arsenal;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.construction.BuildingStatus;
import ua.bobster.defence.strategicstates.construction.BuildingType;
import ua.bobster.defence.strategicstates.construction.ConstructionManager;
import ua.bobster.defence.strategicstates.construction.StateBuilding;

import java.util.EnumMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ArmamentStorageManager {

    private final ConstructionManager construction;
    private final NamespacedKey itemKey;
    private final NamespacedKey storageKey;
    private final NamespacedKey stateKey;
    private final Map<ArsenalItem, BuildingType> buildings = new EnumMap<>(ArsenalItem.class);
    private final Set<String> newlyCreated = new HashSet<>();

    public ArmamentStorageManager(BobsterDefence plugin, ConstructionManager construction) {
        this.construction = construction;
        this.itemKey = new NamespacedKey(plugin, "state_armament");
        this.storageKey = new NamespacedKey(plugin, "state_armament_storage");
        this.stateKey = new NamespacedKey(plugin, "state_armament_owner");
        buildings.put(ArsenalItem.SMALL_ARMS, BuildingType.WORKSHOP);
        buildings.put(ArsenalItem.AIR_DEFENCE_MISSILE, BuildingType.AIR_DEFENCE_SITE);
        buildings.put(ArsenalItem.STRIKE_DRONE, BuildingType.DRONE_FACTORY);
        buildings.put(ArsenalItem.BALLISTIC_MISSILE, BuildingType.MISSILE_FACTORY);
    }

    public void synchronize(UUID stateId, StateArsenal arsenal) {
        for (ArsenalItem item : ArsenalItem.values()) {
            Container container = storage(stateId, item, true);
            if (container == null) {
                continue;
            }
            if (newlyCreated.remove(key(stateId, item))) {
                store(stateId, arsenal, item);
                continue;
            }
            int physical = count(container.getInventory(), item);
            arsenal.amount(item, physical);
        }
    }

    public void store(UUID stateId, StateArsenal arsenal, ArsenalItem item) {
        Container container = storage(stateId, item, true);
        if (container == null) {
            return;
        }
        Inventory inventory = container.getInventory();
        remove(inventory, item);
        int remaining = arsenal.amount(item);
        ItemStack template = create(item, 1);
        while (remaining > 0) {
            int amount = Math.min(remaining, template.getMaxStackSize());
            inventory.addItem(create(item, amount));
            remaining -= amount;
        }
    }

    public boolean consume(UUID stateId, StateArsenal arsenal, ArsenalItem item) {
        Container container = storage(stateId, item, true);
        if (container != null) {
            if (newlyCreated.remove(key(stateId, item))) {
                store(stateId, arsenal, item);
            }
            int physical = count(container.getInventory(), item);
            arsenal.amount(item, physical);
            if (physical < 1) {
                return false;
            }
        } else if (arsenal.amount(item) < 1) {
            return false;
        }
        arsenal.amount(item, arsenal.amount(item) - 1);
        if (container != null) {
            store(stateId, arsenal, item);
        }
        return true;
    }

    public UUID stateAt(Location location) {
        if (location == null) {
            return null;
        }
        Block block = location.getBlock();
        if (!(block.getState() instanceof TileState tile)) {
            return null;
        }
        String raw = tile.getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Container storage(UUID stateId, ArsenalItem item, boolean create) {
        StateBuilding building = construction.workplace(stateId, buildings.get(item));
        if (building == null || building.status() != BuildingStatus.MATERIALIZED) {
            return null;
        }
        Location origin = building.origin().location();
        if (origin == null || !origin.getWorld().isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) {
            return null;
        }
        Location location = storageLocation(building, item);
        Block block = location.getBlock();
        if (!(block.getState() instanceof Container) && create && (block.isPassable() || block.getType() == Material.AIR)) {
            block.setType(Material.CHEST, false);
            newlyCreated.add(key(stateId, item));
        }
        if (!(block.getState() instanceof Container container) || !(container instanceof TileState tile)) {
            return null;
        }
        boolean initialized = tile.getPersistentDataContainer().has(storageKey, PersistentDataType.STRING);
        tile.getPersistentDataContainer().set(storageKey, PersistentDataType.STRING, item.name());
        tile.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, stateId.toString());
        tile.update(true, false);
        if (!initialized) {
            newlyCreated.add(key(stateId, item));
        }
        return container;
    }

    private String key(UUID stateId, ArsenalItem item) {
        return stateId + ":" + item.name();
    }

    private Location storageLocation(StateBuilding building, ArsenalItem item) {
        Location origin = building.origin().location();
        if (item == ArsenalItem.AIR_DEFENCE_MISSILE) {
            return origin.add(3.0D, 2.0D, Math.min(15.0D, building.sizeZ() - 2.0D));
        }
        return origin.add(2.0D, 2.0D, 2.0D);
    }

    private ItemStack create(ArsenalItem item, int amount) {
        ItemStack stack = new ItemStack(material(item), amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name(item)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, item.name());
        stack.setItemMeta(meta);
        return stack;
    }

    private Material material(ArsenalItem item) {
        return switch (item) {
            case SMALL_ARMS -> Material.CROSSBOW;
            case AIR_DEFENCE_MISSILE -> Material.ARROW;
            case STRIKE_DRONE -> Material.PHANTOM_MEMBRANE;
            case BALLISTIC_MISSILE -> Material.FIREWORK_ROCKET;
        };
    }

    private String name(ArsenalItem item) {
        return switch (item) {
            case SMALL_ARMS -> "Державна стрілецька зброя";
            case AIR_DEFENCE_MISSILE -> "Зенітна керована ракета";
            case STRIKE_DRONE -> "Ударний дрон";
            case BALLISTIC_MISSILE -> "Балістична ракета";
        };
    }

    private int count(Inventory inventory, ArsenalItem item) {
        int amount = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && item.name().equals(stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING))) {
                amount += stack.getAmount();
            }
        }
        return amount;
    }

    private void remove(Inventory inventory, ArsenalItem item) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && item.name().equals(stack.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING))) {
                inventory.setItem(slot, null);
            }
        }
    }
}
