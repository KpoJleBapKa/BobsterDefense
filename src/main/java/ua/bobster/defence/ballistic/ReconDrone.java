package ua.bobster.defence.ballistic;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.FluidCollisionMode;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;
import ua.bobster.defence.util.SoundUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Розвідувальний дрон пускової установки.
 * <p>
 * На відміну від FPV-камікадзе він не вибухає: його задача — злітати, роздивитися місцевість
 * і <b>повернутися на базу</b>. Не повернувся (збили, вийшов час, вийшов з гри) — установка
 * лишається без розвідника на {@code lost-cooldown-minutes} хвилин.
 * <p>
 * Стаціонарна ППО його не бачить: сутність не має мітки цілі. Але це звичайна стійка з бронею,
 * тож її спокійно збиває стріла з лука або чужий FPV-дрон, що влетів у неї.
 */
public class ReconDrone {

    private static final class Session {
        final UUID player;
        final Block launcherBlock;
        final Location origin;
        final GameMode gameMode;
        final float flySpeed;
        final boolean allowFlight;
        final ArmorStand model;
        Location previous;
        int hearts;
        int lastHitTick = -999;
        int ticks;
        boolean targetSet;
        boolean ending;

        Session(UUID player, Block launcherBlock, Location origin, GameMode gameMode,
                float flySpeed, boolean allowFlight, ArmorStand model) {
            this.player = player;
            this.launcherBlock = launcherBlock;
            this.origin = origin;
            this.gameMode = gameMode;
            this.flySpeed = flySpeed;
            this.allowFlight = allowFlight;
            this.model = model;
        }
    }

    /** Чому політ завершився — від цього залежить, чи буде штрафна перезарядка. */
    private enum EndReason {
        RETURNED,
        DESTROYED,
        TIMEOUT,
        SHUTDOWN
    }

    private final BobsterDefence plugin;
    private final BallisticManager manager;
    private final org.bukkit.NamespacedKey reconKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private BukkitTask tickTask;

    private boolean enabled;
    private int flightTicks;
    private double speed;
    private double launchHeight;
    private double landingRadius;
    private double landingHeight;
    private int returnDelayTicks;
    private long lostCooldownMillis;
    private int targetRange;
    private boolean visibleStand;
    private Material chestplate;
    private int maxHearts;
    private int hitCooldownTicks;
    private boolean soundEnabled;
    private Sound sound;
    private float soundVolume;
    private float soundPitch;
    private int soundInterval;

    public ReconDrone(BobsterDefence plugin, BallisticManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.reconKey = new org.bukkit.NamespacedKey(plugin, "recon_drone");
        reload();
    }

    public org.bukkit.NamespacedKey reconKey() {
        return reconKey;
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("ballistic.recon.enabled", true);
        this.flightTicks = Math.max(20, config.getInt("ballistic.recon.flight-time", 600) * 20);
        this.speed = Math.max(0.1D, config.getDouble("ballistic.recon.speed", 1.3D));
        this.launchHeight = Math.max(2.0D, config.getDouble("ballistic.recon.launch-height", 5.0D));
        this.landingRadius = Math.max(0.5D, config.getDouble("ballistic.recon.landing-radius", 1.5D));
        this.landingHeight = Math.max(0.5D, config.getDouble("ballistic.recon.landing-height", 2.5D));
        this.returnDelayTicks = Math.max(0, config.getInt("ballistic.recon.return-delay-seconds", 5)) * 20;
        this.lostCooldownMillis = Math.max(0, config.getInt("ballistic.recon.lost-cooldown-minutes", 30))
                * 60_000L;
        this.targetRange = Math.clamp(config.getInt("ballistic.recon.target-range", 512), 16, 1024);
        this.visibleStand = config.getBoolean("ballistic.recon.visible-stand", true);
        Material material = Material.matchMaterial(
                config.getString("ballistic.recon.chestplate", "CHAINMAIL_CHESTPLATE"));
        this.chestplate = material == null ? Material.CHAINMAIL_CHESTPLATE : material;
        this.maxHearts = Math.max(1, config.getInt("ballistic.recon.hearts", 5));
        this.hitCooldownTicks = Math.max(1, config.getInt("ballistic.recon.hit-cooldown-ticks", 10));
        this.soundEnabled = config.getBoolean("ballistic.recon.sound.enabled", true);
        this.sound = SoundUtil.resolve(plugin,
                config.getString("ballistic.recon.sound.name", "ENTITY_BEE_LOOP"), Sound.ENTITY_BEE_LOOP);
        this.soundVolume = (float) config.getDouble("ballistic.recon.sound.volume", 0.35D);
        this.soundPitch = (float) Math.clamp(config.getDouble("ballistic.recon.sound.pitch", 1.9D), 0.5D, 2.0D);
        this.soundInterval = Math.max(1, config.getInt("ballistic.recon.sound.interval-ticks", 15));
    }

    public void start() {
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Session session : new ArrayList<>(sessions.values())) {
            end(session, EndReason.SHUTDOWN);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isActive(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public int targetRange() {
        return targetRange;
    }

    // ─────────────────────────────── запуск ───────────────────────────────

    public void launch(Player player, BallisticLauncher launcher, LauncherTier tier) {
        // Раніше ці випадки просто мовчали, і зовні це виглядало як «нічого не працює».
        if (!enabled) {
            manager.sendMessage(player, "recon-disabled", Map.of());
            return;
        }
        if (isActive(player)) {
            manager.sendMessage(player, "recon-already-flying", Map.of());
            return;
        }
        if (plugin.drones() != null && plugin.drones().isFlying(player)) {
            manager.sendMessage(player, "recon-busy-drone", Map.of());
            return;
        }
        if (manager.camera().isActive(player)) {
            manager.sendMessage(player, "rocket-camera-busy", Map.of());
            return;
        }
        long cooldownLeft = launcher.reconCooldown() - System.currentTimeMillis();
        if (cooldownLeft > 0) {
            manager.sendMessage(player, "recon-cooldown",
                    Map.of("minutes", (cooldownLeft / 60_000L) + 1));
            return;
        }

        World world = launcher.block().getWorld();
        Location start = launcher.block().getLocation().add(0.5D, launchHeight, 0.5D);
        start.setYaw(player.getLocation().getYaw());

        ArmorStand model;
        try {
            model = spawnModel(world, start);
        } catch (RuntimeException ex) {
            // Якщо сутність не створилася, гравець має про це почути, а не гадати:
            // раніше виняток тут обривав запуск, і зовні це виглядало як «кнопка не працює».
            plugin.getLogger().log(Level.SEVERE, "Не вдалося створити розвідника", ex);
            manager.sendMessage(player, "recon-spawn-failed", Map.of());
            return;
        }
        Session session = new Session(player.getUniqueId(), launcher.block(),
                player.getLocation().clone(), player.getGameMode(), player.getFlySpeed(),
                player.getAllowFlight(), model);
        session.hearts = maxHearts;
        session.previous = start.clone();
        sessions.put(player.getUniqueId(), session);

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(start);
        player.setFlySpeed((float) Math.clamp(0.1D * speed, 0.001D, 1.0D));
        world.playSound(start, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.6f);

        manager.sendMessage(player, "recon-launched", Map.of(
                "minutes", flightTicks / 20 / 60,
                "range", tier.range()));
    }

    private ArmorStand spawnModel(World world, Location location) {
        return world.spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(visibleStand);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setPersistent(false);
            stand.setSilent(true);
            // Корпус завалено на 90° — стійка «лежить» у польоті, як апарат, а не як манекен.
            stand.setBodyPose(new EulerAngle(Math.toRadians(90), 0, 0));
            if (stand.getEquipment() != null) {
                // Тільки setChestplate: шанс дропу задається лише мобам, а стійка мобом не є —
                // setChestplateDropChance тут кидає виняток і обриває весь запуск.
                // Від дропу нас і так рятує EntityDeathEvent у BallisticListener.
                stand.getEquipment().setChestplate(new ItemStack(chestplate));
            }
            // Мітка потрібна лише щоб прибрати дроп при знищенні. Мітки цілі тут навмисно
            // немає — саме тому стаціонарна ППО розвідника не бачить.
            stand.getPersistentDataContainer().set(reconKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ─────────────────────────────── політ ───────────────────────────────

    private void tick() {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.ending) {
                continue;
            }
            Player player = plugin.getServer().getPlayer(session.player);
            if (player == null || !player.isOnline()) {
                end(session, EndReason.DESTROYED);
                continue;
            }
            if (!session.model.isValid() || session.model.isDead()) {
                end(session, EndReason.DESTROYED); // збили з лука або протаранили
                continue;
            }
            if (++session.ticks >= flightTicks) {
                end(session, EndReason.TIMEOUT);
                continue;
            }

            Location current = player.getLocation();
            if (checkCollision(session, player, current)) {
                continue; // розвідника добили об рельєф
            }
            moveModel(session, current);
            ambientSound(session, current);
            session.previous = current.clone();

            if (session.ticks % 10 == 0) {
                // Перші секунди повернення не рахуємо: розвідник злітає просто з установки
                // і інакше «сідав» би назад тієї ж миті, коли з'явився.
                if (session.ticks >= returnDelayTicks && hasLanded(current, session)) {
                    end(session, EndReason.RETURNED);
                    continue;
                }
                hud(player, session);
            }
        }
    }

    /**
     * Удар об рельєф. Оператор летить у spectator і крізь блоки проходить вільно, тому
     * зіткнення рахуємо самі — ray trace'ом між позиціями двох сусідніх тіків.
     * <p>
     * Розвідник не вибухає, як FPV: він просто втрачає серце і його виштовхує назад.
     * П'ять зіткнень — і апарат розбито.
     *
     * @return true, якщо розвідника знищено і сесія вже завершена
     */
    private boolean checkCollision(Session session, Player player, Location current) {
        if (session.previous == null || !current.getWorld().equals(session.previous.getWorld())) {
            session.previous = current.clone();
            return false;
        }
        Vector delta = current.toVector().subtract(session.previous.toVector());
        double travelled = delta.length();
        if (travelled < 1.0E-4D) {
            return false;
        }
        RayTraceResult hit = current.getWorld().rayTraceBlocks(session.previous,
                delta.clone().multiply(1.0D / travelled), travelled, FluidCollisionMode.NEVER, true);
        if (hit == null) {
            return false;
        }
        // Пауза між ударами: без неї одне влітання в стіну з'їдало б усі серця за кілька тіків.
        if (session.ticks - session.lastHitTick < hitCooldownTicks) {
            return false;
        }
        session.lastHitTick = session.ticks;
        session.hearts--;

        // Виштовхуємо назад від точки удару, щоб оператор не лишився всередині рельєфу.
        Location safe = hit.getHitPosition().toLocation(current.getWorld())
                .subtract(delta.clone().multiply(0.8D / travelled));
        safe.setYaw(current.getYaw());
        safe.setPitch(current.getPitch());
        player.teleport(safe);
        session.previous = safe.clone();

        World world = current.getWorld();
        world.playSound(safe, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.7f);
        world.spawnParticle(Particle.SMOKE, safe, 12, 0.2D, 0.2D, 0.2D, 0.02D);

        if (session.hearts <= 0) {
            end(session, EndReason.DESTROYED);
            return true;
        }
        manager.sendMessage(player, "recon-damaged",
                Map.of("hearts", session.hearts, "max", maxHearts));
        return false;
    }

    /** Гудіння апарата: тихіше за бойові дрони, але сусіди його чують. */
    private void ambientSound(Session session, Location location) {
        if (!soundEnabled || session.ticks % soundInterval != 0) {
            return;
        }
        location.getWorld().playSound(location, sound, soundVolume, soundPitch);
    }

    /**
     * Розвідник повернувся тільки тоді, коли фізично сів на саму установку:
     * завис над її блоком і опустився майже впритул. Просто пролетіти поруч замало.
     */
    private boolean hasLanded(Location current, Session session) {
        Block base = session.launcherBlock;
        if (!current.getWorld().equals(base.getWorld())) {
            return false;
        }
        double dx = current.getX() - (base.getX() + 0.5D);
        double dz = current.getZ() - (base.getZ() + 0.5D);
        if (dx * dx + dz * dz > landingRadius * landingRadius) {
            return false;
        }
        double above = current.getY() - (base.getY() + 1.0D);
        return above >= -0.6D && above <= landingHeight;
    }

    private void moveModel(Session session, Location current) {
        Location model = current.clone()
                .add(current.getDirection().normalize().multiply(-0.6D))
                .add(0, -0.9D, 0);
        model.setPitch(0);
        session.model.teleport(model);
        if (session.ticks % 4 == 0) {
            current.getWorld().spawnParticle(Particle.SMOKE, model, 1, 0.05D, 0.05D, 0.05D, 0.0D);
        }
    }

    private void hud(Player player, Session session) {
        Block looking = player.getTargetBlockExact(targetRange);
        int secondsLeft = Math.max(0, (flightTicks - session.ticks) / 20);
        double toBase = player.getWorld().equals(session.launcherBlock.getWorld())
                ? player.getLocation().distance(session.launcherBlock.getLocation())
                : -1;

        player.sendActionBar(MessageUtil.parse(plugin.message("recon-hud"), Map.of(
                "x", looking == null ? "—" : String.valueOf(looking.getX()),
                "z", looking == null ? "—" : String.valueOf(looking.getZ()),
                "seconds", secondsLeft,
                "base", (int) Math.round(toBase),
                "hearts", session.hearts,
                "state", plugin.message(session.targetSet ? "recon-state-marked" : "recon-state-searching"))));
    }

    // ─────────────────────────── призначення цілі ───────────────────────────

    /**
     * Записує в установку блок, на який дивиться оператор.
     *
     * @return true, якщо гравець зараз у розвідці (навіть якщо ціль не підійшла)
     */
    public boolean markTarget(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        BallisticLauncher launcher = manager.launcherAt(session.launcherBlock);
        LauncherTier tier = launcher == null ? null : manager.tier(launcher.tierId());
        if (launcher == null || tier == null) {
            end(session, EndReason.DESTROYED);
            return true;
        }
        Block looking = player.getTargetBlockExact(targetRange);
        if (looking == null) {
            manager.sendMessage(player, "recon-no-block", Map.of("range", targetRange));
            return true;
        }
        double distance = manager.horizontalDistance(
                launcher.block().getLocation(), looking.getX(), looking.getZ());
        if (distance > tier.range()) {
            manager.sendMessage(player, "recon-too-far",
                    Map.of("distance", (int) Math.round(distance), "range", tier.range()));
            return true;
        }

        launcher.target(looking.getX(), looking.getZ());
        manager.marker(player.getUniqueId(), looking.getX(), looking.getZ());
        manager.refreshDisplay(launcher);
        session.targetSet = true;

        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.8f);
        manager.sendMessage(player, "recon-target-set", Map.of(
                "x", looking.getX(), "z", looking.getZ(),
                "distance", (int) Math.round(distance)));
        return true;
    }

    // ────────────────────────────── завершення ──────────────────────────────

    /**
     * Збиває розвідника, якщо він у радіусі точки. Використовується тараном FPV-дрона:
     * розвідник не вибухає, але апарат вважається втраченим з усіма наслідками.
     *
     * @return true, якщо когось збили
     */
    public boolean destroyNear(Location location, double radius) {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.ending || !session.model.isValid()) {
                continue;
            }
            Location model = session.model.getLocation();
            if (!model.getWorld().equals(location.getWorld())
                    || model.distanceSquared(location) > radius * radius) {
                continue;
            }
            end(session, EndReason.DESTROYED);
            return true;
        }
        return false;
    }

    /** Примусове завершення ззовні (вихід з гри, перезавантаження конфігу). */
    public void abort(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            end(session, EndReason.DESTROYED);
        }
    }

    private void end(Session session, EndReason reason) {
        if (session.ending) {
            return;
        }
        session.ending = true;
        sessions.remove(session.player);

        Location lost = session.model.isValid() ? session.model.getLocation() : session.origin;
        session.model.remove();

        // Штраф отримує установка, а не гравець: розвідник фізично не повернувся на базу.
        if (reason != EndReason.RETURNED && reason != EndReason.SHUTDOWN && lostCooldownMillis > 0) {
            BallisticLauncher launcher = manager.launcherAt(session.launcherBlock);
            if (launcher != null) {
                launcher.reconCooldown(System.currentTimeMillis() + lostCooldownMillis);
                manager.refreshDisplay(launcher);
            }
        }

        Player player = plugin.getServer().getPlayer(session.player);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.setFlySpeed(session.flySpeed);
        player.setAllowFlight(session.allowFlight);
        player.setGameMode(session.gameMode);
        player.teleport(session.origin);
        player.sendActionBar(Component.empty());

        switch (reason) {
            case RETURNED -> manager.sendMessage(player, "recon-returned", Map.of());
            case DESTROYED -> manager.sendMessage(player, "recon-lost",
                    Map.of("minutes", lostCooldownMillis / 60_000L,
                            "x", lost.getBlockX(), "z", lost.getBlockZ()));
            case TIMEOUT -> manager.sendMessage(player, "recon-timeout",
                    Map.of("minutes", lostCooldownMillis / 60_000L));
            case SHUTDOWN -> {
                // Сервер вимикається — повідомляти нема сенсу.
            }
        }
    }
}
