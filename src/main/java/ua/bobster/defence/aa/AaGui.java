package ua.bobster.defence.aa;

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

public class AaGui implements InventoryHolder {

    public static final int SLOT_AMMO = 2;
    public static final int SLOT_ELYTRA = 6;

    private final BobsterDefence plugin;
    private final AaManager manager;
    private final Block block;
    private final Inventory inventory;

    public AaGui(BobsterDefence plugin, AaManager manager, Block block) {
        this.plugin = plugin;
        this.manager = manager;
        this.block = block;
        this.inventory = Bukkit.createInventory(this, 9, MessageUtil.parse(plugin.message("aa-gui-title")));
    }

    public Block block() {
        return block;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void refresh() {
        AaLauncher launcher = manager.launcherAt(block);
        AaTier tier = launcher == null ? null : manager.tier(launcher.tierId());
        if (tier == null) {
            return;
        }
        boolean enabled = launcher.elytraDefenceEnabled();
        Map<String, Object> placeholders = Map.of("ammo", launcher.ammoCount(tier), "name", MessageUtil.raw(tier.displayName()));
        inventory.setItem(SLOT_AMMO, button(Material.FIREWORK_ROCKET, "aa-gui-ammo-name", "aa-gui-ammo-lore", placeholders));
        inventory.setItem(SLOT_ELYTRA, button(enabled ? Material.LIME_DYE : Material.RED_DYE, enabled ? "aa-gui-elytra-on" : "aa-gui-elytra-off", "aa-gui-elytra-lore", placeholders));
    }

    public void open(Player player) {
        refresh();
        player.openInventory(inventory);
    }

    private ItemStack button(Material material, String nameKey, String loreKey, Map<String, Object> placeholders) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(MessageUtil.parse(plugin.message(nameKey), placeholders).decoration(TextDecoration.ITALIC, false));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("messages." + loreKey)) {
            lore.add(MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
