package ua.bobster.defence.ballistic;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.missile.MissilePayload;
import org.bukkit.persistence.PersistentDataContainer;
import ua.bobster.defence.util.DisplayUtil;
import ua.bobster.defence.util.MessageUtil;
import ua.bobster.defence.util.SoundUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Артилерійська система: реєстр рівнів установок, запуск ракет і їхня детонація.
 */
public class BallisticManager {

    private record ChunkTicket(UUID worldId, int x, int z) {
    }

    private final BobsterDefence plugin;
    private final BallisticItem item;

    private final NamespacedKey targetXKey;
    private final NamespacedKey targetZKey;
    private final NamespacedKey displayKey;
    private final NamespacedKey cooldownKey;
    private final NamespacedKey reconKey;
    private final NamespacedKey launcherOwnerKey;
    private final NamespacedKey redstoneKey;
    private final NamespacedKey projectileKey;
    private final NamespacedKey hitboxKey;
    private final NamespacedKey missileIdKey;
    private final NamespacedKey missilePowerKey;
    private final NamespacedKey missileTargetXKey;
    private final NamespacedKey missileTargetZKey;

    private final Map<String, LauncherTier> tiers = new LinkedHashMap<>();
    private final List<BallisticMissile> projectiles = new ArrayList<>();
    private final Map<ChunkTicket, Integer> chunkTickets = new java.util.HashMap<>();
    private final BallisticLauncherRegistry registry;
    /** Остання установка, з якою гравець взаємодіяв — саме їй адресуються /ballistic та кліки по карті. */
    private final Map<UUID, Block> selection = new java.util.HashMap<>();

    private BukkitTask tickTask;
    private ReconDrone recon;
    private MissileEffects effects;
    private final RocketCamera camera;

    private boolean enabled;
    private double apexMin;
    private double apexPer;
    private double apexMax;
    private double speed;
    private boolean detonateOnTerrain;
    private boolean warningEnabled;
    private int warningRadius;
    private boolean warningExactCoords;
    private int countdownSeconds;
    private Sound countdownSound;
    private float countdownVolume;
    private float countdownPitch;
    private boolean particlesEnabled;
    private boolean soundsEnabled;
    private boolean explosionFire;
    private boolean explosionBreakBlocks;
    private double hologramDistance;
    private double launchSpeedMultiplier;
    private double ascentSpeedMultiplier;
    private double descentSpeedMultiplier;
    private double launchDistance;
    private double ascentPhase;
    private double ballisticPhase;
    private int maxFlightTicks;
    private int interceptionGraceTicks;
    private double impactRadius;
    private boolean chunkLoading;
    private int chunkRadius;
    private double chunkLookAhead;
    private int startChunkHoldTicks;
    private int chunkTicketGeneration;
    private boolean interceptionFire;
    private boolean interceptionBreakBlocks;

    public BallisticManager(BobsterDefence plugin, BallisticItem item) {
        this.plugin = plugin;
        this.item = item;
        this.targetXKey = new NamespacedKey(plugin, "ballistic_target_x");
        this.targetZKey = new NamespacedKey(plugin, "ballistic_target_z");
        this.displayKey = new NamespacedKey(plugin, "ballistic_display");
        this.cooldownKey = new NamespacedKey(plugin, "ballistic_cooldown");
        this.reconKey = new NamespacedKey(plugin, "ballistic_recon_cooldown");
        this.launcherOwnerKey = new NamespacedKey(plugin, "ballistic_owner");
        this.redstoneKey = new NamespacedKey(plugin, "ballistic_redstone");
        this.projectileKey = new NamespacedKey(plugin, "ballistic_missile");
        this.hitboxKey = new NamespacedKey(plugin, "ballistic_missile_hitbox");
        this.missileIdKey = new NamespacedKey(plugin, "missile_id");
        this.missilePowerKey = new NamespacedKey(plugin, "missile_power");
        this.missileTargetXKey = new NamespacedKey(plugin, "missile_target_x");
        this.missileTargetZKey = new NamespacedKey(plugin, "missile_target_z");
        this.effects = new MissileEffects(plugin);
        this.registry = new BallisticLauncherRegistry(plugin);
        this.camera = new RocketCamera(plugin, this);
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("ballistic.enabled", true);
        this.apexMin = config.getDouble("ballistic.trajectory.minimum-height", 40.0D);
        this.apexPer = config.getDouble("ballistic.trajectory.height-per-block", 0.15D);
        this.apexMax = config.getDouble("ballistic.trajectory.maximum-height", 200.0D);
        this.speed = Math.max(0.5D, config.getDouble("ballistic.trajectory.speed", 3.0D));
        this.detonateOnTerrain = config.getBoolean("ballistic.trajectory.detonate-on-terrain", true);
        this.warningEnabled = config.getBoolean("ballistic.warning.enabled", true);
        this.warningRadius = Math.max(0, config.getInt("ballistic.warning.radius", 250));
        this.warningExactCoords = config.getBoolean("ballistic.warning.exact-coords", true);
        this.countdownSeconds = Math.clamp(config.getInt("ballistic.countdown-seconds", 3), 0, 10);
        this.countdownSound = SoundUtil.resolve(plugin,
                config.getString("ballistic.countdown-sound.name", "BLOCK_NOTE_BLOCK_BASEDRUM"),
                Sound.BLOCK_NOTE_BLOCK_BASEDRUM);
        this.countdownVolume = (float) config.getDouble("ballistic.countdown-sound.volume", 1.0D);
        this.countdownPitch = (float) Math.clamp(
                config.getDouble("ballistic.countdown-sound.pitch", 0.55D), 0.5D, 2.0D);
        this.particlesEnabled = config.getBoolean("ballistic.effects.particles", true);
        this.soundsEnabled = config.getBoolean("ballistic.effects.sounds", true);
        this.explosionFire = config.getBoolean("ballistic.explosion-fire", false);
        this.explosionBreakBlocks = config.getBoolean("ballistic.explosion-break-blocks", true);
        this.hologramDistance = Math.max(0.5D, config.getDouble("ballistic.hologram-distance", 3.0D));
        this.launchSpeedMultiplier = Math.max(0.05D,
                config.getDouble("ballistic.missile.launch-speed-multiplier", 0.2D));
        this.ascentSpeedMultiplier = Math.max(launchSpeedMultiplier,
                config.getDouble("ballistic.missile.ascent-speed-multiplier", 0.75D));
        this.descentSpeedMultiplier = Math.max(0.05D,
                config.getDouble("ballistic.missile.descent-speed-multiplier", 1.3D));
        this.launchDistance = Math.max(1.0D,
                config.getDouble("ballistic.missile.launch-distance-blocks", 8.0D));
        this.interceptionGraceTicks = Math.max(0, config.getInt("ballistic.missile.interception-grace-ticks", 60));
        this.ascentPhase = Math.clamp(config.getDouble("ballistic.missile.ascent-phase", 0.35D), 0.01D, 0.95D);
        this.ballisticPhase = Math.clamp(config.getDouble("ballistic.missile.ballistic-phase", 0.70D), ascentPhase, 0.99D);
        this.maxFlightTicks = Math.max(20,
                config.getInt("ballistic.missile.max-flight-time-seconds", 120)) * 20;
        this.impactRadius = Math.max(0.5D, config.getDouble("ballistic.missile.impact-radius", 2.5D));
        this.chunkLoading = config.getBoolean("ballistic.missile.chunk-loading.enabled", true);
        this.chunkRadius = Math.clamp(config.getInt("ballistic.missile.chunk-loading.radius", 1), 0, 4);
        this.chunkLookAhead = Math.max(0.0D,
                config.getDouble("ballistic.missile.chunk-loading.look-ahead", 48.0D));
        this.startChunkHoldTicks = Math.clamp(
                config.getInt("ballistic.missile.chunk-loading.start-hold-seconds", 30), 0, 300) * 20;
        this.interceptionFire = config.getBoolean("ballistic.interception.fire", false);
        this.interceptionBreakBlocks = config.getBoolean("ballistic.interception.break-blocks", true);
        if (effects != null) {
            effects.reload();
        }
        camera.reload();

        loadTiers(config.getConfigurationSection("ballistic.launcher"));
    }

    private void loadTiers(ConfigurationSection section) {
        tiers.clear();
        if (section == null) {
            plugin.getLogger().warning("ballistic.launcher відсутній — установок не буде");
            return;
        }
        int level = 0;
        for (String id : section.getKeys(false)) {
            ConfigurationSection tier = section.getConfigurationSection(id);
            if (tier == null) {
                continue;
            }
            level++;
            tiers.put(id.toLowerCase(), new LauncherTier(
                    id.toLowerCase(),
                    tier.getInt("level", level),
                    tier.getString("name", "<gold>🚀 BOBSTER " + id.toUpperCase()),
                    Math.max(16, tier.getInt("range", 300)),
                    Math.max(1, tier.getInt("ammo", 1)),
                    Math.max(0.5D, tier.getDouble("explosion-power", 4.0D)),
                    Math.max(1, tier.getInt("hits-to-intercept", 1)),
                    Math.max(0.0D, tier.getDouble("airburst-power", 5.0D)),
                    Math.max(0, tier.getInt("cooldown-seconds", 30)),
                    Math.max(0.3D, tier.getDouble("speed", speed)),
                    tier.getBoolean("loses-control-when-hit", false),
                    tier.getStringList("lore"),
                    tier.getString("rocket.name", "<gold>🚀 " + id.toUpperCase() + " rocket"),
                    tier.getStringList("rocket.lore")));
        }
    }

    public void start() {
        registry.load();
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        recon = new ReconDrone(plugin, this);
        recon.start();
        camera.start();
    }

    public ReconDrone recon() {
        return recon;
    }

    public RocketCamera camera() {
        return camera;
    }

    public void shutdown() {
        camera.shutdown();
        if (recon != null) {
            recon.shutdown();
            recon = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (BallisticMissile projectile : new ArrayList<>(projectiles)) {
            projectile.beginEnding();
            projectile.cleanup();
        }
        projectiles.clear();
        releaseAllChunkTickets();
    }

    private void tickAll() {
        // Ідемо по копії: tick() може детонувати ракету, а detonate() прибирає її зі списку.
        // З живим ітератором це давало ConcurrentModificationException і зривало весь тік —
        // разом з усіма іншими ракетами, що летіли в цю мить.
        for (BallisticMissile projectile : new ArrayList<>(projectiles)) {
            boolean alive;
            try {
                alive = projectile.tick();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Помилка в тіку ракети: " + ex);
                projectile.beginEnding();
                projectile.cleanup();
                alive = false;
            }
            if (!alive) {
                projectiles.remove(projectile);
            }
        }
    }

    // ─────────────────────────────── доступ ───────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public NamespacedKey projectileKey() {
        return projectileKey;
    }

    BobsterDefence plugin() {
        return plugin;
    }

    public boolean chunkLoading() {
        return chunkLoading;
    }

    public int chunkRadius() {
        return chunkRadius;
    }

    public double chunkLookAhead() {
        return chunkLookAhead;
    }

    public BallisticMissile latestMissile(UUID playerId) {
        for (int index = projectiles.size() - 1; index >= 0; index--) {
            BallisticMissile missile = projectiles.get(index);
            if (!missile.isEnding() && missile.owner().type() == ua.bobster.defence.combat.CombatPrincipalType.PLAYER && missile.shooter().equals(playerId)) {
                return missile;
            }
        }
        return null;
    }

    void retainChunk(World world, long key) {
        if (!chunkLoading || world == null) {
            return;
        }
        int x = (int) (key >> 32);
        int z = (int) key;
        ChunkTicket ticket = new ChunkTicket(world.getUID(), x, z);
        int references = chunkTickets.merge(ticket, 1, Integer::sum);
        if (references == 1) {
            world.addPluginChunkTicket(x, z, plugin);
        }
    }

    void releaseChunk(World world, long key) {
        if (world == null) {
            return;
        }
        int x = (int) (key >> 32);
        int z = (int) key;
        ChunkTicket ticket = new ChunkTicket(world.getUID(), x, z);
        Integer references = chunkTickets.get(ticket);
        if (references == null) {
            return;
        }
        if (references > 1) {
            chunkTickets.put(ticket, references - 1);
            return;
        }
        chunkTickets.remove(ticket);
        world.removePluginChunkTicket(x, z, plugin);
    }

    public NamespacedKey hitboxKey() {
        return hitboxKey;
    }

    public MissileEffects effects() {
        return effects;
    }

    public int maxFlightTicks() {
        return maxFlightTicks;
    }

    public int interceptionGraceTicks() {
        return interceptionGraceTicks;
    }

    public double impactRadius() {
        return impactRadius;
    }

    /** Фаза польоту за часткою пройденого шляху. */
    public MissileState stateFor(double progress, double travelled) {
        if (travelled < launchDistance) {
            return MissileState.LAUNCH;
        }
        if (progress < ascentPhase) {
            return MissileState.ASCENT;
        }
        if (progress < ballisticPhase) {
            return MissileState.BALLISTIC;
        }
        return MissileState.DESCENT;
    }

    /**
     * Швидкість у блоках за тік для поточної фази. Базова — та, що задана ракеті;
     * старт повільніший, пікірування найшвидше, щоб відчувалося падіння боєголовки.
     */
    public double speedFor(MissileState state, LauncherTier tier) {
        return switch (state) {
            case LAUNCH -> tier.speed() * launchSpeedMultiplier;
            case ASCENT -> tier.speed() * ascentSpeedMultiplier;
            case DESCENT -> tier.speed() * descentSpeedMultiplier;
            default -> tier.speed();
        };
    }

    /**
     * Мітки ракети. Головна — {@code ballistic_missile}: саме за нею ППО й усі перевірки
     * відрізняють ракету від звичайного тризуба, який кинув гравець.
     */
    void tagMissile(PersistentDataContainer pdc, UUID missileId, CombatPrincipal owner,
                    LauncherTier tier, Location impact) {
        pdc.set(projectileKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(missileIdKey, PersistentDataType.STRING, missileId.toString());
        pdc.set(missilePowerKey, PersistentDataType.DOUBLE, tier.explosionPower());
        pdc.set(missileTargetXKey, PersistentDataType.INTEGER, impact.getBlockX());
        pdc.set(missileTargetZKey, PersistentDataType.INTEGER, impact.getBlockZ());
        if (owner != null) {
            pdc.set(plugin.ownerKey(), PersistentDataType.STRING, owner.id().toString());
            plugin.combatIdentity().write(pdc, owner);
        }
    }

    /**
     * Прибирає ракетні тризуби, що лишилися після падіння сервера.
     * Звичайні тризуби гравців не чіпаються — перевіряється саме наша мітка.
     */
    public void removeStrayMissiles() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(projectileKey, PersistentDataType.BYTE)
                        || entity.getPersistentDataContainer().has(hitboxKey, PersistentDataType.BYTE)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Прибрано залишених ракет: " + removed);
        }
    }

    public NamespacedKey ownerKey() {
        return plugin.ownerKey();
    }

    public boolean detonateOnTerrain() {
        return detonateOnTerrain;
    }

    public boolean particlesEnabled() {
        return particlesEnabled;
    }

    public boolean soundsEnabled() {
        return soundsEnabled;
    }

    public BallisticItem item() {
        return item;
    }

    public List<LauncherTier> tiers() {
        return new ArrayList<>(tiers.values());
    }

    public LauncherTier tier(String id) {
        if (id == null) {
            return null;
        }
        LauncherTier direct = tiers.get(id.toLowerCase());
        return direct != null ? direct : tiers.get(canonical(id));
    }

    /**
     * Старі назви (mk1…mk5) лишаються робочими: у вже поставлених установок саме вони
     * записані в PDC блока, і без цього вони перетворилися б на звичайні диспенсери.
     * MK-V більше немає, тому він зіставляється з найважчою наявною установкою.
     */
    private static String canonical(String id) {
        return switch (id.toLowerCase()) {
            case "mk1" -> "fp5";
            case "mk2" -> "satan";
            case "mk3" -> "fp7";
            case "mk4", "mk5" -> "fp9";
            default -> id.toLowerCase();
        };
    }

    /** @return установка на цьому блоці або null, якщо це звичайний диспенсер */
    public BallisticLauncher launcherAt(Block block) {
        if (block == null || block.getType() != Material.DISPENSER) {
            return null;
        }
        BallisticLauncher launcher = wrap(block);
        return launcher.tierId() == null ? null : launcher;
    }

    public BallisticLauncher wrap(Block block) {
        return new BallisticLauncher(block, item, item.tierKey(),
                targetXKey, targetZKey, displayKey, cooldownKey, reconKey, launcherOwnerKey, redstoneKey);
    }

    /** Позначки для хрестика на карті: гравець → останні координати його цілі. */
    private final Map<UUID, int[]> markers = new java.util.HashMap<>();

    public int[] marker(UUID playerId) {
        return markers.get(playerId);
    }

    public void marker(UUID playerId, int x, int z) {
        markers.put(playerId, new int[]{x, z});
    }

    public void select(Player player, Block block) {
        selection.put(player.getUniqueId(), block);
        BallisticLauncher launcher = launcherAt(block);
        if (launcher != null && launcher.hasTarget()) {
            marker(player.getUniqueId(), launcher.targetX(), launcher.targetZ());
        }
    }

    public Block selected(Player player) {
        List<BallisticLauncherRegistry.Entry> selected = registry.selected(player.getUniqueId());
        if (!selected.isEmpty()) {
            return selected.getFirst().block(true);
        }
        return selection.get(player.getUniqueId());
    }

    public List<BallisticLauncher> selectedLaunchers(Player player) {
        List<BallisticLauncherRegistry.Entry> entries = registry.selected(player.getUniqueId());
        if (entries.isEmpty()) {
            Block block = selection.get(player.getUniqueId());
            BallisticLauncher launcher = launcherAt(block);
            return launcher == null ? List.of() : List.of(launcher);
        }
        List<BallisticLauncher> result = new ArrayList<>();
        for (BallisticLauncherRegistry.Entry entry : entries) {
            BallisticLauncher launcher = launcherAt(entry.block(true));
            if (launcher != null && player.getUniqueId().equals(launcher.owner())) {
                result.add(launcher);
            }
        }
        return result;
    }

    public BallisticLauncherRegistry registry() {
        return registry;
    }

    public void registerLauncher(Block block, UUID owner) {
        registry.register(block, owner);
    }

    public void registerMigratedLauncher(Block block, UUID owner) {
        registry.registerMigrated(block, owner);
    }

    public void unregisterLauncher(Block block) {
        registry.unregister(block);
    }

    public int clearLaunchers(UUID owner) {
        return registry.clear(owner);
    }

    public void selectLaunchers(Player player, List<BallisticLauncherRegistry.Entry> entries) {
        registry.select(player.getUniqueId(), entries);
    }

    public void clearSelection(Player player) {
        registry.select(player.getUniqueId(), List.of());
        selection.remove(player.getUniqueId());
    }

    public void forget(UUID playerId) {
        selection.remove(playerId);
    }

    /** @return ракета, корпусом якої є ця сутність (для ППО), або null */
    public BallisticMissile projectileOf(Entity entity) {
        if (entity == null
                || !entity.getPersistentDataContainer().has(projectileKey, PersistentDataType.BYTE)) {
            return null;
        }
        for (BallisticMissile missile : projectiles) {
            if (missile.entity().getUniqueId().equals(entity.getUniqueId())) {
                return missile;
            }
        }
        return null;
    }

    /**
     * @return ракета, чий hitbox — ця сутність. Потрібно для ручного ППО: стріла з лука
     *         не стикається зі снарядами, тож у сам тризуб влучити неможливо.
     */
    public BallisticMissile missileByHitbox(Entity entity) {
        if (entity == null
                || !entity.getPersistentDataContainer().has(hitboxKey, PersistentDataType.BYTE)) {
            return null;
        }
        for (BallisticMissile missile : projectiles) {
            if (missile.hitbox().getUniqueId().equals(entity.getUniqueId())) {
                return missile;
            }
        }
        return null;
    }

    // ────────────────────────────── табличка ──────────────────────────────

    public void refreshDisplay(BallisticLauncher launcher) {
        LauncherTier tier = tier(launcher.tierId());
        if (tier == null) {
            return;
        }
        Block block = launcher.block();
        World world = block.getWorld();
        Location above = block.getLocation().add(0.5D, 1.15D, 0.5D);

        TextDisplay display = null;
        UUID id = launcher.displayId();
        if (id != null && world.getEntity(id) instanceof TextDisplay existing) {
            display = existing;
        }
        if (display == null) {
            display = world.spawn(above, TextDisplay.class, spawned -> {
                spawned.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                spawned.setDefaultBackground(false);
                spawned.setBackgroundColor(org.bukkit.Color.fromARGB(120, 0, 0, 0));
            });
            launcher.displayId(display.getUniqueId());
        }
        // Виставляємо щоразу, а не лише при створенні: інакше вже поставлені установки
        // застрягли б зі старою дальністю після зміни конфігу.
        display.setViewRange(DisplayUtil.viewRange(hologramDistance));

        String targetText = launcher.hasTarget()
                ? launcher.targetX() + ", " + launcher.targetZ()
                : plugin.getConfig().getString("messages.ballistic-no-target-short", "НЕ ЗАДАНО");
        display.text(MessageUtil.parse(plugin.message("ballistic-hologram"), Map.of(
                "name", stripTags(tier.displayName()),
                "target", targetText,
                "ammo", launcher.ammoCount(tier),
                "need", tier.ammo())));
    }

    public void removeDisplay(BallisticLauncher launcher) {
        UUID id = launcher.displayId();
        if (id == null) {
            return;
        }
        Entity entity = launcher.block().getWorld().getEntity(id);
        if (entity instanceof TextDisplay) {
            entity.remove();
        }
    }

    private String stripTags(String miniMessage) {
        return MessageUtil.plain(MessageUtil.parse(miniMessage));
    }


    // ─────────────────────────────── запуск ───────────────────────────────

    /** @return текстовий ключ помилки або null, якщо запуск дозволено */
    public String validate(BallisticLauncher launcher, LauncherTier tier) {
        if (!enabled) {
            return "ballistic-disabled";
        }
        if (!launcher.hasTarget()) {
            return "ballistic-no-target";
        }
        Location origin = launcher.block().getLocation();
        double distance = horizontalDistance(origin, launcher.targetX(), launcher.targetZ());
        if (distance > tier.range()) {
            return "ballistic-out-of-range";
        }
        if (launcher.ammoCount(tier) < 1) {
            return "ballistic-no-ammo";
        }
        long elapsed = System.currentTimeMillis() - launcher.lastLaunch();
        if (elapsed < tier.cooldownSeconds() * 1000L) {
            return "ballistic-cooldown";
        }
        return null;
    }

    public double horizontalDistance(Location origin, int x, int z) {
        double dx = origin.getX() - x;
        double dz = origin.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public void launch(Player operator, BallisticLauncher launcher, LauncherTier tier) {
        launch(operator.getUniqueId(), operator, launcher, tier);
    }

    public void launchRedstone(BallisticLauncher launcher, LauncherTier tier) {
        UUID owner = launcher.owner();
        if (owner != null) {
            launch(owner, plugin.getServer().getPlayer(owner), launcher, tier);
        }
    }

    private void launch(UUID owner, Player operator, BallisticLauncher launcher, LauncherTier tier) {
        ItemStack ammo = launcher.consumeAmmo(tier);
        if (ammo == null) {
            if (operator != null) {
                sendMessage(operator, "ballistic-no-ammo", Map.of());
            }
            return;
        }
        launcher.lastLaunch(System.currentTimeMillis());
        refreshDisplay(launcher);

        Block block = launcher.block();
        World world = block.getWorld();
        holdLaunchChunk(block.getLocation());
        int targetX = launcher.targetX();
        int targetZ = launcher.targetZ();
        MissilePayload payload = plugin.payloads().read(ammo);

        countdown(operator, world, block.getLocation().add(0.5D, 1.0D, 0.5D), () ->
                resolveImpact(world, targetX, targetZ, impact -> fire(owner, operator, block, tier, payload, impact)));
    }

    private void countdown(Player operator, World world, Location location, Runnable onFinish) {
        if (countdownSeconds <= 0) {
            onFinish.run();
            return;
        }
        for (int second = countdownSeconds; second > 0; second--) {
            int remaining = second;
            long delay = (countdownSeconds - second) * 20L;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (soundsEnabled) {
                    world.playSound(location, countdownSound, countdownVolume, countdownPitch);
                }
                if (operator != null && operator.isOnline()) {
                    operator.sendActionBar(MessageUtil.parse(
                            plugin.message("ballistic-countdown"), Map.of("seconds", remaining)));
                }
            }, delay);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, onFinish, countdownSeconds * 20L);
    }

    /**
     * Точку удару рахуємо не за курсором гравця, а за рельєфом: гравець задає лише X/Z.
     * Чанк вантажимо асинхронно — ціль може бути за 2500 блоків, синхронне завантаження
     * такого чанка помітно підвісило б сервер.
     */
    private void resolveImpact(World world, int x, int z, java.util.function.Consumer<Location> callback) {
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk ->
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        callback.accept(new Location(world, x + 0.5D, findImpactY(world, x, z), z + 0.5D))));
    }

    /**
     * У Незері звичайний getHighestBlockYAt дав би стелю з бедроку, тому скануємо вниз
     * від стелі придатної зони.
     */
    private int findImpactY(World world, int x, int z) {
        int from = world.getEnvironment() == World.Environment.NETHER
                ? 120
                : Math.min(world.getMaxHeight() - 1, world.getHighestBlockYAt(x, z) + 1);
        for (int y = from; y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getType() != Material.AIR) {
                return y + 1;
            }
        }
        return world.getMinHeight() + 1;
    }

    private void fire(UUID owner, Player operator, Block launcherBlock, LauncherTier tier, MissilePayload payload, Location impact) {
        World world = launcherBlock.getWorld();
        Location start = launcherBlock.getLocation().add(0.5D, 2.0D, 0.5D);
        holdLaunchChunk(start);

        BallisticTrajectory trajectory = BallisticTrajectory.between(
                start, impact, apexMin, apexPer, apexMax, world.getMaxHeight());

        BallisticMissile missile = new BallisticMissile(
                this, tier, trajectory, CombatPrincipal.player(owner), payload, start, impact);
        projectiles.add(missile);
        if (operator != null) {
            camera.onMissileLaunched(operator, launcherBlock, missile);
        }

        effects.launch(start);

        if (operator != null) {
            sendMessage(operator, "ballistic-launched", Map.of(
                    "x", impact.getBlockX(),
                    "z", impact.getBlockZ(),
                    "seconds", (int) Math.ceil(trajectory.length() / tier.speed() / 20.0D)));
        }
        Location obstruction = launchObstruction(launcherBlock);
        if (obstruction != null) {
            if (operator != null) {
                sendMessage(operator, "ballistic-launch-obstructed", Map.of());
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> detonate(missile, obstruction, false));
            return;
        }
        warnNearby(impact, owner);
    }

    private Location launchObstruction(Block launcherBlock) {
        for (int offset = 1; offset <= 3; offset++) {
            Block block = launcherBlock.getRelative(0, offset, 0);
            if (!block.getType().isAir() && !block.isLiquid() && !block.isPassable()) {
                return block.getLocation().add(0.5D, 0.5D, 0.5D);
            }
        }
        return null;
    }

    public boolean launch(MissileLaunchRequest request) {
        if (!enabled || request == null || request.owner() == null || request.origin() == null || request.target() == null) {
            return false;
        }
        World world = request.origin().getWorld();
        LauncherTier tier = tier(request.tierId());
        if (world == null || request.target().getWorld() == null || !world.equals(request.target().getWorld()) || tier == null) {
            return false;
        }
        if (horizontalDistance(request.origin(), request.target().getBlockX(), request.target().getBlockZ()) > tier.range()) {
            return false;
        }
        Location start = request.origin().clone();
        Location impact = request.target().clone();
        holdLaunchChunk(start);
        BallisticTrajectory trajectory = BallisticTrajectory.between(start, impact, apexMin, apexPer, apexMax, world.getMaxHeight());
        projectiles.add(new BallisticMissile(this, tier, trajectory, request.owner(), MissilePayload.explosive(), start, impact));
        effects.launch(start);
        warnNearby(impact, request.owner().id());
        return true;
    }

    private void holdLaunchChunk(Location location) {
        if (!chunkLoading || startChunkHoldTicks <= 0 || location.getWorld() == null) {
            return;
        }
        World world = location.getWorld();
        long key = ((long) (location.getBlockX() >> 4) << 32) | ((location.getBlockZ() >> 4) & 0xFFFFFFFFL);
        int generation = chunkTicketGeneration;
        retainChunk(world, key);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (generation == chunkTicketGeneration) {
                releaseChunk(world, key);
            }
        }, startChunkHoldTicks);
    }

    private void releaseAllChunkTickets() {
        chunkTicketGeneration++;
        for (ChunkTicket ticket : new ArrayList<>(chunkTickets.keySet())) {
            World world = plugin.getServer().getWorld(ticket.worldId());
            if (world != null) {
                world.removePluginChunkTicket(ticket.x(), ticket.z(), plugin);
            }
        }
        chunkTickets.clear();
    }

    public void cancelAutonomous() {
        for (BallisticMissile missile : new ArrayList<>(projectiles)) {
            if (missile.owner().type() != ua.bobster.defence.combat.CombatPrincipalType.STRATEGIC_STATE || !missile.beginEnding()) {
                continue;
            }
            projectiles.remove(missile);
            missile.cleanup();
        }
    }

    private void warnNearby(Location impact, java.util.UUID shooter) {
        if (!warningEnabled) {
            return;
        }
        // Якщо точка удару лежить у виділеній території — оголошуємо тривогу по ній,
        // це зрозуміліше за голі координати. Старе попередження лишається для «нічиєї» землі.
        if (plugin.airRaid() != null && plugin.airRaid().onMissile(impact, shooter)) {
            return;
        }
        String key = warningExactCoords ? "ballistic-warning-exact" : "ballistic-warning-vague";
        Component message = MessageUtil.parse(plugin.message(key), Map.of(
                "x", impact.getBlockX(),
                "z", impact.getBlockZ()));
        for (Player player : impact.getWorld().getPlayers()) {
            if (player.getLocation().distance(impact) > warningRadius) {
                continue;
            }
            player.sendMessage(message);
        }
    }

    // ────────────────────────────── детонація ──────────────────────────────

    /**
     * @param airburst true — ракету збили в повітрі, вибух слабший
     */
    public void detonate(BallisticMissile projectile, Location location, boolean airburst) {
        if (!projectile.beginEnding()) {
            return;
        }
        projectiles.remove(projectile);

        World world = location.getWorld();
        boolean spent = false;
        if (world != null) {
            if (airburst) {
                // Перехоплення — не «розчинення» ракети: боєголовка нікуди не поділася
                // і спрацьовує там, де її дістали. Збита біля землі ракета лишає слід.
                effects.interception(location);
            }
            double power = airburst ? projectile.tier().airburstPower() : projectile.tier().explosionPower() * projectile.powerMultiplier();
            if (projectile.payload().kind() == MissilePayload.Kind.EXPLOSIVE && power > 0) {
                world.createExplosion(projectile.hitbox(), location, (float) power, airburst ? interceptionFire : explosionFire, airburst ? interceptionBreakBlocks : explosionBreakBlocks);
            } else if (projectile.payload().kind() == MissilePayload.Kind.POTION && power > 0) {
                plugin.payloads().applyPotion(projectile.payload(), location, power);
            } else if (projectile.payload().kind() == MissilePayload.Kind.EMPTY && !airburst) {
                plugin.payloads().leaveSpent(projectile.detachSpent(), location, projectile.heading());
                spent = true;
            }
        }
        if (!spent) {
            projectile.cleanup();
        }

        Player shooter = projectile.owner().type() == ua.bobster.defence.combat.CombatPrincipalType.PLAYER
                ? plugin.getServer().getPlayer(projectile.shooter()) : null;
        if (shooter != null && shooter.isOnline()) {
            sendMessage(shooter, airburst ? "ballistic-intercepted-shooter" : "ballistic-impact", Map.of(
                    "x", location.getBlockX(),
                    "z", location.getBlockZ()));
        }
    }

    /** Повідомляє стрільця, що ракета вціліла, але втратила керування. Один раз на ракету. */
    public void notifyDamaged(BallisticMissile projectile) {
        if (!projectile.isDamaged() || !projectile.markDamagedNotified()) {
            return;
        }
        Player shooter = projectile.owner().type() == ua.bobster.defence.combat.CombatPrincipalType.PLAYER
                ? plugin.getServer().getPlayer(projectile.shooter()) : null;
        if (shooter != null && shooter.isOnline()) {
            sendMessage(shooter, "ballistic-damaged", Map.of());
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
