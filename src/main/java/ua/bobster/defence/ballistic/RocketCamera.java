package ua.bobster.defence.ballistic;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RocketCamera implements Listener {

    private static final class Session {
        final UUID playerId;
        final Location origin;
        final GameMode gameMode;
        final float flySpeed;
        final boolean allowFlight;
        final long expiresAt;
        Block launcherBlock;
        ArmorStand waitingTarget;
        BallisticMissile missile;
        Entity cameraTarget;
        long heldChunk;
        World heldWorld;
        int graceTicks;
        boolean spectatorFallback;
        boolean attachQueued;
        boolean ending;

        Session(Player player, long expiresAt) {
            this.playerId = player.getUniqueId();
            this.origin = player.getLocation().clone();
            this.gameMode = player.getGameMode();
            this.flySpeed = player.getFlySpeed();
            this.allowFlight = player.getAllowFlight();
            this.expiresAt = expiresAt;
        }
    }

    private final BobsterDefence plugin;
    private final BallisticManager manager;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask tickTask;
    private boolean enabled;
    private int waitingTimeoutSeconds;

    public RocketCamera(BobsterDefence plugin, BallisticManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        reload();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("ballistic.rocket-camera.enabled", true);
        waitingTimeoutSeconds = Math.clamp(
                plugin.getConfig().getInt("ballistic.rocket-camera.waiting-timeout-seconds", 300), 10, 3600);
    }

    public void start() {
        if (tickTask == null) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        }
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Session session : new ArrayList<>(sessions.values())) {
            end(session, false);
        }
    }

    public boolean isActive(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public void toggle(Player player) {
        Session active = sessions.get(player.getUniqueId());
        if (active != null) {
            end(active, true);
            return;
        }
        if (!enabled) {
            manager.sendMessage(player, "rocket-camera-disabled", Map.of());
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR || plugin.drones().isFlying(player) || manager.recon().isActive(player)) {
            manager.sendMessage(player, "rocket-camera-busy", Map.of());
            return;
        }
        BallisticMissile missile = manager.latestMissile(player.getUniqueId());
        Session session = new Session(player, System.currentTimeMillis() + waitingTimeoutSeconds * 1000L);
        sessions.put(player.getUniqueId(), session);
        if (missile != null) {
            attach(session, missile);
            manager.sendMessage(player, "rocket-camera-attached", Map.of());
            return;
        }
        Block selected = manager.selected(player);
        BallisticLauncher launcher = selected == null ? null : manager.launcherAt(selected);
        if (launcher == null) {
            sessions.remove(player.getUniqueId());
            manager.sendMessage(player, "rocket-camera-no-rocket", Map.of());
            return;
        }
        waitInside(session, selected);
        manager.sendMessage(player, "rocket-camera-waiting", Map.of("seconds", waitingTimeoutSeconds));
    }

    public void onMissileLaunched(Player player, Block launcherBlock, BallisticMissile missile) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.ending || session.missile != null || session.launcherBlock == null || session.attachQueued) {
            return;
        }
        if (!sameBlock(session.launcherBlock, launcherBlock)) {
            return;
        }
        session.attachQueued = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            session.attachQueued = false;
            if (session.ending || session.missile != null) {
                return;
            }
            BallisticMissile latest = manager.latestMissile(player.getUniqueId());
            if (latest == null) {
                return;
            }
            attach(session, latest);
            manager.sendMessage(player, "rocket-camera-launched", Map.of());
        });
    }

    public void onMissileEnd(BallisticMissile missile) {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.missile == missile) {
                end(session, false);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            end(session, false, event.getPlayer());
        }
    }

    private void waitInside(Session session, Block block) {
        session.launcherBlock = block;
        session.heldWorld = block.getWorld();
        session.heldChunk = chunkKey(block.getX() >> 4, block.getZ() >> 4);
        manager.retainChunk(session.heldWorld, session.heldChunk);
        Location position = block.getLocation().add(0.5D, -0.65D, 0.5D);
        session.waitingTarget = block.getWorld().spawn(position, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setSmall(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setPersistent(false);
            stand.setSilent(true);
        });
        enterSpectator(session, session.waitingTarget);
    }

    private void attach(Session session, BallisticMissile missile) {
        releaseWaiting(session);
        session.missile = missile;
        enterSpectator(session, missile.entity());
    }

    private void enterSpectator(Session session, Entity target) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null || !player.isOnline() || target == null || !target.isValid()) {
            end(session, false);
            return;
        }
        session.graceTicks = 10;
        session.cameraTarget = target;
        session.spectatorFallback = false;
        player.setGameMode(GameMode.SPECTATOR);
        Location camera = target.getLocation();
        if (target.getVelocity().lengthSquared() > 1.0E-6D) {
            camera.setDirection(target.getVelocity());
        } else {
            camera.setYaw(player.getYaw());
            camera.setPitch(player.getPitch());
        }
        player.teleport(camera);
        if (!target.addPassenger(player)) {
            session.spectatorFallback = true;
            player.setSpectatorTarget(target);
        }
    }

    private void tick() {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.ending) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(session.playerId);
            Entity target = session.missile == null ? session.waitingTarget : session.missile.entity();
            if (player == null || !player.isOnline()) {
                end(session, false);
                continue;
            }
            if (session.missile == null && System.currentTimeMillis() >= session.expiresAt) {
                manager.sendMessage(player, "rocket-camera-timeout", Map.of());
                end(session, false);
                continue;
            }
            if (session.missile == null && manager.launcherAt(session.launcherBlock) == null) {
                manager.sendMessage(player, "rocket-camera-no-rocket", Map.of());
                end(session, false);
                continue;
            }
            if (target == null || !target.isValid() || session.missile != null && session.missile.isEnding()) {
                end(session, false);
                continue;
            }
            if (session.graceTicks > 0) {
                session.graceTicks -= 2;
            }
            if (player.getGameMode() != GameMode.SPECTATOR) {
                end(session, true);
                continue;
            }
            if (session.graceTicks <= 0 && player.isSneaking()) {
                end(session, true);
                continue;
            }
            if (session.graceTicks <= 0) {
                boolean attached = session.spectatorFallback ? player.getSpectatorTarget() == target : player.getVehicle() == target;
                if (!attached) {
                    end(session, true);
                }
            }
        }
    }

    private void end(Session session, boolean notify) {
        end(session, notify, plugin.getServer().getPlayer(session.playerId));
    }

    private void end(Session session, boolean notify, Player player) {
        if (session.ending) {
            return;
        }
        session.ending = true;
        sessions.remove(session.playerId);
        releaseWaiting(session);
        if (player == null) {
            return;
        }
        player.leaveVehicle();
        player.setSpectatorTarget(null);
        player.setFlySpeed(session.flySpeed);
        player.setAllowFlight(session.allowFlight);
        player.setGameMode(session.gameMode);
        player.teleport(session.origin);
        player.sendActionBar(Component.empty());
        if (notify && player.isOnline()) {
            manager.sendMessage(player, "rocket-camera-returned", Map.of());
        }
    }

    private void releaseWaiting(Session session) {
        if (session.waitingTarget != null) {
            session.waitingTarget.remove();
            session.waitingTarget = null;
        }
        if (session.heldWorld != null) {
            manager.releaseChunk(session.heldWorld, session.heldChunk);
            session.heldWorld = null;
        }
    }

    private boolean sameBlock(Block first, Block second) {
        return first.getWorld().equals(second.getWorld()) && first.getX() == second.getX() && first.getY() == second.getY() && first.getZ() == second.getZ();
    }

    private long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
