package ua.bobster.defence.airdefence;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.ballistic.BallisticManager;
import ua.bobster.defence.ballistic.BallisticMissile;
import ua.bobster.defence.drone.DroneManager;
import ua.bobster.defence.drone.DroneSession;
import ua.bobster.defence.util.MessageUtil;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ППО: влучання стрілою в активований TNT (TNTPrimed) підриває його майже миттєво.
 * <p>
 * Свідомо НЕ робимо remove() + createExplosion() вручну — лишаємо сам TNT
 * і просто скорочуємо йому fuse. Так зберігається штатна логіка Minecraft:
 * потужність, джерело вибуху, взаємодія з блоками і наш ChestProtectionListener.
 */
public class AirDefenceListener implements Listener {

    private static final Particle FALLBACK_PARTICLE = Particle.CRIT;

    private final BobsterDefence plugin;

    private boolean enabled;
    private boolean allowArrow;
    private boolean allowSpectral;
    private boolean allowTrident;
    private boolean removeArrow;
    private int fuseTicks;
    private int chancePercent;
    private boolean countNonPlayerShooters;

    private boolean particlesEnabled;
    private Particle particle = FALLBACK_PARTICLE;
    private int particleCount;
    private double particleOffset;

    private boolean soundEnabled;
    private Sound sound = Sound.ENTITY_FIREWORK_ROCKET_BLAST;
    private float soundVolume;
    private float soundPitch;

    private boolean actionbarEnabled;
    private boolean interceptTnt;
    private boolean interceptDrones;
    private boolean interceptMissiles;

    public AirDefenceListener(BobsterDefence plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("air-defence.enabled", true);
        this.allowArrow = config.getBoolean("air-defence.projectiles.arrow", true);
        this.allowSpectral = config.getBoolean("air-defence.projectiles.spectral-arrow", true);
        this.allowTrident = config.getBoolean("air-defence.projectiles.trident", false);
        this.removeArrow = config.getBoolean("air-defence.remove-arrow", true);
        this.fuseTicks = Math.max(1, config.getInt("air-defence.instant-fuse-ticks", 1));
        this.chancePercent = Math.clamp(config.getInt("air-defence.chance-percent", 100), 0, 100);
        this.countNonPlayerShooters = config.getBoolean("air-defence.count-non-player-shooters", false);

        this.particlesEnabled = config.getBoolean("air-defence.effects.particles", true);
        this.particle = resolveParticle(config.getString("air-defence.effects.particle", "ELECTRIC_SPARK"));
        this.particleCount = Math.max(0, config.getInt("air-defence.effects.particle-count", 30));
        this.particleOffset = Math.max(0.0D, config.getDouble("air-defence.effects.particle-offset", 0.4D));

        this.soundEnabled = config.getBoolean("air-defence.effects.sound", true);
        this.sound = resolveSound(config.getString("air-defence.effects.sound-name", "ENTITY_FIREWORK_ROCKET_BLAST"));
        this.soundVolume = (float) config.getDouble("air-defence.effects.sound-volume", 1.0D);
        this.soundPitch = (float) Math.clamp(config.getDouble("air-defence.effects.sound-pitch", 1.4D), 0.5D, 2.0D);

        this.actionbarEnabled = config.getBoolean("air-defence.effects.actionbar", true);
        this.interceptTnt = config.getBoolean("air-defence.intercept-tnt", true);
        this.interceptDrones = config.getBoolean("air-defence.intercept-drones", true);
        this.interceptMissiles = config.getBoolean("air-defence.intercept-missiles", true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!enabled) {
            return;
        }
        Projectile projectile = event.getEntity();
        // Стріли стаціонарної ППО обробляє її власне наведення — інакше ракета
        // порахувала б одне влучання двічі.
        if (plugin.aa() != null && projectile.getPersistentDataContainer()
                .has(plugin.aa().interceptorKey(), org.bukkit.persistence.PersistentDataType.BYTE)) {
            return;
        }
        if (plugin.ballistic() != null && plugin.ballistic().projectileOf(projectile) != null) {
            return;
        }
        if (plugin.strike() != null && plugin.strike().isGuided(projectile)) {
            return;
        }
        if (!isAllowedProjectile(projectile)) {
            return;
        }
        Entity hit = event.getHitEntity();
        if (hit == null) {
            return;
        }
        if (interceptMissiles && tryInterceptMissile(event, projectile, hit)) {
            return;
        }
        if (interceptDrones && tryInterceptDrone(event, projectile, hit)) {
            return;
        }
        if (!interceptTnt || !(hit instanceof TNTPrimed tnt) || !tnt.isValid()) {
            return;
        }
        // Стріла могла прилетіти вже після того, як TNT почав детонувати.
        if (tnt.getFuseTicks() <= fuseTicks) {
            return;
        }
        // Два гравці влучили в один тік — зараховуємо лише перше влучання.
        if (isAlreadyIntercepted(tnt)) {
            consumeProjectile(event, projectile);
            return;
        }
        if (chancePercent < 100 && ThreadLocalRandom.current().nextInt(100) >= chancePercent) {
            return; // промах ППО — стріла поводиться як завжди
        }

        markIntercepted(tnt);
        tnt.setFuseTicks(fuseTicks);

        playEffects(tnt.getLocation());
        rewardShooter(projectile, "intercept-actionbar");
        consumeProjectile(event, projectile);
    }

    /**
     * Влучання по балістичній ракеті. На відміну від TNT і дрона, ракету з одного разу не збити:
     * потрібно стільки влучань, скільки задано рівнем установки (MK-III → 3 влучання).
     *
     * @return true, якщо снаряд влучив саме в ракету
     */
    private boolean tryInterceptMissile(ProjectileHitEvent event, Projectile projectile, Entity hit) {
        if (plugin.strike() != null && plugin.strike().isGuided(hit)) {
            if (plugin.strike().isGuidedLaunchProtected(hit)) {
                return false;
            }
            if (chancePercent < 100 && ThreadLocalRandom.current().nextInt(100) >= chancePercent) {
                return true;
            }
            playEffects(hit.getLocation());
            consumeProjectile(event, projectile);
            rewardShooter(projectile, "intercept-missile-actionbar");
            plugin.strike().intercept(hit);
            return true;
        }
        BallisticManager ballistic = plugin.ballistic();
        if (ballistic == null) {
            return false;
        }
        // Саме за hitbox: стріла з лука не стикається зі снарядами, тож у корпус-тризуб
        // влучити неможливо в принципі.
        BallisticMissile missile = ballistic.missileByHitbox(hit);
        if (missile == null || missile.isEnding()) {
            return false;
        }
        if (!missile.interceptable()) {
            consumeProjectile(event, projectile);
            return true;
        }
        if (chancePercent < 100 && ThreadLocalRandom.current().nextInt(100) >= chancePercent) {
            return true;
        }

        playEffects(hit.getLocation());
        consumeProjectile(event, projectile);

        boolean destroyed = missile.registerHit();
        Player shooter = projectile.getShooter() instanceof Player player ? player : null;

        if (!destroyed) {
            ballistic.notifyDamaged(missile);
            if (shooter != null && actionbarEnabled) {
                shooter.sendActionBar(MessageUtil.parse(plugin.message("intercept-missile-progress"),
                        Map.of("left", missile.hitsLeft(), "total", missile.tier().hitsToIntercept())));
            }
            return true;
        }
        rewardShooter(projectile, "intercept-missile-actionbar");
        ballistic.detonate(missile, missile.currentLocation(), true);
        return true;
    }

    /**
     * Влучання по FPV-дрону. Дрон — це не TNTPrimed, а власна сутність-hitbox,
     * тож перевіряємо його окремо і завершуємо політ через DroneManager.
     *
     * @return true, якщо снаряд влучив саме в дрон
     */
    private boolean tryInterceptDrone(ProjectileHitEvent event, Projectile projectile, Entity hit) {
        DroneManager drones = plugin.drones();
        if (drones == null) {
            return false;
        }
        if (!drones.isDrone(hit)) {
            return false;
        }
        if (chancePercent < 100 && ThreadLocalRandom.current().nextInt(100) >= chancePercent) {
            return true; // промах ППО, але снаряд усе одно влучив у дрон — TNT тут ні до чого
        }

        Player shooter = projectile.getShooter() instanceof Player player ? player : null;
        playEffects(hit.getLocation());
        rewardShooter(projectile, "intercept-drone-actionbar");
        consumeProjectile(event, projectile);
        drones.intercept(hit, shooter);
        return true;
    }

    private boolean isAllowedProjectile(Projectile projectile) {
        if (projectile instanceof Trident) {
            return allowTrident;
        }
        if (projectile instanceof SpectralArrow) {
            return allowSpectral;
        }
        // Arrow покриває і звичайні, і tipped (зіллєві) стріли.
        if (projectile instanceof Arrow) {
            return allowArrow;
        }
        // Сніжки, яйця, феєрверки, вогняні кулі тощо — не ППО.
        return false;
    }

    private boolean isAlreadyIntercepted(TNTPrimed tnt) {
        return tnt.getPersistentDataContainer().has(plugin.interceptedKey(), PersistentDataType.BYTE);
    }

    private void markIntercepted(TNTPrimed tnt) {
        tnt.getPersistentDataContainer().set(plugin.interceptedKey(), PersistentDataType.BYTE, (byte) 1);
    }

    private void consumeProjectile(ProjectileHitEvent event, Projectile projectile) {
        if (!removeArrow) {
            return;
        }
        event.setCancelled(true); // щоб стріла не «прилипала» до вже мертвої цілі
        projectile.remove();
    }

    private void playEffects(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        if (particlesEnabled && particleCount > 0) {
            world.spawnParticle(particle, location, particleCount, particleOffset, particleOffset, particleOffset, 0.0D);
        }
        if (soundEnabled) {
            world.playSound(location, sound, soundVolume, soundPitch);
        }
    }

    private void rewardShooter(Projectile projectile, String messageKey) {
        ProjectileSource shooter = projectile.getShooter();
        if (!(shooter instanceof Player player)) {
            if (countNonPlayerShooters) {
                // Скелет/роздавач: перехоплення відбулося, але записувати нема на кого.
                plugin.getLogger().fine("Перехоплення не від гравця — статистика не змінена.");
            }
            return;
        }
        int kills = plugin.getConfig().getBoolean("stats.enabled", true)
                ? plugin.stats().increment(player.getUniqueId(), player.getName())
                : 0;

        if (actionbarEnabled) {
            player.sendActionBar(MessageUtil.parse(
                    plugin.message(messageKey),
                    Map.of("kills", kills, "player", player.getName())));
        }
    }

    private Particle resolveParticle(String raw) {
        if (raw == null) {
            return FALLBACK_PARTICLE;
        }
        try {
            Particle parsed = Particle.valueOf(raw.trim().toUpperCase());
            if (parsed.getDataType() != Void.class) {
                // Такі частинки вимагають додаткових даних (напр. DUST) — spawnParticle без них кине помилку.
                plugin.getLogger().warning("Частинка '" + raw + "' потребує додаткових даних, використано "
                        + FALLBACK_PARTICLE.name());
                return FALLBACK_PARTICLE;
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Невідома частинка '" + raw + "', використано " + FALLBACK_PARTICLE.name());
            return FALLBACK_PARTICLE;
        }
    }

    private Sound resolveSound(String raw) {
        if (raw == null) {
            return Sound.ENTITY_FIREWORK_ROCKET_BLAST;
        }
        try {
            return Sound.valueOf(raw.trim().toUpperCase().replace('.', '_'));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Невідомий звук '" + raw + "', використано ENTITY_FIREWORK_ROCKET_BLAST");
            return Sound.ENTITY_FIREWORK_ROCKET_BLAST;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
