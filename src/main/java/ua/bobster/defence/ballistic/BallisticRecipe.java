package ua.bobster.defence.ballistic;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.missile.MissilePayload;

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
            ConfigurationSection rocketRecipe = launchers.getConfigurationSection(tier.id() + ".rocket.recipe");
            if (rocketRecipe != null) {
                registerOne(tier, rocketRecipe, true);
            }
            ConfigurationSection recipeSection = launchers.getConfigurationSection(tier.id() + ".recipe");
            if (recipeSection != null) {
                registerOne(tier, recipeSection, false);
            }
        }
        if (!registered.isEmpty()) {
            plugin.getLogger().info("Ballistic: зареєстровано крафтів — " + registered.size());
        }
    }

    private void registerOne(LauncherTier tier, ConfigurationSection section, boolean rocket) {
        List<String> shape = section.getStringList("shape");
        ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
        if (shape.isEmpty() || shape.size() > 3 || ingredients == null) {
            plugin.getLogger().warning("Некоректний крафт для " + tier.id() + ", пропущено");
            return;
        }

        String keyName = rocket ? "ballistic_rocket_" + tier.id() : "ballistic_" + tier.id();
        int amount = Math.max(1, Math.min(64, section.getInt("amount", 1)));
        NamespacedKey key = new NamespacedKey(plugin, keyName);
        ShapedRecipe recipe = new ShapedRecipe(key, rocket ? item.createRocket(tier, amount) : item.create(tier, amount));
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
        if (rocket) {
            registerEmptyAndLoading(tier, shape, ingredients, amount);
        }
    }

    private RecipeChoice resolveChoice(String raw, LauncherTier tier) {
        return resolveChoice(raw, tier, MissilePayload.explosive());
    }

    private RecipeChoice resolveChoice(String raw, LauncherTier tier, MissilePayload payload) {
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.startsWith(LAUNCHER_PREFIX)) {
            LauncherTier other = requireTier(raw.substring(LAUNCHER_PREFIX.length()), tier);
            return other == null ? null : new RecipeChoice.ExactChoice(item.create(other, 1));
        }
        // Важчі ракети збираються з легших, тому ракета теж буває інгредієнтом.
        if (upper.startsWith(ROCKET_PREFIX)) {
            LauncherTier other = requireTier(raw.substring(ROCKET_PREFIX.length()), tier);
            if (other == null) {
                return null;
            }
            return payload.kind() == MissilePayload.Kind.EXPLOSIVE ? new RecipeChoice.ExactChoice(item.createRocket(other, 1, payload), item.createLegacyRocket(other, 1)) : new RecipeChoice.ExactChoice(item.createRocket(other, 1, payload));
        }
        Material material = Material.matchMaterial(raw);
        return material == null || !material.isItem() ? null : new RecipeChoice.MaterialChoice(material);
    }

    private void registerEmptyAndLoading(LauncherTier tier, List<String> sourceShape, ConfigurationSection ingredients, int amount) {
        List<String> shape = emptyShape(sourceShape, ingredients);
        if (shape.isEmpty()) {
            plugin.getLogger().warning("Порожній крафт ракети " + tier.id() + " не має корпусу");
            return;
        }
        MissilePayload empty = MissilePayload.empty();
        NamespacedKey emptyKey = new NamespacedKey(plugin, "ballistic_rocket_" + tier.id() + "_empty");
        ShapedRecipe emptyRecipe = new ShapedRecipe(emptyKey, item.createRocket(tier, amount, empty));
        emptyRecipe.shape(shape.toArray(new String[0]));
        for (String symbol : ingredients.getKeys(false)) {
            char key = symbol.charAt(0);
            String raw = ingredients.getString(symbol, "");
            if (!uses(shape, key) || Material.matchMaterial(raw) == Material.TNT) {
                continue;
            }
            RecipeChoice choice = resolveChoice(raw, tier, empty);
            if (choice != null) {
                emptyRecipe.setIngredient(key, choice);
            }
        }
        Bukkit.addRecipe(emptyRecipe);
        registered.add(emptyKey);

        ItemStackPair pair = loadingResults(tier);
        NamespacedKey tntKey = new NamespacedKey(plugin, "ballistic_rocket_" + tier.id() + "_load_tnt");
        ShapelessRecipe tnt = new ShapelessRecipe(tntKey, pair.explosive());
        tnt.addIngredient(new RecipeChoice.ExactChoice(pair.empty()));
        tnt.addIngredient(Material.TNT);
        Bukkit.addRecipe(tnt);
        registered.add(tntKey);

        NamespacedKey potionKey = new NamespacedKey(plugin, "ballistic_rocket_" + tier.id() + "_load_potion");
        ShapelessRecipe potion = new ShapelessRecipe(potionKey, pair.potion());
        potion.addIngredient(new RecipeChoice.ExactChoice(pair.empty()));
        potion.addIngredient(Material.SPLASH_POTION);
        Bukkit.addRecipe(potion);
        registered.add(potionKey);
    }

    private ItemStackPair loadingResults(LauncherTier tier) {
        org.bukkit.inventory.ItemStack empty = item.createRocket(tier, 1, MissilePayload.empty());
        org.bukkit.inventory.ItemStack explosive = item.createRocket(tier, 1, MissilePayload.explosive());
        org.bukkit.inventory.ItemStack potion = item.createRocket(tier, 1, MissilePayload.potion(new org.bukkit.inventory.ItemStack(Material.SPLASH_POTION)));
        return new ItemStackPair(empty, explosive, potion);
    }

    private List<String> emptyShape(List<String> source, ConfigurationSection ingredients) {
        List<String> result = new ArrayList<>();
        for (String row : source) {
            StringBuilder changed = new StringBuilder(row);
            for (int index = 0; index < changed.length(); index++) {
                String raw = ingredients.getString(String.valueOf(changed.charAt(index)), "");
                if (Material.matchMaterial(raw) == Material.TNT) {
                    changed.setCharAt(index, ' ');
                }
            }
            result.add(changed.toString());
        }
        while (!result.isEmpty() && result.getFirst().isBlank()) {
            result.removeFirst();
        }
        while (!result.isEmpty() && result.getLast().isBlank()) {
            result.removeLast();
        }
        return result;
    }

    private boolean uses(List<String> shape, char symbol) {
        return shape.stream().anyMatch(row -> row.indexOf(symbol) >= 0);
    }

    private record ItemStackPair(org.bukkit.inventory.ItemStack empty, org.bukkit.inventory.ItemStack explosive, org.bukkit.inventory.ItemStack potion) {
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
