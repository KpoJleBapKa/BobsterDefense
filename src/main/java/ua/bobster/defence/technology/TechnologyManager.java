package ua.bobster.defence.technology;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.aa.AaTier;
import ua.bobster.defence.ballistic.LauncherTier;
import ua.bobster.defence.strike.GuidedMissileType;
import ua.bobster.defence.strike.StrikeLauncherItem;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TechnologyManager {

    public static final String BALLISTIC = "ballistic";
    public static final String AA = "aa";
    public static final String CRUISE = "cruise";

    private final BobsterDefence plugin;
    private final NamespacedKey bookKey;
    private final NamespacedKey unlockedKey;

    public TechnologyManager(BobsterDefence plugin) {
        this.plugin = plugin;
        this.bookKey = new NamespacedKey(plugin, "technology_book");
        this.unlockedKey = new NamespacedKey(plugin, "technologies_unlocked");
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("technologies.enabled", true);
    }

    public String id(String category, String tier) {
        return category.toLowerCase(Locale.ROOT) + ":" + tier.toLowerCase(Locale.ROOT);
    }

    public boolean valid(String category, String tier) {
        if (category.equalsIgnoreCase(BALLISTIC)) {
            return plugin.ballistic().tier(tier) != null;
        }
        if (category.equalsIgnoreCase(AA)) {
            return plugin.aa().tier(tier) != null;
        }
        if (category.equalsIgnoreCase(CRUISE)) {
            return plugin.strike().type(tier) != null;
        }
        return false;
    }

    public List<String> ids() {
        List<String> result = new ArrayList<>();
        for (LauncherTier tier : plugin.ballistic().tiers()) {
            result.add(id(BALLISTIC, tier.id()));
        }
        for (AaTier tier : plugin.aa().tiers()) {
            result.add(id(AA, tier.id()));
        }
        for (GuidedMissileType type : plugin.strike().types()) {
            result.add(id(CRUISE, type.id()));
        }
        return result;
    }

    public ItemStack createBook(String category, String tierId, int amount) {
        String technologyId = id(category, tierId);
        if (!valid(category, tierId)) {
            return null;
        }
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(
                "technologies.books." + category.toLowerCase(Locale.ROOT) + "." + tierId.toLowerCase(Locale.ROOT));
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK, Math.max(1, Math.min(16, amount)));
        BookMeta meta = (BookMeta) book.getItemMeta();
        Map<String, Object> placeholders = placeholders(category, tierId);
        String title = section == null ? displayName(technologyId) : section.getString("title", displayName(technologyId));
        String author = section == null ? "Bobster Research Division" : section.getString("author", "Bobster Research Division");
        String name = section == null ? "<gold><bold>Технологія: {name}</bold></gold>" : section.getString("name", "<gold><bold>Технологія: {name}</bold></gold>");
        List<String> lore = section == null ? List.of("<gray>ПКМ — вивчити технологію", "<red>Книга буде поглинута") : section.getStringList("lore");
        List<String> pages = section == null ? List.of("<gold><bold>{name}</bold></gold>\n\n<gray>Технічний пакет відкриває виробництво установки та її боєприпасів.</gray>") : section.getStringList("pages");

        meta.title(MessageUtil.parse(title, placeholders));
        meta.author(MessageUtil.parse(author, placeholders));
        meta.displayName(MessageUtil.parse(name, placeholders).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> MessageUtil.parse(line, placeholders).decoration(TextDecoration.ITALIC, false)).toList());
        meta.addPages(pages.stream().map(page -> MessageUtil.parse(page, placeholders)).toArray(Component[]::new));
        meta.setGeneration(BookMeta.Generation.ORIGINAL);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, technologyId);
        book.setItemMeta(meta);
        return book;
    }

    public String bookTechnology(ItemStack item) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
    }

    public boolean has(Player player, String technologyId) {
        return unlocked(player).contains(technologyId.toLowerCase(Locale.ROOT));
    }

    public boolean unlock(Player player, String technologyId) {
        Set<String> unlocked = unlocked(player);
        if (!unlocked.add(technologyId.toLowerCase(Locale.ROOT))) {
            return false;
        }
        player.getPersistentDataContainer().set(unlockedKey, PersistentDataType.STRING, String.join(",", unlocked));
        discover(player, technologyId);
        return true;
    }

    public Set<String> unlocked(Player player) {
        String raw = player.getPersistentDataContainer().get(unlockedKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(raw.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(value -> value.toLowerCase(Locale.ROOT)).forEach(result::add);
        return result;
    }

    public String requiredFor(ItemStack result) {
        if (!enabled() || result == null || result.getType().isAir()) {
            return null;
        }
        String tier = plugin.ballistic().item().tierIdOf(result);
        if (tier == null) {
            tier = plugin.ballistic().item().rocketTierOf(result);
        }
        if (tier != null) {
            return id(BALLISTIC, tier);
        }
        tier = plugin.aa().item().tierIdOf(result);
        if (tier == null) {
            tier = plugin.aa().item().ammoTierOf(result);
        }
        if (tier != null) {
            return id(AA, tier);
        }
        StrikeLauncherItem.Kind kind = plugin.strike().item().launcherKind(result);
        if (kind == StrikeLauncherItem.Kind.GUIDED) {
            return id(CRUISE, "corsair");
        }
        tier = plugin.strike().item().missileType(result);
        return tier == null ? null : id(CRUISE, tier);
    }

    public String displayName(String technologyId) {
        String[] parts = technologyId.split(":", 2);
        if (parts.length != 2) {
            return technologyId;
        }
        if (parts[0].equals(BALLISTIC)) {
            LauncherTier tier = plugin.ballistic().tier(parts[1]);
            return tier == null ? technologyId : MessageUtil.plain(MessageUtil.parse(tier.displayName()));
        }
        if (parts[0].equals(CRUISE)) {
            GuidedMissileType type = plugin.strike().type(parts[1]);
            return type == null ? technologyId : MessageUtil.plain(MessageUtil.parse(type.displayName()));
        }
        AaTier tier = plugin.aa().tier(parts[1]);
        return tier == null ? technologyId : MessageUtil.plain(MessageUtil.parse(tier.displayName()));
    }

    public void synchronizeRecipes(Player player) {
        for (String technologyId : ids()) {
            if (!enabled() || has(player, technologyId)) {
                discover(player, technologyId);
            } else {
                undiscover(player, technologyId);
            }
        }
    }

    private Map<String, Object> placeholders(String category, String tierId) {
        String technologyId = id(category, tierId);
        String categoryName = category.equalsIgnoreCase(AA) ? "ППО" : category.equalsIgnoreCase(CRUISE) ? "Крилаті ракети" : "Балістика";
        return Map.of("name", displayName(technologyId), "tier", tierId.toUpperCase(Locale.ROOT), "category", categoryName);
    }

    private void discover(Player player, String technologyId) {
        for (NamespacedKey key : recipeKeys(technologyId)) {
            player.discoverRecipe(key);
        }
    }

    private void undiscover(Player player, String technologyId) {
        for (NamespacedKey key : recipeKeys(technologyId)) {
            player.undiscoverRecipe(key);
        }
    }

    private List<NamespacedKey> recipeKeys(String technologyId) {
        String[] parts = technologyId.split(":", 2);
        if (parts.length != 2) {
            return List.of();
        }
        if (parts[0].equals(BALLISTIC)) {
            return List.of(new NamespacedKey(plugin, "ballistic_" + parts[1]), new NamespacedKey(plugin, "ballistic_rocket_" + parts[1]), new NamespacedKey(plugin, "ballistic_rocket_" + parts[1] + "_empty"), new NamespacedKey(plugin, "ballistic_rocket_" + parts[1] + "_load_tnt"), new NamespacedKey(plugin, "ballistic_rocket_" + parts[1] + "_load_potion"));
        }
        if (parts[0].equals(CRUISE)) {
            List<NamespacedKey> missile = List.of(new NamespacedKey(plugin, "guided_missile_" + parts[1]), new NamespacedKey(plugin, "guided_missile_" + parts[1] + "_empty"), new NamespacedKey(plugin, "guided_missile_" + parts[1] + "_load_tnt"), new NamespacedKey(plugin, "guided_missile_" + parts[1] + "_load_potion"));
            if (!parts[1].equals("corsair")) {
                return missile;
            }
            List<NamespacedKey> result = new java.util.ArrayList<>();
            result.add(new NamespacedKey(plugin, "guided_launch_system"));
            result.addAll(missile);
            return result;
        }
        return List.of(new NamespacedKey(plugin, "aa_" + parts[1]), new NamespacedKey(plugin, "aa_ammo_" + parts[1]));
    }
}
