package ua.bobster.defence.strategicstates;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import com.destroystokyo.paper.event.entity.ProjectileCollideEvent;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.block.BlockState;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.util.MessageUtil;

public class StrategicStateListener implements Listener {

    private final BobsterDefence plugin;
    private final StrategicStateManager manager;

    public StrategicStateListener(BobsterDefence plugin, StrategicStateManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!manager.coreItem().isCore(event.getItemInHand())) {
            return;
        }
        if (!event.getPlayer().hasPermission("bobsterdefence.states.create")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MessageUtil.parse("<red>Недостатньо прав для створення держави."));
            return;
        }
        StrategicStateManager.CreationResult result = manager.createState(event.getBlockPlaced());
        if (!result.success()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MessageUtil.parse("<red>" + result.message()));
            return;
        }
        event.getPlayer().sendMessage(MessageUtil.parse("<green>" + result.message()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.BEDROCK) {
            return;
        }
        StrategicState state = manager.coreAt(event.getBlock());
        if (state == null) {
            return;
        }
        if (!event.getPlayer().hasPermission("bobsterdefence.states.admin")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MessageUtil.parse("<red>State Core захищений."));
            return;
        }
        manager.coreDestroyed(state);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (manager.citizenDied(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCitizenDamage(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            attacker = player;
        }
        if (attacker != null) {
            manager.citizenAttacked(event.getEntity(), attacker);
        }
        org.bukkit.entity.Entity stateAttacker = event.getDamager();
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            stateAttacker = shooter;
        }
        if (manager.citizen(stateAttacker) != null && event.getEntity() instanceof org.bukkit.entity.LivingEntity target) {
            if (!manager.citizenTargetAllowed(stateAttacker, target)) {
                event.setCancelled(true);
                return;
            }
            manager.citizenCombatHit(stateAttacker, target, event.getFinalDamage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCitizenEnvironmentDamage(EntityDamageEvent event) {
        if (manager.citizen(event.getEntity()) == null) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION || event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCitizenTarget(EntityTargetLivingEntityEvent event) {
        if (manager.citizen(event.getEntity()) != null && event.getTarget() != null
                && !manager.citizenTargetAllowed(event.getEntity(), event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCitizenShoot(EntityShootBowEvent event) {
        if (manager.citizen(event.getEntity()) == null || !(event.getEntity() instanceof Mob mob) || !(event.getProjectile() instanceof AbstractArrow arrow)) {
            return;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !manager.citizenTargetAllowed(event.getEntity(), target)) {
            event.setCancelled(true);
            return;
        }
        arrow.setPierceLevel(10);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCitizenProjectileCollision(ProjectileCollideEvent event) {
        if (!(event.getEntity().getShooter() instanceof org.bukkit.entity.Entity shooter) || manager.citizen(shooter) == null) {
            return;
        }
        if (event.getCollidedWith() instanceof LivingEntity target && !manager.citizenTargetAllowed(shooter, target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> manager.materializeChunk(event.getChunk()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.virtualizeChunk(event.getChunk());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof BlockState state) {
            plugin.getServer().getScheduler().runTask(plugin, () -> manager.synchronizeWarehouse(state.getLocation()));
        }
    }
}
