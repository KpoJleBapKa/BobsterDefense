package ua.bobster.defence.strategicstates.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ua.bobster.defence.BobsterDefence;

import java.util.List;

public class StateCoreItem {

    private final NamespacedKey itemTypeKey;

    public StateCoreItem(BobsterDefence plugin) {
        this.itemTypeKey = new NamespacedKey(plugin, "item_type");
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.BEDROCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Strategic State Core", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Створює автономну NPC-державу", NamedTextColor.GRAY),
                Component.text("Максимум п'ять активних держав", NamedTextColor.DARK_GRAY)));
        meta.getPersistentDataContainer().set(itemTypeKey, PersistentDataType.STRING, "state_core");
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCore(ItemStack item) {
        if (item == null || item.getType() != Material.BEDROCK || !item.hasItemMeta()) {
            return false;
        }
        return "state_core".equals(item.getItemMeta().getPersistentDataContainer().get(itemTypeKey, PersistentDataType.STRING));
    }
}
