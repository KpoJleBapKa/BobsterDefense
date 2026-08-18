package ua.bobster.defence.ballistic;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import ua.bobster.defence.BobsterDefence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Крафти установок. Апгрейди (MK-II і далі) використовують попередню установку як інгредієнт —
 * через {@link RecipeChoice.ExactChoice}, який звіряє предмет разом із його PDC, тож підсунути
 * звичайний диспенсер із таким самим ім'ям не вийде.
 */
public class BallisticRecipe {

    private static final String LAUNCHER_PREFIX = "LAUNCHER:";
    private static final String ROCKET_PREFIX = "ROCKET:";

    private final BobsterDefence plugin;
    private final BallisticManager manager;
    private final BallisticItem item;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public BallisticRecipe(BobsterDefence plugin, BallisticManager manager, BallisticItem item) {
        this.plugin = plugin;
        this.manager = manager;
        this.item = item;
    }

    public void register() {
        unregister();
        if (!plugin.getConfig().getBoolean("ballistic.enabled", true)
                || !plugin.getConfig().getBoolean("ballistic.recipes-enabled", true)) {
            return;
        }
        ConfigurationSection launchers = plugin.getConfig().getConfigurationSection("ballistic.launcher");
        if (launchers == null) {
            return;
        }
        for (LauncherTier tier : manager.tiers()) {
            ConfigurationSection recipeSection = launchers.getConfigurationSection(tier.id() + ".recipe");
            if (recipeSection == null) {
                continue;
            }
            registerOne(tier, recipeSection);
        }
        if (!registered.isEmpty()) {
            plugin.getLogger().info("Ballistic: зареєстровано крафтів — " + registered.size());
        }
    }

    private void registerOne(LauncherTier tier, ConfigurationSection section) {
        List<String> shape = section.getStringList("shape");
        ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
        if (shape.isEmpty() || shape.size() > 3 || ingredients == null) {
            plugin.getLogger().warning("Некоректний крафт для " + tier.id() + ", пропущено");
            return;
        }

        NamespacedKey key = new NamespacedKey(plugin, "ballistic_" + tier.id());
        ShapedRecipe recipe = new ShapedRecipe(key, item.create(tier, 1));
        try {
            recipe.shape(shape.toArray(new String[0]));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Форма крафту " + tier.id() + ": " + ex.getMessage());
            return;
        }

        for (String symbol : ingredients.getKeys(false)) {
            if (symbol.length() != 1) {
                plugin.getLogger().warning("Символ '" + symbol + "' у крафті " + tier.id()
                        + " має бути одним знаком");
                return;
            }
            String raw = ingredients.getString(symbol, "");
            RecipeChoice choice = resolveChoice(raw, tier);
            if (choice == null) {
                plugin.getLogger().warning("Невідомий інгредієнт '" + raw + "' у крафті " + tier.id());
                return;
            }
            try {
                recipe.setIngredient(symbol.charAt(0), choice);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Символ '" + symbol + "' не використовується у формі "
                        + tier.id());
            }
        }

        Bukkit.addRecipe(recipe);
        registered.add(key);
    }

    private RecipeChoice resolveChoice(String raw, LauncherTier tier) {
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.startsWith(LAUNCHER_PREFIX)) {
            LauncherTier other = requireTier(raw.substring(LAUNCHER_PREFIX.length()), tier);
            return other == null ? null : new RecipeChoice.ExactChoice(item.create(other, 1));
        }
        // Важчі ракети збираються з легших, тому ракета теж буває інгредієнтом.
        if (upper.startsWith(ROCKET_PREFIX)) {
            LauncherTier other = requireTier(raw.substring(ROCKET_PREFIX.length()), tier);
            return other == null ? null : new RecipeChoice.ExactChoice(item.createRocket(other, 1));
        }
        Material material = Material.matchMaterial(raw);
        return material == null || !material.isItem() ? null : new RecipeChoice.MaterialChoice(material);
    }

    private LauncherTier requireTier(String id, LauncherTier owner) {
        LauncherTier found = manager.tier(id.trim());
        if (found == null) {
            plugin.getLogger().warning("Крафт " + owner.id() + " посилається на невідомий рівень '"
                    + id.trim() + "'");
        }
        return found;
    }

    public void unregister() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }
}
