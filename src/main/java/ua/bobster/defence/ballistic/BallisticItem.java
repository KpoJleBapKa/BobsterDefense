package ua.bobster.defence.ballistic;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
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
 * Предмет пускової установки. Фізично це DISPENSER, але з міткою рівня в PDC —
 * звичайний диспенсер ніколи не стане установкою, і навпаки.
 */
public class BallisticItem {

    private final BobsterDefence plugin;
    private final NamespacedKey tierKey;
    private final NamespacedKey rocketKey;

    public BallisticItem(BobsterDefence plugin) {
        this.plugin = plugin;
        this.tierKey = new NamespacedKey(plugin, "ballistic_tier");
        this.rocketKey = new NamespacedKey(plugin, "ballistic_rocket");
    }

    public NamespacedKey tierKey() {
        return tierKey;
    }

    /**
     * Ракета як окремий предмет. Усі бойові характеристики (дальність, сила, перезарядка)
     * належать саме ракеті — установка лише пускова труба, тому вони й виписані в її lore.
     */
    public ItemStack createRocket(LauncherTier tier, int amount) {
        ItemStack item = new ItemStack(Material.FIREWORK_ROCKET, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.parse(tier.rocketName()).decoration(TextDecoration.ITALIC, false));

        Map<String, Object> placeholders = Map.of(
                "range", tier.range(),
                "power", tier.explosionPower(),
                "hits", tier.hitsToIntercept(),
                "cooldown", tier.cooldownSeconds(),
                "speed", tier.speed(),
                "launcher", MessageUtil.plain(MessageUtil.parse(tier.displayName())));
        List<Component> lore = new ArrayList<>();
        for (String line : tier.rocketLore()) {
            lore.add(MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(rocketKey, PersistentDataType.STRING, tier.id());
        item.setItemMeta(meta);
        return item;
    }

    /** @return id установки, до якої підходить ця ракета, або null */
    public String rocketTierOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(rocketKey, PersistentDataType.STRING);
    }

    public boolean isRocketFor(ItemStack item, LauncherTier tier) {
        return tier != null && tier.id().equalsIgnoreCase(rocketTierOf(item));
    }

    public ItemStack create(LauncherTier tier, int amount) {
        ItemStack item = new ItemStack(Material.DISPENSER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(MessageUtil.parse(tier.displayName()).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        Map<String, Object> placeholders = Map.of(
                "range", tier.range(),
                "ammo", tier.ammo(),
                "power", tier.explosionPower(),
                "hits", tier.hitsToIntercept(),
                "cooldown", tier.cooldownSeconds());
        for (String line : tier.lore()) {
            lore.add(MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.id());
        item.setItemMeta(meta);
        return item;
    }

    /** @return id рівня установки або null, якщо це звичайний предмет */
    public String tierIdOf(ItemStack item) {
        if (item == null || item.getType() != Material.DISPENSER || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
    }
}
