package ua.bobster.defence.strike;

import org.bukkit.block.Block;
import org.bukkit.block.Dropper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.OwnerProtection;

public class StrikeLauncherListener implements Listener {

    private final BobsterDefence plugin;
    private final StrikeLauncherManager manager;
    private final StrikeLauncherItem item;

    public StrikeLauncherListener(BobsterDefence plugin, StrikeLauncherManager manager, StrikeLauncherItem item) {
        this.plugin = plugin;
        this.manager = manager;
        this.item = item;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                    migrateChunk(chunk);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        StrikeLauncherItem.Kind kind = item.launcherKind(event.getItemInHand());
        if (kind != null) {
            manager.initialize(event.getBlock(), kind, event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        java.util.UUID owner = manager.owner(event.getBlock());
        if (owner != null && !OwnerProtection.mayBreak(plugin, event.getPlayer(), owner, "ballistic-not-owner")) {
            event.setCancelled(true);
            return;
        }
        StrikeLauncherItem.Kind kind = manager.kind(event.getBlock());
        if (kind != null) {
            if (kind == StrikeLauncherItem.Kind.DRONE) {
                manager.unregisterDroneLauncher(event.getBlock());
            }
            event.setDropItems(false);
            if (event.getBlock().getState() instanceof Dropper dropper) {
                for (ItemStack stack : dropper.getInventory().getContents()) {
                    if (stack != null && !stack.getType().isAir()) {
                        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), stack.clone());
                    }
                }
                dropper.getInventory().clear();
            }
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item.createLauncher(kind, 1));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        unregisterExploded(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        unregisterExploded(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        migrateChunk(event.getChunk());
    }

    private void unregisterExploded(java.util.List<Block> blocks) {
        for (Block block : blocks) {
            if (manager.kind(block) == StrikeLauncherItem.Kind.DRONE) {
                manager.unregisterDroneLauncher(block);
            }
        }
    }

    private void migrateChunk(org.bukkit.Chunk chunk) {
        for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
            Block block = state.getBlock();
            if (manager.kind(block) != StrikeLauncherItem.Kind.DRONE) {
                continue;
            }
            java.util.UUID owner = manager.owner(block);
            if (owner == null || plugin.strategicStates() != null && plugin.strategicStates().byId(owner) != null) {
                continue;
            }
            manager.registerMigratedDroneLauncher(block, owner);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        StrikeLauncherItem.Kind kind = manager.kind(block);
        if (kind == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!OwnerProtection.mayUse(plugin, player, manager.owner(block), "ballistic-not-owner")) {
            event.setCancelled(true);
            return;
        }
        if (player.isSneaking()) {
            return;
        }
        event.setCancelled(true);
        manager.select(player, block);
        new StrikeLauncherGui(manager, block).open(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof StrikeLauncherGui gui)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= 9) {
            return;
        }
        if (!OwnerProtection.mayUse(plugin, player, manager.owner(gui.block()), "ballistic-not-owner")) {
            player.closeInventory();
            return;
        }
        if (event.getRawSlot() == StrikeLauncherGui.SLOT_REDSTONE) {
            manager.redstone(gui.block(), !manager.redstone(gui.block()));
            gui.refresh();
        } else if (event.getRawSlot() == StrikeLauncherGui.SLOT_AMMO) {
            player.closeInventory();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (gui.block().getState(false) instanceof Dropper dropper) {
                    player.openInventory(dropper.getInventory());
                }
            });
        } else if (event.getRawSlot() == StrikeLauncherGui.SLOT_LAUNCH) {
            manager.launchFromPanel(player, gui.block());
            if (player.getOpenInventory().getTopInventory().getHolder() == gui) {
                gui.refresh();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (manager.kind(event.getBlock()) == null) {
            return;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> manager.handleRedstone(event.getBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getOldCurrent() > 0 || event.getNewCurrent() <= 0) {
            return;
        }
        java.util.ArrayList<Block> candidates = new java.util.ArrayList<>();
        candidates.add(event.getBlock());
        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN, org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            candidates.add(event.getBlock().getRelative(face));
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Block block : candidates) {
                if (manager.kind(block) != null && block.isBlockPowered()) {
                    manager.handleRedstone(block);
                }
            }
        });
    }
}
