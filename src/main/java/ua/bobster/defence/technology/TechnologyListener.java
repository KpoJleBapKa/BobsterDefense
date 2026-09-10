package ua.bobster.defence.technology;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;

import java.util.Map;

public class TechnologyListener implements Listener {

    private final BobsterDefence plugin;
    private final TechnologyManager technologies;

    public TechnologyListener(BobsterDefence plugin, TechnologyManager technologies) {
        this.plugin = plugin;
        this.technologies = technologies;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraftPrepare(PrepareItemCraftEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        String required = technologies.requiredFor(result);
        if (required != null && !technologies.has(player, required)) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (technologies.requiredFor(event.getResult()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBookUse(PlayerInteractEvent event) {
        if (!technologies.enabled() || event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        ItemStack held = event.getItem();
        String technologyId = technologies.bookTechnology(held);
        if (technologyId == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (technologies.has(player, technologyId)) {
            player.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message("technology-known"), Map.of("name", technologies.displayName(technologyId))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8F, 0.8F);
            return;
        }
        ItemStack display = held.clone();
        display.setAmount(1);
        if (!technologies.unlock(player, technologyId)) {
            return;
        }
        consume(player, held);
        player.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message("technology-unlocked"), Map.of("name", technologies.displayName(technologyId))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
        plugin.getServer().getScheduler().runTask(plugin, () -> player.openBook(display));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> technologies.synchronizeRecipes(event.getPlayer()));
    }

    private void consume(Player player, ItemStack held) {
        if (held.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItemInMainHand(held);
    }
}
