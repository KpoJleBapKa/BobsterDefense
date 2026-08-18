package ua.bobster.defence.ballistic;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.Vector;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.SoundUtil;

/**
 * Уся видима й чутна частина ракети: реактивний слід, звук двигуна, ефекти старту,
 * перехоплення та удару. Логіка польоту про це нічого не знає.
 */
public class MissileEffects {

    private final BobsterDefence plugin;

    private boolean exhaustEnabled;
    private int flameCount;
    private int smokeCount;
    private double trailLength;
    private int trailPoints;
    private int exhaustInterval;

    private boolean soundEnabled;
    private Sound engineSound;
    private float soundVolume;
    private float soundPitch;
    private int soundInterval;

    private boolean launchEffect;
    private boolean launchSoundEnabled;
    private Sound launchSound;
    private float launchVolume;
    private float launchPitch;
    private Sound launchBlast;
    private float launchBlastVolume;
    private float launchBlastPitch;

    public MissileEffects(BobsterDefence plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.exhaustEnabled = config.getBoolean("ballistic.missile.exhaust.enabled", true);
        this.flameCount = Math.max(0, config.getInt("ballistic.missile.exhaust.flame-count", 4));
        this.smokeCount = Math.max(0, config.getInt("ballistic.missile.exhaust.smoke-count", 2));
        this.trailLength = Math.max(0.1D, config.getDouble("ballistic.missile.exhaust.trail-length", 1.5D));
        this.trailPoints = Math.clamp(config.getInt("ballistic.missile.exhaust.trail-points", 3), 1, 8);
        this.exhaustInterval = Math.max(1, config.getInt("ballistic.missile.exhaust.interval-ticks", 1));

        this.soundEnabled = config.getBoolean("ballistic.missile.sound.enabled", true);
        this.engineSound = SoundUtil.resolve(plugin,
                config.getString("ballistic.missile.sound.name", "ENTITY_BLAZE_SHOOT"), Sound.ENTITY_BLAZE_SHOOT);
        this.soundVolume = (float) config.getDouble("ballistic.missile.sound.volume", 6.0D);
        this.soundPitch = (float) Math.clamp(
                config.getDouble("ballistic.missile.sound.pitch", 0.7D), 0.5D, 2.0D);
        this.soundInterval = Math.max(1, config.getInt("ballistic.missile.sound.interval-ticks", 8));

        this.launchEffect = config.getBoolean("ballistic.missile.launch-effect", true);
        this.launchSoundEnabled = config.getBoolean("ballistic.missile.launch-sound.enabled", true);
        this.launchSound = SoundUtil.resolve(plugin,
                config.getString("ballistic.missile.launch-sound.name", "ENTITY_FIREWORK_ROCKET_LAUNCH"),
                Sound.ENTITY_FIREWORK_ROCKET_LAUNCH);
        this.launchVolume = (float) config.getDouble("ballistic.missile.launch-sound.volume", 12.0D);
        this.launchPitch = (float) Math.clamp(
                config.getDouble("ballistic.missile.launch-sound.pitch", 0.5D), 0.5D, 2.0D);
        this.launchBlast = SoundUtil.resolve(plugin,
                config.getString("ballistic.missile.launch-sound.blast-name", "ENTITY_FIREWORK_ROCKET_BLAST"),
                Sound.ENTITY_FIREWORK_ROCKET_BLAST);
        this.launchBlastVolume = (float) config.getDouble("ballistic.missile.launch-sound.blast-volume", 10.0D);
        this.launchBlastPitch = (float) Math.clamp(
                config.getDouble("ballistic.missile.launch-sound.blast-pitch", 0.6D), 0.5D, 2.0D);
    }

    /**
     * Реактивний слід. Малюється <b>позаду</b> ракети вздовж вектора руху, а не в її точці:
     * інакше полум'я тягнулося б збоку і на дугу було б схоже мало.
     */
    public void exhaust(Location position, Vector direction, MissileState state, int ticks) {
        if (!exhaustEnabled || ticks % exhaustInterval != 0 || direction.lengthSquared() < 1.0E-6D) {
            return;
        }
        World world = position.getWorld();
        Vector back = direction.clone().normalize().multiply(-1);
        double throttle = throttle(state);

        for (int i = 1; i <= trailPoints; i++) {
            double step = trailLength * i / trailPoints;
            Location point = position.clone().add(back.clone().multiply(step));
            // Далі від сопла — рідше: так слід виглядає як згасаючий факел, а не як труба.
            double fade = 1.0D - (double) (i - 1) / trailPoints;

            int flames = (int) Math.round(flameCount * throttle * fade);
            if (flames > 0) {
                force(world, Particle.FLAME, point, flames, 0.05D, 0.01D);
            }
            int smoke = (int) Math.round(smokeCount * throttle * fade);
            if (smoke > 0) {
                force(world, Particle.SMOKE, point, smoke, 0.08D, 0.01D);
            }
        }
    }

    /**
     * Частинки з примусовою видимістю.
     * <p>
     * Без цього клієнт малює їх лише в радіусі <b>32 блоків</b> — тобто слід ракети,
     * яка йде на висоті 150+, не видно з землі взагалі. Прапорець {@code force} піднімає
     * межу до 512 блоків, і саме він потрібен усьому, що літає високо.
     */
    private static void force(World world, Particle particle, Location at,
                              int count, double spread, double extra) {
        world.spawnParticle(particle, at, count, spread, spread, spread, extra, null, true);
    }

    /** На розгоні й пікіруванні двигун працює на повну, у верхній частині дуги — впівсили. */
    private double throttle(MissileState state) {
        return switch (state) {
            case LAUNCH, ASCENT, DESCENT -> 1.0D;
            case BALLISTIC -> 0.45D;
            default -> 0.0D;
        };
    }

    /**
     * Звук двигуна. Гучність тут — це насамперед <b>дальність</b>: Minecraft роздає звук
     * гравцям у радіусі {@code volume × 16} блоків. На штатній одиниці ракету було б чути
     * лише за 16 блоків, тобто практично ніколи.
     */
    public void engine(Location position, int ticks) {
        if (!soundEnabled || ticks % soundInterval != 0) {
            return;
        }
        position.getWorld().playSound(position, engineSound, soundVolume, soundPitch);
    }

    /**
     * Стартовий факел: перші тіки помітно щедріші на частинки.
     * <p>
     * Звук — два шари феєрверка одночасно: низький «пуск» дає тіло звуку, верхній «розрив» —
     * різкість. Гучність узята з великим запасом, щоб пуск було чути далеко за межами бази.
     */
    public void launch(Location position) {
        World world = position.getWorld();
        if (launchSoundEnabled) {
            world.playSound(position, launchSound, launchVolume, launchPitch);
            world.playSound(position, launchBlast, launchBlastVolume, launchBlastPitch);
        }
        if (!launchEffect) {
            return;
        }
        force(world, Particle.FLAME, position, 80, 0.6D, 0.12D);
        force(world, Particle.LARGE_SMOKE, position, 60, 0.8D, 0.06D);
        force(world, Particle.CLOUD, position, 40, 1.0D, 0.05D);
    }

    /** Повітряний підрив: спалах і дим, без вогню й без кратера. */
    public void interception(Location position) {
        World world = position.getWorld();
        force(world, Particle.FLASH, position, 2, 0.0D, 0.0D);
        force(world, Particle.EXPLOSION, position, 4, 1.0D, 0.0D);
        force(world, Particle.LARGE_SMOKE, position, 40, 1.5D, 0.08D);
        force(world, Particle.FLAME, position, 30, 1.2D, 0.15D);
        world.playSound(position, Sound.ENTITY_GENERIC_EXPLODE, 10.0f, 1.2f);
        world.playSound(position, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 8.0f, 1.0f);
    }
}
