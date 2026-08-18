package ua.bobster.defence.ballistic;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Панель керування установкою. Звичайний 9-слотовий інвентар — жодних ресурспаків,
 * а виглядає як пульт.
 */
public class BallisticGui implements InventoryHolder {

    public static final int SLOT_STATUS = 2;
    public static final int SLOT_TARGET = 4;
    public static final int SLOT_AMMO = 6;
    public static final int SLOT_FIRE = 8;

    private final BobsterDefence plugin;
    private final BallisticManager manager;
    private final Block launcherBlock;
    private final Inventory inventory;

    public BallisticGui(BobsterDefence plugin, BallisticManager manager, Block launcherBlock) {
        this.plugin = plugin;
        this.manager = manager;
        this.launcherBlock = launcherBlock;
        this.inventory = Bukkit.createInventory(this, 9,
                MessageUtil.parse(plugin.message("ballistic-gui-title")));
    }

    public Block launcherBlock() {
        return launcherBlock;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void refresh() {
        BallisticLauncher launcher = manager.launcherAt(launcherBlock);
        LauncherTier tier = launcher == null ? null : manager.tier(launcher.tierId());
        if (tier == null) {
            return;
        }
        double distance = launcher.hasTarget()
                ? manager.horizontalDistance(launcherBlock.getLocation(), launcher.targetX(), launcher.targetZ())
                : 0;
        long cooldownLeft = Math.max(0L,
                tier.cooldownSeconds() * 1000L - (System.currentTimeMillis() - launcher.lastLaunch())) / 1000L;

        Map<String, Object> placeholders = Map.of(
                "name", MessageUtil.raw(tier.displayName()),
                "range", tier.range(),
                "power", tier.explosionPower(),
                "ammo", launcher.ammoCount(tier),
                "need", tier.ammo(),
                "hits", tier.hitsToIntercept(),
                "x", launcher.hasTarget() ? launcher.targetX() : "—",
                "z", launcher.hasTarget() ? launcher.targetZ() : "—",
                "distance", (int) Math.round(distance),
                "cooldown", cooldownLeft);

        inventory.setItem(SLOT_STATUS, button(Material.PAPER,
                "ballistic-gui-status-name", "ballistic-gui-status-lore", placeholders));
        inventory.setItem(SLOT_TARGET, button(Material.FILLED_MAP,
                "ballistic-gui-target-name", "ballistic-gui-target-lore", placeholders));
        inventory.setItem(SLOT_AMMO, button(Material.TNT,
                "ballistic-gui-ammo-name", "ballistic-gui-ammo-lore", placeholders));

        boolean ready = manager.validate(launcher, tier) == null;
        inventory.setItem(SLOT_FIRE, button(ready ? Material.FIRE_CHARGE : Material.GRAY_DYE,
                ready ? "ballistic-gui-fire-name" : "ballistic-gui-fire-blocked-name",
                "ballistic-gui-fire-lore", placeholders));
    }

    private ItemStack button(Material material, String nameKey, String loreKey,
                             Map<String, Object> placeholders) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MessageUtil.parse(plugin.message(nameKey), placeholders)
                .decoration(TextDecoration.ITALIC, false));

        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("messages." + loreKey)) {
            lore.add(MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public void open(Player player) {
        refresh();
        player.openInventory(inventory);
    }
}
