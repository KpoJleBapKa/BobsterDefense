package ua.bobster.defence.strike;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.RecipeChoice;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.missile.MissilePayload;

import java.util.ArrayList;
import java.util.List;

public class StrikeLauncherRecipe {

    private final BobsterDefence plugin;
    private final StrikeLauncherManager manager;
    private final List<NamespacedKey> keys = new ArrayList<>();

    public StrikeLauncherRecipe(BobsterDefence plugin, StrikeLauncherManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void register() {
        unregister();
        ShapedRecipe drone = recipe("drone_launch_system", manager.item().createLauncher(StrikeLauncherItem.Kind.DRONE, 1), "I I", "IDI", "RBR");
        drone.setIngredient('I', Material.IRON_BLOCK);
        drone.setIngredient('D', Material.DROPPER);
        drone.setIngredient('R', Material.REDSTONE);
        drone.setIngredient('B', Material.REDSTONE_BLOCK);
        add(drone);

        ShapedRecipe guided = recipe("guided_launch_system", manager.item().createLauncher(StrikeLauncherItem.Kind.GUIDED, 1), "ITI", "IDI", "IBI");
        guided.setIngredient('I', Material.IRON_BLOCK);
        guided.setIngredient('T', Material.IRON_TRAPDOOR);
        guided.setIngredient('D', Material.DROPPER);
        guided.setIngredient('B', Material.REDSTONE_BLOCK);
        add(guided);

        GuidedMissileType corsair = manager.type("corsair");
        if (corsair != null) {
            ShapedRecipe recipe = recipe("guided_missile_corsair", manager.item().createMissile(corsair, 1), " I ", "ITI", "CRC");
            recipe.setIngredient('I', Material.IRON_INGOT);
            recipe.setIngredient('T', Material.TNT);
            recipe.setIngredient('C', Material.COPPER_INGOT);
            recipe.setIngredient('R', Material.REDSTONE);
            add(recipe);
            ShapedRecipe empty = recipe("guided_missile_corsair_empty", manager.item().createMissile(corsair, 1, MissilePayload.empty()), " I ", "I I", "CRC");
            empty.setIngredient('I', Material.IRON_INGOT);
            empty.setIngredient('C', Material.COPPER_INGOT);
            empty.setIngredient('R', Material.REDSTONE);
            add(empty);
            addLoading(corsair);
        }

        GuidedMissileType barrier = manager.type("barrier");
        if (barrier != null) {
            ShapedRecipe recipe = recipe("guided_missile_barrier", manager.item().createMissile(barrier, 1), " B ", "BTB", "CRC");
            recipe.setIngredient('B', Material.IRON_BLOCK);
            recipe.setIngredient('T', Material.TNT);
            recipe.setIngredient('C', Material.COPPER_BLOCK);
            recipe.setIngredient('R', Material.REDSTONE_BLOCK);
            add(recipe);
            ShapedRecipe empty = recipe("guided_missile_barrier_empty", manager.item().createMissile(barrier, 1, MissilePayload.empty()), " B ", "B B", "CRC");
            empty.setIngredient('B', Material.IRON_BLOCK);
            empty.setIngredient('C', Material.COPPER_BLOCK);
            empty.setIngredient('R', Material.REDSTONE_BLOCK);
            add(empty);
            addLoading(barrier);
        }
    }

    public void unregister() {
        for (NamespacedKey key : keys) {
            Bukkit.removeRecipe(key);
        }
        keys.clear();
    }

    private ShapedRecipe recipe(String id, org.bukkit.inventory.ItemStack result, String first, String second, String third) {
        NamespacedKey key = new NamespacedKey(plugin, id);
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(first, second, third);
        keys.add(key);
        return recipe;
    }

    private void add(ShapedRecipe recipe) {
        Bukkit.addRecipe(recipe);
    }

    private void addLoading(GuidedMissileType type) {
        org.bukkit.inventory.ItemStack empty = manager.item().createMissile(type, 1, MissilePayload.empty());
        NamespacedKey tntKey = new NamespacedKey(plugin, "guided_missile_" + type.id() + "_load_tnt");
        ShapelessRecipe tnt = new ShapelessRecipe(tntKey, manager.item().createMissile(type, 1, MissilePayload.explosive()));
        tnt.addIngredient(new RecipeChoice.ExactChoice(empty));
        tnt.addIngredient(Material.TNT);
        Bukkit.addRecipe(tnt);
        keys.add(tntKey);

        NamespacedKey potionKey = new NamespacedKey(plugin, "guided_missile_" + type.id() + "_load_potion");
        ShapelessRecipe potion = new ShapelessRecipe(potionKey, manager.item().createMissile(type, 1, MissilePayload.potion(new org.bukkit.inventory.ItemStack(Material.SPLASH_POTION))));
        potion.addIngredient(new RecipeChoice.ExactChoice(empty));
        potion.addIngredient(Material.SPLASH_POTION);
        Bukkit.addRecipe(potion);
        keys.add(potionKey);
    }
}
