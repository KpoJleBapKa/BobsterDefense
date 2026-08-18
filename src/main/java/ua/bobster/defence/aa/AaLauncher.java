package ua.bobster.defence.aa;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Установка ППО як блок. Рівень, власник і службові дані живуть у PDC самого диспенсера.
 */
public class AaLauncher {

    private final Block block;
    private final AaItem item;
    private final NamespacedKey ownerKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey turretKey;

    AaLauncher(Block block, AaItem item, NamespacedKey ownerKey,
               NamespacedKey displayKey, NamespacedKey turretKey) {
        this.block = block;
        this.item = item;
        this.ownerKey = ownerKey;
        this.displayKey = displayKey;
        this.turretKey = turretKey;
    }

    public Block block() {
        return block;
    }

    /** Точка, з якої летять перехоплювачі та звідки перевіряється видимість цілі. */
    public Location muzzle() {
        return block.getLocation().add(0.5D, 1.4D, 0.5D);
    }

    private Dispenser state(boolean snapshot) {
        if (block.getType() != Material.DISPENSER) {
            return null;
        }
        return block.getState(snapshot) instanceof Dispenser dispenser ? dispenser : null;
    }

    private PersistentDataContainer container() {
        Dispenser dispenser = state(true);
        return dispenser == null ? null : dispenser.getPersistentDataContainer();
    }

    private void mutate(Consumer<PersistentDataContainer> action) {
        Dispenser dispenser = state(true);
        if (dispenser == null) {
            return;
        }
        action.accept(dispenser.getPersistentDataContainer());
        dispenser.update(true, false);
    }

    public String tierId() {
        PersistentDataContainer pdc = container();
        return pdc == null ? null : pdc.get(item.tierKey(), PersistentDataType.STRING);
    }

    public void tierId(String id) {
        mutate(pdc -> pdc.set(item.tierKey(), PersistentDataType.STRING, id));
    }

    public UUID owner() {
        PersistentDataContainer pdc = container();
        if (pdc == null) {
            return null;
        }
        String raw = pdc.get(ownerKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void owner(UUID uuid) {
        mutate(pdc -> pdc.set(ownerKey, PersistentDataType.STRING, uuid.toString()));
    }

    public UUID displayId() {
        PersistentDataContainer pdc = container();
        if (pdc == null) {
            return null;
        }
        String raw = pdc.get(displayKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void displayId(UUID uuid) {
        mutate(pdc -> pdc.set(displayKey, PersistentDataType.STRING, uuid.toString()));
    }

    public UUID turretId() {
        return readUuid(turretKey);
    }

    public void turretId(UUID uuid) {
        mutate(pdc -> pdc.set(turretKey, PersistentDataType.STRING, uuid.toString()));
    }

    private UUID readUuid(NamespacedKey key) {
        PersistentDataContainer pdc = container();
        if (pdc == null) {
            return null;
        }
        String raw = pdc.get(key, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public int ammoCount(AaTier tier) {
        Dispenser dispenser = state(false);
        if (dispenser == null || tier == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : dispenser.getInventory().getContents()) {
            if (item.isAmmoFor(stack, tier)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** @return true, якщо боєприпас знайшовся і був списаний */
    public boolean consumeAmmo(AaTier tier) {
        Dispenser dispenser = state(false);
        if (dispenser == null || tier == null) {
            return false;
        }
        Inventory inventory = dispenser.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!item.isAmmoFor(stack, tier)) {
                continue;
            }
            stack.setAmount(stack.getAmount() - 1);
            inventory.setItem(slot, stack.getAmount() <= 0 ? null : stack);
            return true;
        }
        return false;
    }
}
