package ua.bobster.defence.aa;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;
import ua.bobster.defence.util.OwnerProtection;

import java.util.List;
import java.util.Map;

/**
 * Встановлення, демонтаж і огляд стаціонарних ППО.
 */
public class AaListener implements Listener {

    private final BobsterDefence plugin;
    private final AaManager manager;
    private final AaItem item;

    public AaListener(BobsterDefence plugin, AaManager manager, AaItem item) {
        this.plugin = plugin;
        this.manager = manager;
        this.item = item;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String tierId = item.tierIdOf(event.getItemInHand());
        if (tierId == null) {
            return;
        }
        AaTier tier = manager.tier(tierId);
        if (tier == null) {
            return;
        }
        AaLauncher launcher = manager.wrap(event.getBlock());
        launcher.tierId(tierId);
        launcher.owner(event.getPlayer().getUniqueId());
        manager.register(event.getBlock());
        manager.refreshVisuals(launcher);
        manager.sendMessage(event.getPlayer(), "aa-placed", Map.of(
                "name", MessageUtil.raw(tier.displayName()), "zone", tier.range() * 2));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        AaLauncher launcher = manager.launcherAt(event.getBlock());
        if (launcher == null) {
            return;
        }
        // Руками установку розбирає лише власник. Ворог має знищувати її ракетою чи дроном.
        if (!OwnerProtection.mayBreak(plugin, event.getPlayer(), launcher.owner(), "aa-not-owner")) {
            event.setCancelled(true);
            return;
        }
        AaTier tier = manager.tier(launcher.tierId());
        manager.removeVisuals(launcher);
        manager.unregister(event.getBlock());
        if (tier == null || event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        event.setDropItems(false);
        dropContents(event.getBlock());
        event.getBlock().getWorld().dropItemNaturally(
                event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D), item.createLauncher(tier, 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExploded(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExploded(event.blockList());
    }

    private void handleExploded(List<Block> blocks) {
        for (Block block : blocks) {
            AaLauncher launcher = manager.launcherAt(block);
            if (launcher == null) {
                continue;
            }
            // Знищена вибухом установка не повертається предметом: це втрата, а не демонтаж.
            manager.removeVisuals(launcher);
            manager.unregister(block);
        }
    }

    private void dropContents(Block block) {
        if (!(block.getState(false) instanceof Dispenser dispenser)) {
            return;
        }
        for (ItemStack content : dispenser.getInventory().getContents()) {
            if (content != null && !content.getType().isAir()) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5D, 0.5D, 0.5D), content);
            }
        }
        dispenser.getInventory().clear();
    }

    /**
     * Редстоун-імпульс не має вистрілювати боєкомплект: установка стріляє сама й тільки по цілях.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (manager.launcherAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        AaLauncher launcher = manager.launcherAt(event.getClickedBlock());
        if (launcher == null) {
            return;
        }
        // Присів — звичайний інвентар, щоб зарядити перехоплювачі.
        if (event.getPlayer().isSneaking()) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        AaTier tier = manager.tier(launcher.tierId());
        if (tier == null) {
            return;
        }
        String ownerName = launcher.owner() == null
                ? "—"
                : String.valueOf(plugin.getServer().getOfflinePlayer(launcher.owner()).getName());
        manager.sendMessage(player, "aa-info", Map.of(
                "name", MessageUtil.raw(tier.displayName()),
                "zone", tier.range() * 2,
                "range", tier.range(),
                "vertical", tier.verticalRange(),
                "targets", tier.maxTargets(),
                "cooldown", String.format("%.1f", tier.fireCooldown() / 20.0D),
                "ammo", launcher.ammoCount(tier),
                "owner", ownerName));
    }
}
