package ua.bobster.defence.aa;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.ballistic.BallisticMissile;
import ua.bobster.defence.drone.DroneSession;
import ua.bobster.defence.util.DisplayUtil;
import ua.bobster.defence.util.MessageUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Стаціонарна автоматична ППО: реєстр установок, сканування зон, пуск і наведення перехоплювачів.
 * <p>
 * Установки не шукаються по світу — їхні координати зберігаються в aa-launchers.yml,
 * решта даних лежить у PDC самого блока.
 */
public class AaManager {

    private record ActiveShot(Interceptor interceptor, String launcherKey, String tierId) {
    }

    private final BobsterDefence plugin;
    private final AaItem item;
    private final TeamRegistry teams;
    private final AaTargetScanner scanner;

    private final NamespacedKey ownerKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey turretKey;
    private final NamespacedKey interceptorKey;

    private final Map<String, AaTier> tiers = new LinkedHashMap<>();
    private final Set<String> launcherKeys = new LinkedHashSet<>();
    private final List<ActiveShot> shots = new ArrayList<>();
    private final Map<String, Long> lastShot = new HashMap<>();
    private final Map<String, String> lastStatus = new HashMap<>();
    /**
     * Вердикт «чи впорається цей тип ППО з цією ракетою» — кидається один раз на ракету.
     * Ключ: UUID ракети → id типу ППО → чи бере.
     */
    private final Map<UUID, Map<String, Boolean>> missileVerdict = new HashMap<>();
    /** Скільки перехоплювачів цього типу вже змарновано на ракету, яку він не бере. */
    private final Map<UUID, Map<String, Integer>> wastedShots = new HashMap<>();
    /** Коли по ракеті вперше кинули вердикт — щоб прибрати запис після її зникнення. */
    private final Map<UUID, Long> verdictTime = new HashMap<>();

    private final File storageFile;
    private BukkitTask tickTask;
    private long tickCounter;

    private boolean enabled;
    private int interceptorLifetime;
    private boolean flameTrail;
    private boolean smokeTrail;
    private boolean countInStats;
    private double hologramDistance;
    private double hitDistance;
    private int wastedShotsLimit;
    private long verdictTtlMillis;

    public AaManager(BobsterDefence plugin, AaItem item, TeamRegistry teams) {
        this.plugin = plugin;
        this.item = item;
        this.teams = teams;
        this.scanner = new AaTargetScanner(plugin, teams);
        this.ownerKey = new NamespacedKey(plugin, "aa_owner");
        this.displayKey = new NamespacedKey(plugin, "aa_display");
        this.turretKey = new NamespacedKey(plugin, "aa_turret");
        this.interceptorKey = new NamespacedKey(plugin, "aa_interceptor");
        this.storageFile = new File(plugin.getDataFolder(), "aa-launchers.yml");
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("air-defence-system.enabled", true);
        this.interceptorLifetime = Math.max(20, config.getInt("air-defence-system.interceptor.lifetime-ticks", 100));
        this.flameTrail = config.getBoolean("air-defence-system.interceptor.flame-trail", true);
        this.smokeTrail = config.getBoolean("air-defence-system.interceptor.smoke-trail", true);
        this.countInStats = config.getBoolean("air-defence-system.count-in-stats", true);
        this.hologramDistance = Math.max(0.5D,
                config.getDouble("air-defence-system.hologram-distance", 3.0D));
        this.hitDistance = Math.max(0.5D,
                config.getDouble("air-defence-system.interceptor.hit-distance", 2.0D));
        this.wastedShotsLimit = Math.max(1,
                config.getInt("air-defence-system.targeting.wasted-shots-per-missile", 2));
        // Найдовший політ ракети — близько 45 с, тож із запасом.
        this.verdictTtlMillis = Math.max(30, config.getInt(
                "air-defence-system.targeting.verdict-ttl-seconds", 180)) * 1000L;
        // Скидаємо кеш статусів, інакше після /ppo reload плашки не перемалювалися б
        // і не підхопили б нову дальність видимості.
        lastStatus.clear();
        scanner.reload();
        loadTiers(config.getConfigurationSection("air-defence-system.launcher"));
    }

    private void loadTiers(ConfigurationSection section) {
        tiers.clear();
        if (section == null) {
            return;
        }
        int level = 0;
        for (String id : section.getKeys(false)) {
            ConfigurationSection tier = section.getConfigurationSection(id);
            if (tier == null) {
                continue;
            }
            level++;
            tiers.put(id.toLowerCase(), new AaTier(
                    id.toLowerCase(),
                    tier.getInt("level", level),
                    tier.getString("name", "<gold>🛡 BOBSTER AA " + id.toUpperCase()),
                    Math.max(4, tier.getInt("range", 32)),
                    Math.max(4, tier.getInt("vertical-range", 48)),
                    Math.max(1, tier.getInt("max-targets", 1)),
                    Math.max(1, tier.getInt("scan-interval", 5)),
                    Math.max(1, tier.getInt("fire-cooldown", 30)),
                    Math.max(0.5D, tier.getDouble("interceptor-speed", 2.5D)),
                    Math.clamp(tier.getDouble("missile-hit-chance", 1.0D), 0.0D, 1.0D),
                    tier.getStringList("lore"),
                    tier.getString("ammo.name", "<gold>🔥 " + id.toUpperCase() + " rocket"),
                    tier.getStringList("ammo.lore")));
        }
    }

    /**
     * Старі назви рівнів (mk1/mk2/mk3) лишаються робочими: у вже поставлених установок
     * саме вони записані в PDC блока, і без цього вони б «забули» свій рівень.
     */
    private static String canonical(String id) {
        return switch (id.toLowerCase()) {
            case "mk1" -> "nasams";
            case "mk2" -> "patriot";
            case "mk3" -> "freyja";
            default -> id.toLowerCase();
        };
    }

    public void start() {
        loadLaunchers();
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (ActiveShot shot : shots) {
            shot.interceptor().cleanup();
        }
        shots.clear();
        saveLaunchers();
    }

    // ─────────────────────────────── реєстр ───────────────────────────────

    private String keyOf(Block block) {
        return block.getWorld().getUID() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    private Block blockOf(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(UUID.fromString(parts[0]));
        if (world == null) {
            return null;
        }
        return world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]));
    }

    public void register(Block block) {
        launcherKeys.add(keyOf(block));
        saveLaunchers();
    }

    public void unregister(Block block) {
        launcherKeys.remove(keyOf(block));
        lastShot.remove(keyOf(block));
        lastStatus.remove(keyOf(block));
        saveLaunchers();
    }

    private void loadLaunchers() {
        launcherKeys.clear();
        if (!storageFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        launcherKeys.addAll(yaml.getStringList("launchers"));
        plugin.getLogger().info("AA: установок у реєстрі — " + launcherKeys.size());
    }

    private void saveLaunchers() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("launchers", new ArrayList<>(launcherKeys));
        try {
            File parent = storageFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            yaml.save(storageFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти aa-launchers.yml", ex);
        }
    }

    // ─────────────────────────────── доступ ───────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public AaItem item() {
        return item;
    }

    public NamespacedKey interceptorKey() {
        return interceptorKey;
    }

    public List<AaTier> tiers() {
        return new ArrayList<>(tiers.values());
    }

    public AaTier tier(String id) {
        if (id == null) {
            return null;
        }
        AaTier direct = tiers.get(id.toLowerCase());
        return direct != null ? direct : tiers.get(canonical(id));
    }

    public AaLauncher wrap(Block block) {
        return new AaLauncher(block, item, ownerKey, displayKey, turretKey);
    }

    public AaLauncher launcherAt(Block block) {
        if (block == null || block.getType() != Material.DISPENSER) {
            return null;
        }
        AaLauncher launcher = wrap(block);
        return launcher.tierId() == null ? null : launcher;
    }

    // ──────────────────────────────── тік ────────────────────────────────

    private void tick() {
        tickCounter++;
        tickInterceptors();
        if (tickCounter % 200 == 0) {
            forgetOldVerdicts();
        }
        if (!enabled) {
            return;
        }
        Iterator<String> iterator = new ArrayList<>(launcherKeys).iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            try {
                tickLauncher(key);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Помилка в тіку ППО " + key + ": " + ex);
            }
        }
    }

    /**
     * Прибирає вердикти за ракетами, яких давно немає.
     * <p>
     * Навмисно за часом, а не за наявністю перехоплювача в дорозі: між пусками ракета
     * лишається в польоті без жодного перехоплювача, і вердикт тоді б забувся й перекинувся
     * наново — тобто «один кидок на ракету» перестав би бути правдою.
     */
    private void forgetOldVerdicts() {
        if (verdictTime.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() - verdictTtlMillis;
        verdictTime.entrySet().removeIf(entry -> {
            if (entry.getValue() > deadline) {
                return false;
            }
            missileVerdict.remove(entry.getKey());
            wastedShots.remove(entry.getKey());
            return true;
        });
    }

    private void tickInterceptors() {
        Iterator<ActiveShot> iterator = shots.iterator();
        while (iterator.hasNext()) {
            ActiveShot shot = iterator.next();
            Interceptor.Result result;
            try {
                result = shot.interceptor().tick();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Помилка перехоплювача: " + ex);
                shot.interceptor().cleanup();
                iterator.remove();
                continue;
            }
            if (result == Interceptor.Result.FLYING) {
                continue;
            }
            if (result == Interceptor.Result.HIT) {
                intercept(shot.interceptor(), shot.tierId());
            }
            shot.interceptor().cleanup();
            iterator.remove();
        }
    }

    private void tickLauncher(String key) {
        Block block = blockOf(key);
        if (block == null || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        AaLauncher launcher = launcherAt(block);
        if (launcher == null) {
            // Блок зник (наприклад, вибухом) — прибираємо з реєстру.
            launcherKeys.remove(key);
            saveLaunchers();
            return;
        }
        AaTier tier = tier(launcher.tierId());
        if (tier == null || tickCounter % tier.scanInterval() != 0) {
            return;
        }

        int ammo = launcher.ammoCount(tier);
        // Темп вогню тримається виключно на власному кулдауні установки. Раніше замість нього
        // рахувалися «слоти» по цілі: щойно перехоплювач влучав або втрачав ціль, слот
        // звільнявся і установка одразу брала наступну — темп залежав від того, що летить,
        // а не від самої установки. Тепер NASAMS пускає раз на 15 с незалежно ні від чого.
        boolean ready = tickCounter - lastShot.getOrDefault(key, -9999L) >= tier.fireCooldown();

        if (ammo <= 0) {
            updateStatus(launcher, tier, "aa-status-no-ammo", ammo, null);
            return;
        }
        if (!ready) {
            updateStatus(launcher, tier, shots.isEmpty() ? "aa-status-active" : "aa-status-lock", ammo, null);
            return;
        }

        List<AaTarget> targets = scanner.scan(launcher.muzzle(), tier, launcher.owner(),
                target -> worthEngaging(target, tier),
                tier.maxTargets());
        if (targets.isEmpty()) {
            updateStatus(launcher, tier, "aa-status-active", ammo, null);
            return;
        }
        AaTarget target = targets.get(0);
        fire(launcher, tier, target, key);
        updateStatus(launcher, tier, "aa-status-lock", launcher.ammoCount(tier), target);
    }

    /**
     * Установка не має висаджувати весь боєкомплект у ракету, яка їй не по зубах.
     * Кілька пусків вона зробить — щоб це виглядало як робота ППО, а не як байдужість, —
     * але після {@code wasted-shots-per-missile} марних влучань переходить на інші цілі.
     */
    private boolean worthEngaging(AaTarget target, AaTier tier) {
        if (target.type() != AaTarget.Type.BALLISTIC_MISSILE) {
            return true;
        }
        Map<String, Integer> wasted = wastedShots.get(target.id());
        return wasted == null || wasted.getOrDefault(tier.id(), 0) < wastedShotsLimit;
    }

    // ─────────────────────────────── постріл ───────────────────────────────

    private void fire(AaLauncher launcher, AaTier tier, AaTarget target, String key) {
        if (!launcher.consumeAmmo(tier)) {
            return;
        }
        lastShot.put(key, tickCounter);

        Location muzzle = launcher.muzzle();
        World world = muzzle.getWorld();
        Vector initial = target.aimPoint().toVector().subtract(muzzle.toVector()).normalize()
                .multiply(tier.speed());

        Arrow arrow = world.spawn(muzzle, Arrow.class, spawned -> {
            spawned.setVelocity(initial);
            spawned.setGravity(false);          // керована ракета, а не балістична стріла
            spawned.setFireTicks(20 * 60);      // саме той «вогняний ефект»
            spawned.setCritical(true);
            spawned.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            spawned.setPersistent(false);
            spawned.getPersistentDataContainer().set(interceptorKey, PersistentDataType.BYTE, (byte) 1);
            UUID owner = launcher.owner();
            if (owner != null) {
                spawned.getPersistentDataContainer().set(
                        plugin.ownerKey(), PersistentDataType.STRING, owner.toString());
            }
        });

        shots.add(new ActiveShot(new Interceptor(arrow, target, launcher.owner(), tier.speed(),
                hitDistance, interceptorLifetime, flameTrail, smokeTrail), key, tier.id()));

        world.playSound(muzzle, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.6f, 1.4f);
        world.spawnParticle(Particle.FLAME, muzzle, 15, 0.2D, 0.2D, 0.2D, 0.05D);
        aimTurret(launcher, target.aimPoint());
    }

    /**
     * Перехоплення: єдина точка, де ППО щось робить із ціллю. Що саме летіло —
     * визначає {@link AaTarget.Type}, решта коду про це не знає.
     */
    private void intercept(Interceptor interceptor, String tierId) {
        AaTarget target = interceptor.target();
        if (!target.isValid()) {
            return;
        }
        Location location = target.location();

        // Балістику бере не кожна система. Вердикт кидається один раз на ракету:
        // серія збиттів ніяк не впливає на наступну ракету, кожна — окремий кидок.
        if (target.type() == AaTarget.Type.BALLISTIC_MISSILE && !canDefeatMissile(target, tierId)) {
            missEffect(location);
            wastedShots.computeIfAbsent(target.id(), id -> new HashMap<>())
                    .merge(tierId, 1, Integer::sum);
            return;
        }
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.EXPLOSION, location, 1);
            world.spawnParticle(Particle.CRIT, location, 25, 0.4D, 0.4D, 0.4D, 0.1D);
            world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0f, 1.3f);
        }

        missileVerdict.remove(target.id());
        wastedShots.remove(target.id());
        verdictTime.remove(target.id());
        boolean destroyed = switch (target.type()) {
            case VANILLA_TNT -> interceptTnt(target.entity());
            case FPV_DRONE -> interceptDrone(target.entity(), interceptor.launcherOwner());
            case BALLISTIC_MISSILE -> interceptMissile(target.entity());
        };
        if (destroyed) {
            creditOwner(interceptor.launcherOwner());
        }
    }

    /**
     * Чи здатна ця система збити конкретну ракету. Кидок робиться один раз і запам'ятовується:
     * інакше три батареї по 80% давали б разом майже 100%, і числа в конфізі нічого б не означали.
     */
    private boolean canDefeatMissile(AaTarget target, String tierId) {
        AaTier tier = tier(tierId);
        if (tier == null || tier.missileHitChance() >= 1.0D) {
            return true;
        }
        verdictTime.putIfAbsent(target.id(), System.currentTimeMillis());
        return missileVerdict.computeIfAbsent(target.id(), id -> new HashMap<>())
                .computeIfAbsent(tierId, id ->
                        ThreadLocalRandom.current().nextDouble() < tier.missileHitChance());
    }

    /** Перехоплювач дійшов до ракети, але не впорався: іскри є, ракета летить далі. */
    private void missEffect(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.SMOKE, location, 20, 0.5D, 0.5D, 0.5D, 0.05D);
        world.spawnParticle(Particle.CRIT, location, 10, 0.3D, 0.3D, 0.3D, 0.05D);
        world.playSound(location, Sound.ENTITY_ARROW_HIT, 1.6f, 0.8f);
    }

    private boolean interceptTnt(Entity entity) {
        if (!(entity instanceof TNTPrimed tnt)) {
            return false;
        }
        // Та сама мітка, що й у ручного ППО — щоб одне TNT не порахувалося двічі.
        if (tnt.getPersistentDataContainer().has(plugin.interceptedKey(), PersistentDataType.BYTE)) {
            return false;
        }
        tnt.getPersistentDataContainer().set(plugin.interceptedKey(), PersistentDataType.BYTE, (byte) 1);
        tnt.setFuseTicks(1);
        return true;
    }

    private boolean interceptDrone(Entity entity, UUID launcherOwner) {
        if (plugin.drones() == null) {
            return false;
        }
        DroneSession session = plugin.drones().sessionByDrone(entity);
        if (session == null || session.isEnding()) {
            return false;
        }
        Player credited = launcherOwner == null ? null : plugin.getServer().getPlayer(launcherOwner);
        plugin.drones().end(session, DroneSession.EndReason.INTERCEPTED, credited);
        return true;
    }

    private boolean interceptMissile(Entity entity) {
        if (plugin.ballistic() == null) {
            return false;
        }
        BallisticMissile missile = plugin.ballistic().projectileOf(entity);
        if (missile == null || missile.isEnding()) {
            return false;
        }
        if (!missile.registerHit()) {
            plugin.ballistic().notifyDamaged(missile);
            return false; // ракета витримала влучання — потрібні ще
        }
        plugin.ballistic().detonate(missile, missile.currentLocation(), true);
        return true;
    }

    private void creditOwner(UUID owner) {
        if (!countInStats || owner == null || !plugin.getConfig().getBoolean("stats.enabled", true)) {
            return;
        }
        String name = plugin.getServer().getOfflinePlayer(owner).getName();
        plugin.stats().increment(owner, name);
    }

    // ──────────────────────── візуальна частина ────────────────────────

    public void refreshVisuals(AaLauncher launcher) {
        AaTier tier = tier(launcher.tierId());
        if (tier == null) {
            return;
        }
        updateStatus(launcher, tier, launcher.ammoCount(tier) > 0 ? "aa-status-active" : "aa-status-no-ammo",
                launcher.ammoCount(tier), null);
        turret(launcher);
    }

    private void updateStatus(AaLauncher launcher, AaTier tier, String statusKey, int ammo, AaTarget target) {
        String cacheKey = keyOf(launcher.block());
        String signature = statusKey + ":" + ammo;
        if (signature.equals(lastStatus.get(cacheKey)) && target == null) {
            return; // нічого не змінилося — не смикаємо сутність щосканування
        }
        lastStatus.put(cacheKey, signature);

        TextDisplay display = statusDisplay(launcher);
        if (display == null) {
            return;
        }
        // Щоразу, а не лише при створенні: інакше вже поставлені установки застрягли б
        // зі старою дальністю після зміни конфігу.
        display.setViewRange(DisplayUtil.viewRange(hologramDistance));
        display.text(MessageUtil.parse(plugin.message("aa-hologram"), Map.of(
                "name", MessageUtil.plain(MessageUtil.parse(tier.displayName())),
                "status", plugin.message(statusKey),
                "ammo", ammo)));
        if (target != null) {
            aimTurret(launcher, target.aimPoint());
        }
    }

    private TextDisplay statusDisplay(AaLauncher launcher) {
        World world = launcher.block().getWorld();
        UUID id = launcher.displayId();
        if (id != null && world.getEntity(id) instanceof TextDisplay existing) {
            return existing;
        }
        TextDisplay display = world.spawn(launcher.block().getLocation().add(0.5D, 1.9D, 0.5D),
                TextDisplay.class, spawned -> {
                    spawned.setBillboard(Display.Billboard.CENTER);
                    spawned.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
                });
        display.setViewRange(DisplayUtil.viewRange(hologramDistance));
        launcher.displayId(display.getUniqueId());
        return display;
    }

    /** Декоративна башта: маленький блок над установкою, який повертається за ціллю. */
    private BlockDisplay turret(AaLauncher launcher) {
        World world = launcher.block().getWorld();
        UUID id = launcher.turretId();
        if (id != null && world.getEntity(id) instanceof BlockDisplay existing) {
            return existing;
        }
        Material material = Material.matchMaterial(plugin.getConfig().getString(
                "air-defence-system.turret-block", "POLISHED_DEEPSLATE_WALL"));
        Material safe = material == null || !material.isBlock() ? Material.IRON_BLOCK : material;
        BlockDisplay display = world.spawn(launcher.block().getLocation().add(0.5D, 1.0D, 0.5D),
                BlockDisplay.class, spawned -> {
                    spawned.setBlock(safe.createBlockData());
                    float scale = 0.45f;
                    spawned.setTransformation(new Transformation(
                            new Vector3f(-scale / 2f, 0f, -scale / 2f),
                            new AxisAngle4f(),
                            new Vector3f(scale, scale * 1.6f, scale),
                            new AxisAngle4f()));
                    spawned.setBrightness(new Display.Brightness(15, 15));
                    spawned.setTeleportDuration(3);
                });
        launcher.turretId(display.getUniqueId());
        return display;
    }

    private void aimTurret(AaLauncher launcher, Location aim) {
        BlockDisplay turret = turret(launcher);
        if (turret == null) {
            return;
        }
        Location base = launcher.block().getLocation().add(0.5D, 1.0D, 0.5D);
        Vector direction = aim.toVector().subtract(base.toVector());
        if (direction.lengthSquared() < 1.0E-6D) {
            return;
        }
        base.setDirection(direction);
        base.setPitch(0); // башта крутиться по горизонталі, вертикальний нахил виглядає дивно на блоці
        turret.teleport(base);
    }

    public void removeVisuals(AaLauncher launcher) {
        World world = launcher.block().getWorld();
        for (UUID id : new UUID[]{launcher.displayId(), launcher.turretId()}) {
            if (id == null) {
                continue;
            }
            Entity entity = world.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    public void sendMessage(Player player, String key, Map<String, ?> placeholders) {
        String raw = plugin.message(key);
        if (raw.isEmpty()) {
            return;
        }
        player.sendMessage(MessageUtil.parse(plugin.prefix() + raw, placeholders));
    }
}
