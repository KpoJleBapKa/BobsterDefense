package ua.bobster.defence.ballistic;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;
import ua.bobster.defence.util.OwnerProtection;

import java.util.List;
import java.util.Map;

/**
 * Установка та керування пусковими установками, а також наведення по карті в рамці.
 */
public class BallisticListener implements Listener {

    private final BobsterDefence plugin;
    private final BallisticManager manager;
    private final BallisticItem item;

    public BallisticListener(BobsterDefence plugin, BallisticManager manager, BallisticItem item) {
        this.plugin = plugin;
        this.manager = manager;
        this.item = item;
    }

    // ─────────────────────── установка та демонтаж ───────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String tierId = item.tierIdOf(event.getItemInHand());
        if (tierId == null) {
            return;
        }
        LauncherTier tier = manager.tier(tierId);
        if (tier == null) {
            return;
        }
        BallisticLauncher launcher = manager.wrap(event.getBlock());
        launcher.tierId(tierId);
        launcher.owner(event.getPlayer().getUniqueId());
        manager.refreshDisplay(launcher);
        manager.sendMessage(event.getPlayer(), "ballistic-placed", Map.of("name", MessageUtil.raw(tier.displayName())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        BallisticLauncher launcher = manager.launcherAt(event.getBlock());
        if (launcher == null) {
            return;
        }
        if (!OwnerProtection.mayBreak(plugin, event.getPlayer(), launcher.owner(), "ballistic-not-owner")) {
            event.setCancelled(true);
            return;
        }
        LauncherTier tier = manager.tier(launcher.tierId());
        manager.removeDisplay(launcher);
        if (tier == null || event.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE) {
            return;
        }
        // Дефолтний дроп — звичайний диспенсер, а нам треба повернути саме установку.
        event.setDropItems(false);
        dropContents(event.getBlock());
        event.getBlock().getWorld().dropItemNaturally(
                event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D), item.create(tier, 1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplodedLaunchers(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplodedLaunchers(event.blockList());
    }

    private void handleExplodedLaunchers(List<Block> blocks) {
        for (Block block : blocks) {
            BallisticLauncher launcher = manager.launcherAt(block);
            if (launcher == null) {
                continue;
            }
            // Вибух знищує установку остаточно — предметом вона не повертається.
            manager.removeDisplay(launcher);
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
     * Редстоун не має викидати боєкомплект: TNT усередині — це бойова частина, а не вміст диспенсера.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(org.bukkit.event.block.BlockDispenseEvent event) {
        if (manager.launcherAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    // ────────────────────────── панель керування ──────────────────────────

    /**
     * Клік по блоку в режимі розвідки. Spectator не завжди доносить клік до сервера,
     * тому дублює його команда /ballistic mark — вона працює завжди.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReconSwing(PlayerAnimationEvent event) {
        if (manager.recon().isActive(event.getPlayer())) {
            manager.recon().markTarget(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (manager.recon().isActive(event.getPlayer())) {
            event.setCancelled(true);
            manager.recon().markTarget(event.getPlayer());
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        BallisticLauncher launcher = manager.launcherAt(event.getClickedBlock());
        if (launcher == null) {
            return;
        }
        Player player = event.getPlayer();

        // Присів — відкриваємо звичайний інвентар диспенсера, щоб зарядити TNT.
        if (player.isSneaking()) {
            return;
        }
        event.setCancelled(true);

        if (!player.hasPermission("bobsterdefence.ballistic.use")) {
            manager.sendMessage(player, "no-permission", Map.of());
            return;
        }
        manager.select(player, event.getClickedBlock());
        new BallisticGui(plugin, manager, event.getClickedBlock()).open(player);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BallisticGui gui)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Клік по власному інвентарю гравця нас не цікавить. Порівнюємо саме raw-слот:
        // getSlot() для нижнього інвентаря повертає індекс усередині нього й може збігтися
        // з номером нашої кнопки.
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= gui.getInventory().getSize()) {
            return;
        }

        BallisticLauncher launcher = manager.launcherAt(gui.launcherBlock());
        LauncherTier tier = launcher == null ? null : manager.tier(launcher.tierId());
        if (tier == null) {
            player.closeInventory();
            manager.sendMessage(player, "ballistic-gone", Map.of());
            return;
        }

        switch (slot) {
            case BallisticGui.SLOT_AMMO -> later(player, () -> {
                if (gui.launcherBlock().getState(false) instanceof Dispenser dispenser) {
                    player.openInventory(dispenser.getInventory());
                }
            });
            // Будь-який клік піднімає розвідника. Розділяти ЛКМ і ПКМ виявилося поганою ідеєю:
            // легко влучити не тією кнопкою й отримати підказку замість вильоту.
            case BallisticGui.SLOT_TARGET -> later(player, () -> {
                manager.sendMessage(player, "ballistic-target-help", Map.of());
                manager.recon().launch(player, launcher, tier);
            });
            case BallisticGui.SLOT_FIRE -> {
                String error = manager.validate(launcher, tier);
                if (error != null) {
                    manager.sendMessage(player, error, Map.of(
                            "range", tier.range(),
                            "need", tier.ammo(),
                            "cooldown", tier.cooldownSeconds()));
                    return;
                }
                later(player, () -> manager.launch(player, launcher, tier));
            }
            default -> gui.refresh();
        }
    }

    /**
     * Закриває панель і виконує дію вже наступного тіку.
     * <p>
     * Це не косметика: зміна режиму гри й телепорт просто всередині обробника кліку
     * втрачаються — клієнт у цей момент ще закриває вікно контейнера. Саме через це
     * розвідник «не вилітав»: панель зникала, а далі не відбувалося нічого.
     */
    private void later(Player player, Runnable action) {
        player.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    // ─────────────────────── наведення по карті в рамці ───────────────────────

    /**
     * Присів + ПКМ по карті в рамці = ціль для вибраної установки.
     * Без присідання рамка працює як завжди (поворот предмета).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMapClick(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame) || !event.getPlayer().isSneaking()) {
            return;
        }
        if (frame.getItem().getType() != Material.FILLED_MAP) {
            return;
        }
        Player player = event.getPlayer();
        Block selected = manager.selected(player);
        BallisticLauncher launcher = selected == null ? null : manager.launcherAt(selected);
        if (launcher == null) {
            manager.sendMessage(player, "ballistic-not-selected", Map.of());
            return;
        }
        event.setCancelled(true);

        MapTargeting.Result result = MapTargeting.resolve(frame, event.getClickedPosition());
        if (!result.success()) {
            manager.sendMessage(player, result.errorKey(), Map.of());
            return;
        }
        MapTargeting.attachRenderer(frame, manager);
        applyTarget(player, launcher, result.x(), result.z());
    }

    /**
     * Друга половина захисту від обертання карти: сам поворот предмета в рамці вішається
     * саме на цю подію, тому скасувати треба обидві — інакше ціль ставилася б і карта
     * одночасно кивала на 45°.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameRotate(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame) || !event.getPlayer().isSneaking()) {
            return;
        }
        if (frame.getItem().getType() != Material.FILLED_MAP) {
            return;
        }
        if (manager.selected(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    public void applyTarget(Player player, BallisticLauncher launcher, int x, int z) {
        LauncherTier tier = manager.tier(launcher.tierId());
        if (tier == null) {
            return;
        }
        double distance = manager.horizontalDistance(launcher.block().getLocation(), x, z);
        launcher.target(x, z);
        manager.marker(player.getUniqueId(), x, z);
        manager.refreshDisplay(launcher);

        manager.sendMessage(player, distance > tier.range() ? "ballistic-target-too-far" : "ballistic-target-set",
                Map.of("x", x, "z", z, "distance", (int) Math.round(distance), "range", tier.range()));
    }

    /**
     * Вихід у розвідці = розвідник не повернувся на базу. Установка отримує штрафну
     * перезарядку, а гравець при наступному вході не лишається spectator'ом.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (manager.recon().isActive(player)) {
            manager.recon().abort(player);
        }
    }

    // ─────────────────────────── ракета в польоті ───────────────────────────

    /** Збитий розвідник не має лишати після себе стійку й нагрудник на землі. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onReconDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (event.getEntity().getPersistentDataContainer()
                .has(manager.recon().reconKey(), org.bukkit.persistence.PersistentDataType.BYTE)) {
            event.getDrops().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileDamage(EntityDamageEvent event) {
        // Hitbox ракети не має вмирати від сторонньої шкоди — збиття рахує тільки ППО.
        if (manager.projectileOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }
}
