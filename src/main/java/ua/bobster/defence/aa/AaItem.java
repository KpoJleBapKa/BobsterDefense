package ua.bobster.defence.aa;

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
 * Предмети ППО: сама установка (DISPENSER із міткою рівня) та боєприпас-перехоплювач
 * (ARROW із власною міткою).
 * <p>
 * Окремий боєприпас потрібен навмисно: інакше установку можна було б зарядити тисячею
 * звичайних стріл, і ППО перестало б чогось коштувати.
 */
public class AaItem {

    private final BobsterDefence plugin;
    private final NamespacedKey tierKey;
    private final NamespacedKey ammoKey;

    public AaItem(BobsterDefence plugin) {
        this.plugin = plugin;
        this.tierKey = new NamespacedKey(plugin, "aa_tier");
        this.ammoKey = new NamespacedKey(plugin, "aa_ammo");
    }

    public NamespacedKey tierKey() {
        return tierKey;
    }

    public NamespacedKey ammoKey() {
        return ammoKey;
    }

    public ItemStack createLauncher(AaTier tier, int amount) {
        ItemStack item = new ItemStack(Material.DISPENSER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.parse(tier.displayName()).decoration(TextDecoration.ITALIC, false));

        Map<String, Object> placeholders = Map.of(
                "range", tier.range(),
                "zone", tier.range() * 2,
                "vertical", tier.verticalRange(),
                "targets", tier.maxTargets(),
                "cooldown", String.format("%.1f", tier.fireCooldown() / 20.0D),
                "speed", tier.speed());
        meta.lore(parseLore(tier.lore(), placeholders));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.id());
        item.setItemMeta(meta);
        return item;
    }

    /** Кожна установка має власну ракету — перехоплювач від NASAMS у Patriot не підійде. */
    public ItemStack createAmmo(AaTier tier, int amount) {
        ItemStack item = new ItemStack(Material.ARROW, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtil.parse(tier.ammoName()).decoration(TextDecoration.ITALIC, false));
        meta.lore(parseLore(tier.ammoLore(), Map.of(
                "launcher", MessageUtil.plain(MessageUtil.parse(tier.displayName())))));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(ammoKey, PersistentDataType.STRING, tier.id());
        item.setItemMeta(meta);
        return item;
    }

    /** @return id установки, до якої підходить цей боєприпас, або null */
    public String ammoTierOf(ItemStack item) {
        if (item == null || item.getType() != Material.ARROW || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(ammoKey, PersistentDataType.STRING);
    }

    public boolean isAmmoFor(ItemStack item, AaTier tier) {
        if (item == null || item.getType() != Material.ARROW) {
            return false;
        }
        if (plugin.getConfig().getBoolean("air-defence-system.ammo.allow-plain-arrows", false)
                && ammoTierOf(item) == null) {
            return true;
        }
        return tier.id().equalsIgnoreCase(ammoTierOf(item));
    }

    private List<Component> parseLore(List<String> lines, Map<String, Object> placeholders) {
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        return lore;
    }

    public String tierIdOf(ItemStack item) {
        if (item == null || item.getType() != Material.DISPENSER || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(tierKey, PersistentDataType.STRING);
    }

}
