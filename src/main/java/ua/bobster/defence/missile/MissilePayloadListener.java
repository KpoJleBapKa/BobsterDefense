package ua.bobster.defence.missile;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;

public class MissilePayloadListener implements Listener {

    private final BobsterDefence plugin;

    public MissilePayloadListener(BobsterDefence plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPrepare(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || plugin.payloads().read(result).kind() != MissilePayload.Kind.POTION) {
            return;
        }
        ItemStack potion = null;
        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (ingredient == null || ingredient.getType() != Material.SPLASH_POTION) {
                continue;
            }
            if (potion != null && !potion.isSimilar(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
            potion = ingredient;
        }
        if (potion == null) {
            event.getInventory().setResult(null);
            return;
        }
        event.getInventory().setResult(plugin.payloads().write(result, MissilePayload.potion(potion)));
    }
}
