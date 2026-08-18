package ua.bobster.defence.drone;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;

import java.util.Map;

/**
 * Щотікова фізика дрона.
 * <p>
 * Рух задає сам оператор (spectator), а ми між попередньою та новою позицією робимо ray trace.
 * Без цього швидкий дрон за один тік просто проскочив би крізь тонку стіну — Bukkit-івські
 * події зіткнення тут не допоможуть, бо spectator крізь блоки проходить вільно.
 */
public class DronePhysics {

    private static final double ENTITY_RAY_SIZE = 0.35D;

    private final BobsterDefence plugin;
    private final DroneManager manager;

    private boolean explodeOnBlocks;
    private boolean explodeOnPlayers;
    private boolean explodeOnEntities;
    private boolean particlesEnabled;
    private boolean actionbarEnabled;
    private boolean soundEnabled;
    private org.bukkit.Sound sound;
    private float soundVolume;
    private float soundPitch;
    private int soundInterval;
    private double ramRadius;

    public DronePhysics(BobsterDefence plugin, DroneManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.explodeOnBlocks = config.getBoolean("fpv-drone.explode-on.blocks", true);
        this.explodeOnPlayers = config.getBoolean("fpv-drone.explode-on.players", true);
        this.explodeOnEntities = config.getBoolean("fpv-drone.explode-on.entities", true);
        this.particlesEnabled = config.getBoolean("fpv-drone.effects.particles", true);
        this.actionbarEnabled = config.getBoolean("fpv-drone.effects.actionbar", true);
        this.soundEnabled = config.getBoolean("fpv-drone.sound.enabled", true);
        this.sound = ua.bobster.defence.util.SoundUtil.resolve(plugin,
                config.getString("fpv-drone.sound.name", "ENTITY_BEE_LOOP"),
                org.bukkit.Sound.ENTITY_BEE_LOOP);
        this.soundVolume = (float) config.getDouble("fpv-drone.sound.volume", 1.0D);
        this.soundPitch = (float) Math.clamp(config.getDouble("fpv-drone.sound.pitch", 1.6D), 0.5D, 2.0D);
        this.soundInterval = Math.max(1, config.getInt("fpv-drone.sound.interval-ticks", 10));
        this.ramRadius = Math.max(0.0D, config.getDouble("fpv-drone.ram-radius", 2.0D));
    }

    public void tickAll() {
        for (DroneSession session : manager.activeSessions()) {
            if (session.isEnding()) {
                continue;
            }
            try {
                tick(session);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Помилка в тіку дрона: " + ex);
                manager.end(session, DroneSession.EndReason.COLLISION);
            }
        }
    }

    private void tick(DroneSession session) {
        Player player = plugin.getServer().getPlayer(session.operator());
        if (player == null || !player.isOnline()) {
            manager.end(session, DroneSession.EndReason.SHUTDOWN);
            return;
        }

        int ticks = session.tick();
        Location current = player.getLocation().clone();
        Location previous = session.lastLocation();

        // Оператор змінив світ (портал/телепорт ззовні) — сигнал утрачено.
        if (current.getWorld() == null || !current.getWorld().equals(previous.getWorld())) {
            manager.end(session, DroneSession.EndReason.SIGNAL_LOST);
            return;
        }

        applySpeed(player, session.type());

        if (checkLimits(session, player, current, ticks)) {
            return;
        }
        if (checkRam(session, player, current)) {
            return;
        }
        if (checkCollision(session, player, previous, current)) {
            return;
        }

        moveEntities(session, current);
        spawnTrail(current);
        // Гудіння апарата: чути і оператору, і тим, над ким він пролітає.
        if (soundEnabled && ticks % soundInterval == 0) {
            current.getWorld().playSound(current, sound, soundVolume, soundPitch);
        }
        if (plugin.airRaid() != null && ticks % 10 == 0) {
            // Раз на пів секунди перевіряємо, чи дрон не зайшов у зону чиєїсь території.
            plugin.airRaid().onDrone(current, session.operator());
        }
        if (actionbarEnabled) {
            sendHud(session, player, current, ticks);
        }
        session.lastLocation(current);
    }

    private void applySpeed(Player player, DroneType type) {
        double target = player.isSprinting() ? type.boostSpeed() : type.speed();
        float flySpeed = (float) Math.clamp(0.1D * target, 0.001D, 1.0D);
        if (Math.abs(player.getFlySpeed() - flySpeed) > 0.0001f) {
            player.setFlySpeed(flySpeed);
        }
    }

    /** @return true, якщо політ уже завершено */
    private boolean checkLimits(DroneSession session, Player player, Location current, int ticks) {
        double distance = current.distance(session.origin());
        if (distance > session.type().maxDistance()) {
            manager.end(session, DroneSession.EndReason.SIGNAL_LOST);
            return true;
        }
        if (ticks >= session.type().flightTime() * 20) {
            manager.end(session, DroneSession.EndReason.BATTERY_EMPTY);
            return true;
        }
        return false;
    }

    /**
     * Таран: збиття чужого дрона зіткненням.
     * <p>
     * Окремо від {@link #checkCollision}, і на це дві причини. По-перше, hitbox дрона —
     * маленька невидима стійка, і влучити в неї променем на льоту практично неможливо;
     * тут працює проста відстань, як і очікує гравець від «врізався». По-друге, hitbox
     * навмисно невразливий до шкоди (щоб не гинув сам собою), тож вибух по ньому нічого
     * не робив — чужий політ треба завершувати явно.
     *
     * @return true, якщо таран стався і наш дрон уже детонував
     */
    private boolean checkRam(DroneSession session, Player player, Location current) {
        if (ramRadius <= 0) {
            return false;
        }
        for (DroneSession other : manager.activeSessions()) {
            if (other == session || other.isEnding() || !other.hitbox().isValid()) {
                continue;
            }
            Location target = other.hitbox().getLocation();
            if (!target.getWorld().equals(current.getWorld())
                    || target.distanceSquared(current) > ramRadius * ramRadius) {
                continue;
            }
            manager.end(other, DroneSession.EndReason.RAMMED, player);
            creditRam(player);
            detonateAt(session, current);
            return true;
        }
        // Розвідник теж збивається тараном — він не вибухає, але апарат втрачено.
        if (plugin.ballistic() != null && plugin.ballistic().recon() != null
                && plugin.ballistic().recon().destroyNear(current, ramRadius)) {
            creditRam(player);
            detonateAt(session, current);
            return true;
        }
        return false;
    }

    private void creditRam(Player player) {
        if (plugin.getConfig().getBoolean("stats.enabled", true)) {
            plugin.stats().increment(player.getUniqueId(), player.getName());
        }
    }

    /** @return true, якщо стався контакт і політ завершено */
    private boolean checkCollision(DroneSession session, Player player, Location previous, Location current) {
        World world = current.getWorld();
        Vector delta = current.toVector().subtract(previous.toVector());
        double travelled = delta.length();
        if (travelled < 1.0E-4D) {
            return false;
        }
        Vector direction = delta.clone().multiply(1.0D / travelled);

        if (explodeOnBlocks) {
            RayTraceResult blockHit = world.rayTraceBlocks(
                    previous, direction, travelled, FluidCollisionMode.NEVER, true);
            if (blockHit != null) {
                detonateAt(session, blockHit.getHitPosition().toLocation(world));
                return true;
            }
        }

        if (explodeOnPlayers || explodeOnEntities) {
            RayTraceResult entityHit = world.rayTraceEntities(previous, direction, travelled, ENTITY_RAY_SIZE,
                    entity -> isValidTarget(session, player, entity));
            if (entityHit != null) {
                detonateAt(session, entityHit.getHitPosition().toLocation(world));
                return true;
            }
        }
        return false;
    }

    private boolean isValidTarget(DroneSession session, Player operator, Entity entity) {
        if (entity.getUniqueId().equals(operator.getUniqueId())
                || entity.getUniqueId().equals(session.hitbox().getUniqueId())
                || entity.getUniqueId().equals(session.display().getUniqueId())) {
            return false;
        }
        if (session.body() != null && entity.getUniqueId().equals(session.body().getUniqueId())) {
            return false;
        }
        // Чужі дрони теж збиваються тараном, а от службові сутності — ні.
        if (entity instanceof Player target) {
            return explodeOnPlayers && target.getGameMode() != org.bukkit.GameMode.SPECTATOR;
        }
        return explodeOnEntities;
    }

    private void detonateAt(DroneSession session, Location location) {
        if (session.hitbox().isValid()) {
            session.hitbox().teleport(location.clone().subtract(0, 0.5D, 0));
        }
        session.lastLocation(location);
        manager.end(session, DroneSession.EndReason.COLLISION);
    }

    private void moveEntities(DroneSession session, Location current) {
        session.hitbox().teleport(current.clone().subtract(0, 0.5D, 0));

        // Модель зміщена трохи назад і вниз, щоб не затуляла камеру оператора,
        // але для інших гравців дрон виглядає рівно там, де він і є.
        Location model = current.clone()
                .add(current.getDirection().normalize().multiply(-0.35D))
                .add(0, -0.55D, 0);
        model.setPitch(0);
        session.display().teleport(model);
    }

    private void spawnTrail(Location location) {
        if (!particlesEnabled) {
            return;
        }
        World world = location.getWorld();
        world.spawnParticle(Particle.SMOKE, location.clone().add(0, -0.4D, 0), 2, 0.05D, 0.05D, 0.05D, 0.0D);
    }

    private void sendHud(DroneSession session, Player player, Location current, int ticks) {
        int totalTicks = session.type().flightTime() * 20;
        int secondsLeft = Math.max(0, (totalTicks - ticks) / 20);
        double distance = current.distance(session.origin());
        int percent = (int) Math.round(100.0D * (totalTicks - ticks) / totalTicks);

        player.sendActionBar(MessageUtil.parse(plugin.message("drone-hud"), Map.of(
                "name", MessageUtil.plain(MessageUtil.parse(session.type().displayName())),
                "bar", bar(percent),
                "percent", percent,
                "seconds", secondsLeft,
                "distance", (int) Math.round(distance),
                "max-distance", session.type().maxDistance())));
    }

    private String bar(int percent) {
        int filled = Math.clamp(Math.round(percent / 10.0f), 0, 10);
        return "█".repeat(filled) + "░".repeat(10 - filled);
    }
}
