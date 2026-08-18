package ua.bobster.defence.drone;

import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;

/**
 * Запуск дрона з предмета та обмеження для оператора під час польоту.
 */
public class DroneListener implements Listener {

    private final BobsterDefence plugin;
    private final DroneManager manager;
    private final DroneItem droneItem;

    public DroneListener(BobsterDefence plugin, DroneManager manager, DroneItem droneItem) {
        this.plugin = plugin;
        this.manager = manager;
        this.droneItem = droneItem;
    }

    // ─────────────────────────────── запуск ───────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // Під час польоту тіло оператора нічого не робить.
        if (manager.isFlying(player)) {
            event.setCancelled(true);
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (!droneItem.isDrone(item)) {
            return;
        }
        // Дрон ніколи не ставиться як звичайний блок TNT.
        event.setCancelled(true);

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (!manager.isEnabled()) {
            manager.sendMessage(player, "drone-disabled");
            return;
        }
        DroneType type = manager.type(droneItem.typeIdOf(item));
        if (type == null) {
            manager.sendMessage(player, "drone-unknown-type");
            return;
        }
        if (!player.hasPermission("bobsterdefence.drone.use")) {
            manager.sendMessage(player, "no-permission");
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            manager.sendMessage(player, "drone-spectator");
            return;
        }
        if (!manager.launch(player, type)) {
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE
                || plugin.getConfig().getBoolean("fpv-drone.consume-in-creative", true)) {
            // Списуємо саме зі слоту, а не з копії, яку віддає подія.
            ItemStack inHand = player.getInventory().getItemInMainHand();
            if (droneItem.isDrone(inHand)) {
                inHand.setAmount(inHand.getAmount() - 1);
            }
        }
    }

    // ─────────────────────── обмеження для оператора ───────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && manager.isFlying(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (manager.isFlying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!manager.isFlying(event.getPlayer())) {
            return;
        }
        // Наші власні телепорти йдуть з причиною PLUGIN — їх пропускаємо.
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
            return;
        }
        // Сторонній телепорт (команда, портал) обриває політ.
        DroneSession session = manager.sessionOf(event.getPlayer());
        if (session != null) {
            manager.end(session, DroneSession.EndReason.SIGNAL_LOST);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        DroneSession session = manager.sessionOf(event.getPlayer());
        if (session == null) {
            return;
        }
        // Гравець вийшов у польоті: дрон детонує, а gamemode повертаємо тут же,
        // інакше при наступному вході він залишиться spectator.
        Player player = event.getPlayer();
        player.setGameMode(session.originalGameMode());
        player.setFlySpeed(session.originalFlySpeed());
        player.setAllowFlight(session.originalAllowFlight());
        player.teleport(session.origin());
        manager.end(session, DroneSession.EndReason.COLLISION);
    }

    // ───────────────────────── шкода та сутності ─────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        // Hitbox дрона не має вмирати «сам собою» — детонацією керуємо ми (ППО/зіткнення).
        if (manager.sessionByDrone(event.getEntity()) != null) {
            event.setCancelled(true);
            return;
        }

        DroneSession session = manager.sessionByBody(event.getEntity());
        if (session == null) {
            return;
        }
        event.setCancelled(true);

        Player operator = plugin.getServer().getPlayer(session.operator());
        if (operator == null || !operator.isOnline()) {
            return;
        }
        // Spectator невразливий, тому шкоду по «тілу» переносимо на здоров'я оператора вручну.
        double remaining = operator.getHealth() - event.getFinalDamage();
        operator.playSound(operator.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.0f, 1.0f);

        if (remaining > 0) {
            operator.setHealth(remaining);
            return;
        }
        manager.sendMessage(operator, "drone-operator-killed");
        manager.end(session, DroneSession.EndReason.OPERATOR_DIED);
        operator.setHealth(0.0D); // gamemode уже відновлено — смерть відпрацює штатно
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (manager.sessionByDrone(event.getRightClicked()) != null
                || manager.sessionByBody(event.getRightClicked()) != null) {
            event.setCancelled(true);
        }
    }
}
