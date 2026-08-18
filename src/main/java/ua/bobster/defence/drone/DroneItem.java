package ua.bobster.defence.drone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Предмети дронів. Тип зберігається в PDC, тож звичайний TNT дроном не стане,
 * а два різні дрони не переплутаються між собою.
 */
public class DroneItem {

    private final BobsterDefence plugin;
    private final NamespacedKey key;
    private final NamespacedKey typeKey;

    public DroneItem(BobsterDefence plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "fpv_drone");
        this.typeKey = new NamespacedKey(plugin, "fpv_drone_type");
    }

    public NamespacedKey key() {
        return key;
    }

    public ItemStack create(DroneType type, int amount) {
        ItemStack item = new ItemStack(type.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(MessageUtil.parse(type.displayName()).decoration(TextDecoration.ITALIC, false));

        Map<String, Object> placeholders = Map.of(
                "distance", type.maxDistance(),
                "time", type.flightTime(),
                "power", type.explosionPower(),
                "damage", (int) Math.round(type.playerDamage()),
                "speed", type.speed());
        List<Component> lore = new ArrayList<>();
        for (String line : type.lore()) {
            lore.add(MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        if (plugin.getConfig().getBoolean("fpv-drone.glow", true)) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isDrone(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** @return id типу дрона або null */
    public String typeIdOf(ItemStack item) {
        if (!isDrone(item)) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }
}
