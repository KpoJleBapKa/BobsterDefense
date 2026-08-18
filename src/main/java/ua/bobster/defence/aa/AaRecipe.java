package ua.bobster.defence.aa;

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
 * Крафти установок ППО та боєприпасів-перехоплювачів.
 */
public class AaRecipe {

    private static final String LAUNCHER_PREFIX = "AA:";

    private final BobsterDefence plugin;
    private final AaManager manager;
    private final AaItem item;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public AaRecipe(BobsterDefence plugin, AaManager manager, AaItem item) {
        this.plugin = plugin;
        this.manager = manager;
        this.item = item;
    }

    public void register() {
        unregister();
        if (!plugin.getConfig().getBoolean("air-defence-system.enabled", true)
                || !plugin.getConfig().getBoolean("air-defence-system.recipes-enabled", true)) {
            return;
        }
        ConfigurationSection launchers = plugin.getConfig()
                .getConfigurationSection("air-defence-system.launcher");
        if (launchers != null) {
            for (AaTier tier : manager.tiers()) {
                ConfigurationSection section = launchers.getConfigurationSection(tier.id() + ".recipe");
                if (section != null) {
                    build("aa_" + tier.id(), section, item.createLauncher(tier, 1), 1);
                }
                // У кожної установки власна ракета — свій крафт і свій боєприпас.
                ConfigurationSection ammo = launchers.getConfigurationSection(tier.id() + ".ammo.recipe");
                if (ammo != null) {
                    int amount = Math.max(1, ammo.getInt("amount", 1));
                    build("aa_ammo_" + tier.id(), ammo, item.createAmmo(tier, amount), amount);
                }
            }
        }
        if (!registered.isEmpty()) {
            plugin.getLogger().info("AA: зареєстровано крафтів — " + registered.size());
        }
    }

    private void build(String id, ConfigurationSection section, org.bukkit.inventory.ItemStack result,
                       int amount) {
        List<String> shape = section.getStringList("shape");
        ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
        if (shape.isEmpty() || shape.size() > 3 || ingredients == null) {
            plugin.getLogger().warning("Некоректний крафт " + id + ", пропущено");
            return;
        }
        result.setAmount(amount);

        NamespacedKey key = new NamespacedKey(plugin, id);
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        try {
            recipe.shape(shape.toArray(new String[0]));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Форма крафту " + id + ": " + ex.getMessage());
            return;
        }

        for (String symbol : ingredients.getKeys(false)) {
            if (symbol.length() != 1) {
                plugin.getLogger().warning("Символ '" + symbol + "' у крафті " + id
                        + " має бути одним знаком");
                return;
            }
            String raw = ingredients.getString(symbol, "");
            RecipeChoice choice = resolve(raw);
            if (choice == null) {
                plugin.getLogger().warning("Невідомий інгредієнт '" + raw + "' у крафті " + id);
                return;
            }
            try {
                recipe.setIngredient(symbol.charAt(0), choice);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Символ '" + symbol + "' не використовується у формі " + id);
            }
        }
        Bukkit.addRecipe(recipe);
        registered.add(key);
    }

    private RecipeChoice resolve(String raw) {
        if (raw.toUpperCase(Locale.ROOT).startsWith(LAUNCHER_PREFIX)) {
            AaTier previous = manager.tier(raw.substring(LAUNCHER_PREFIX.length()).trim());
            return previous == null ? null : new RecipeChoice.ExactChoice(item.createLauncher(previous, 1));
        }
        Material material = Material.matchMaterial(raw);
        return material == null || !material.isItem() ? null : new RecipeChoice.MaterialChoice(material);
    }

    public void unregister() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }
}
