package ua.bobster.defence.ballistic;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Обгортка над блоком-диспенсером: усе, що установка «пам'ятає», лежить у PDC самого блока,
 * тому дані переживають перезавантаження сервера й вивантаження чанків.
 */
public class BallisticLauncher {

    private final Block block;
    private final BallisticItem item;
    private final NamespacedKey tierKey;
    private final NamespacedKey targetXKey;
    private final NamespacedKey targetZKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey cooldownKey;
    private final NamespacedKey reconKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey redstoneKey;

    BallisticLauncher(Block block, BallisticItem item, NamespacedKey tierKey, NamespacedKey targetXKey,
                      NamespacedKey targetZKey, NamespacedKey displayKey, NamespacedKey cooldownKey,
                      NamespacedKey reconKey, NamespacedKey ownerKey, NamespacedKey redstoneKey) {
        this.reconKey = reconKey;
        this.ownerKey = ownerKey;
        this.redstoneKey = redstoneKey;
        this.block = block;
        this.item = item;
        this.tierKey = tierKey;
        this.targetXKey = targetXKey;
        this.targetZKey = targetZKey;
        this.displayKey = displayKey;
        this.cooldownKey = cooldownKey;
    }

    public Block block() {
        return block;
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

    private void mutate(java.util.function.Consumer<PersistentDataContainer> action) {
        Dispenser dispenser = state(true);
        if (dispenser == null) {
            return;
        }
        action.accept(dispenser.getPersistentDataContainer());
        dispenser.update(true, false);
    }

    public String tierId() {
        PersistentDataContainer pdc = container();
        return pdc == null ? null : pdc.get(tierKey, PersistentDataType.STRING);
    }

    public void tierId(String id) {
        mutate(pdc -> pdc.set(tierKey, PersistentDataType.STRING, id));
    }

    public boolean hasTarget() {
        PersistentDataContainer pdc = container();
        return pdc != null && pdc.has(targetXKey, PersistentDataType.INTEGER);
    }

    public int targetX() {
        PersistentDataContainer pdc = container();
        return pdc == null ? 0 : pdc.getOrDefault(targetXKey, PersistentDataType.INTEGER, 0);
    }

    public int targetZ() {
        PersistentDataContainer pdc = container();
        return pdc == null ? 0 : pdc.getOrDefault(targetZKey, PersistentDataType.INTEGER, 0);
    }

    public void target(int x, int z) {
        mutate(pdc -> {
            pdc.set(targetXKey, PersistentDataType.INTEGER, x);
            pdc.set(targetZKey, PersistentDataType.INTEGER, z);
        });
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

    /** Хто поставив установку. Тільки він може розібрати її руками. */
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

    public boolean redstoneEnabled() {
        PersistentDataContainer pdc = container();
        return pdc != null && pdc.getOrDefault(redstoneKey, PersistentDataType.BYTE, (byte) 0) == (byte) 1;
    }

    public void redstoneEnabled(boolean enabled) {
        mutate(pdc -> pdc.set(redstoneKey, PersistentDataType.BYTE, enabled ? (byte) 1 : (byte) 0));
    }

    /** Час (millis), до якого установка лишається без розвідника після втрати попереднього. */
    public long reconCooldown() {
        PersistentDataContainer pdc = container();
        return pdc == null ? 0L : pdc.getOrDefault(reconKey, PersistentDataType.LONG, 0L);
    }

    public void reconCooldown(long until) {
        mutate(pdc -> pdc.set(reconKey, PersistentDataType.LONG, until));
    }

    public long lastLaunch() {
        PersistentDataContainer pdc = container();
        return pdc == null ? 0L : pdc.getOrDefault(cooldownKey, PersistentDataType.LONG, 0L);
    }

    public void lastLaunch(long millis) {
        mutate(pdc -> pdc.set(cooldownKey, PersistentDataType.LONG, millis));
    }

    /** @return скільки придатних ракет лежить усередині установки */
    public int ammoCount(LauncherTier tier) {
        Dispenser dispenser = state(false);
        if (dispenser == null || tier == null) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : dispenser.getInventory().getContents()) {
            if (item.isRocketFor(stack, tier)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Списує одну ракету.
     *
     * @return true, якщо ракета знайшлася
     */
    public ItemStack consumeAmmo(LauncherTier tier) {
        Dispenser dispenser = state(false);
        if (dispenser == null || tier == null) {
            return null;
        }
        Inventory inventory = dispenser.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!item.isRocketFor(stack, tier)) {
                continue;
            }
            ItemStack consumed = stack.clone();
            consumed.setAmount(1);
            stack.setAmount(stack.getAmount() - 1);
            inventory.setItem(slot, stack.getAmount() <= 0 ? null : stack);
            return consumed;
        }
        return null;
    }
}
