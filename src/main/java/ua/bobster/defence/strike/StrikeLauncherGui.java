package ua.bobster.defence.strike;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Dropper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;

public class StrikeLauncherGui implements InventoryHolder {

    public static final int SLOT_REDSTONE = 0;
    public static final int SLOT_STATUS = 2;
    public static final int SLOT_TARGET = 4;
    public static final int SLOT_AMMO = 6;
    public static final int SLOT_LAUNCH = 8;

    private final StrikeLauncherManager manager;
    private final Block block;
    private final Inventory inventory;

    public StrikeLauncherGui(StrikeLauncherManager manager, Block block) {
        this.manager = manager;
        this.block = block;
        StrikeLauncherItem.Kind kind = manager.kind(block);
        String title = kind == StrikeLauncherItem.Kind.DRONE ? "<dark_gray>Drone launch system" : "<dark_gray>Rocket launch system";
        this.inventory = Bukkit.createInventory(this, 9, MessageUtil.parse(title));
    }

    public Block block() {
        return block;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        refresh();
        player.openInventory(inventory);
    }

    public void refresh() {
        StrikeLauncherItem.Kind kind = manager.kind(block);
        boolean redstone = manager.redstone(block);
        int[] target = manager.target(block);
        int ammo = manager.ammoCount(block);
        DroneLauncherRegistry.Entry droneEntry = kind == StrikeLauncherItem.Kind.DRONE ? manager.droneRegistry().at(block) : null;
        String launcherType = kind == StrikeLauncherItem.Kind.DRONE ? "Drone launcher #" + (droneEntry == null ? "—" : droneEntry.number()) : "Guided missile launcher";
        inventory.setItem(SLOT_REDSTONE, button(redstone ? Material.LIME_DYE : Material.RED_DYE, redstone ? "<green><bold>REDSTONE: ON" : "<red><bold>REDSTONE: OFF", List.of("<gray>Клік — перемкнути автоматичний пуск")));
        inventory.setItem(SLOT_STATUS, button(Material.PAPER, "<gold><bold>СТАТУС", List.of("<gray>Тип: <white>" + launcherType, "<gray>Боєприпасів у відсіку: <white>" + ammo)));
        inventory.setItem(SLOT_TARGET, button(Material.COMPASS, target == null ? "<red><bold>ЦІЛЬ НЕ ЗАДАНА" : "<yellow><bold>ЦІЛЬ: " + target[0] + ", " + target[1], kind == StrikeLauncherItem.Kind.DRONE ? List.of("<gray>/drone launcher target <x> <z>", "<gray>/drone launcher target here") : List.of("<gray>Крилата ракета керується оператором")));
        inventory.setItem(SLOT_AMMO, button(Material.CHEST, "<aqua><bold>БОЄПРИПАСИ: " + ammo, List.of("<gray>Клік — відкрити відсік", "<gray>Також працює присів + ПКМ")));
        inventory.setItem(SLOT_LAUNCH, button(Material.FIRE_CHARGE, "<red><bold>ПУСК", List.of("<gray>Клік — запустити один боєприпас")));
    }

    private ItemStack button(Material material, String name, List<String> lines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.parse(name).decoration(TextDecoration.ITALIC, false));
        ArrayList<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(MessageUtil.parse(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
