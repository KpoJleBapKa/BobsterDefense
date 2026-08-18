package ua.bobster.defence.drone;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ShapedRecipe;
import ua.bobster.defence.BobsterDefence;

import java.util.ArrayList;
import java.util.List;

/**
 * Крафти всіх типів дронів. Форми й інгредієнти беруться з config.yml.
 */
public class DroneRecipe {

    private final BobsterDefence plugin;
    private final DroneManager manager;
    private final DroneItem droneItem;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public DroneRecipe(BobsterDefence plugin, DroneManager manager, DroneItem droneItem) {
        this.plugin = plugin;
        this.manager = manager;
        this.droneItem = droneItem;
    }

    public void register() {
        unregister();
        if (!plugin.getConfig().getBoolean("fpv-drone.enabled", true)
                || !plugin.getConfig().getBoolean("fpv-drone.recipes-enabled", true)) {
            return;
        }
        ConfigurationSection drones = plugin.getConfig().getConfigurationSection("fpv-drone.drones");
        if (drones == null) {
            return;
        }
        for (DroneType type : manager.types()) {
            ConfigurationSection section = drones.getConfigurationSection(type.id() + ".recipe");
            if (section != null) {
                build(type, section);
            }
        }
        if (!registered.isEmpty()) {
            plugin.getLogger().info("FPV Drone: зареєстровано крафтів — " + registered.size());
        }
    }

    private void build(DroneType type, ConfigurationSection section) {
        List<String> shape = section.getStringList("shape");
        ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
        if (shape.isEmpty() || shape.size() > 3 || ingredients == null) {
            plugin.getLogger().warning("Некоректний крафт дрона " + type.id() + ", пропущено");
            return;
        }
        int amount = Math.max(1, section.getInt("amount", 1));

        NamespacedKey key = new NamespacedKey(plugin, "fpv_drone_" + type.id());
        ShapedRecipe recipe = new ShapedRecipe(key, droneItem.create(type, amount));
        try {
            recipe.shape(shape.toArray(new String[0]));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Форма крафту дрона " + type.id() + ": " + ex.getMessage());
            return;
        }

        for (String symbol : ingredients.getKeys(false)) {
            if (symbol.length() != 1) {
                plugin.getLogger().warning("Символ '" + symbol + "' у крафті дрона " + type.id()
                        + " має бути одним знаком");
                return;
            }
            Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
            if (material == null || !material.isItem()) {
                plugin.getLogger().warning("Невідомий інгредієнт '" + ingredients.getString(symbol)
                        + "' у крафті дрона " + type.id());
                return;
            }
            try {
                recipe.setIngredient(symbol.charAt(0), material);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Символ '" + symbol + "' не використовується у формі "
                        + type.id());
            }
        }
        Bukkit.addRecipe(recipe);
        registered.add(key);
    }

    public void unregister() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
    }
}
