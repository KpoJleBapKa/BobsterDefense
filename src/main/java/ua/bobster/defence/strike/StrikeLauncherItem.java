package ua.bobster.defence.strike;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.missile.MissilePayload;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.Map;

public class StrikeLauncherItem {

    public enum Kind {
        DRONE,
        GUIDED
    }

    private final BobsterDefence plugin;
    private final NamespacedKey launcherKey;
    private final NamespacedKey missileKey;

    public StrikeLauncherItem(BobsterDefence plugin) {
        this.plugin = plugin;
        this.launcherKey = new NamespacedKey(plugin, "strike_launcher_item");
        this.missileKey = new NamespacedKey(plugin, "guided_missile_item");
    }

    public ItemStack createLauncher(Kind kind, int amount) {
        ItemStack item = new ItemStack(Material.DROPPER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        String name = kind == Kind.DRONE ? "<aqua><bold>Drone launch system</bold>" : "<gold><bold>Rocket launch system</bold>";
        meta.displayName(MessageUtil.parse(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(java.util.List.of(
                MessageUtil.parse("<gray>ПКМ — пульт керування").decoration(TextDecoration.ITALIC, false),
                MessageUtil.parse("<gray>Присів + ПКМ — відсік боєприпасів").decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(launcherKey, PersistentDataType.STRING, kind.name());
        item.setItemMeta(meta);
        return item;
    }

    public Kind launcherKind(ItemStack item) {
        if (item == null || item.getType() != Material.DROPPER || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(launcherKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return Kind.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public ItemStack createMissile(GuidedMissileType type, int amount) {
        return createMissile(type, amount, MissilePayload.explosive());
    }

    public ItemStack createMissile(GuidedMissileType type, int amount, MissilePayload payload) {
        ItemStack item = new ItemStack(Material.FIREWORK_ROCKET, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.parse(type.displayName()).decoration(TextDecoration.ITALIC, false));
        Map<String, Object> placeholders = Map.of("range", "без обмежень", "fuel", type.fuelSeconds(), "power", plugin.payloads().powerText(payload, type.explosionPower()), "hits", type.hitsToIntercept(), "speed", type.speed());
        ArrayList<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : type.lore()) {
            lore.add(MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(missileKey, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return plugin.payloads().write(item, payload);
    }

    public String missileType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(missileKey, PersistentDataType.STRING);
    }
}
