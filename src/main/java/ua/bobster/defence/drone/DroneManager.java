package ua.bobster.defence.drone;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.strategicstates.combat.StrategicWeaponType;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Життєвий цикл FPV-дронів: типи, запуск, реєстр сесій, детонація, повернення оператора.
 * <p>
 * Керування реалізоване через spectator-режим оператора: на Paper 1.21.1 немає API для
 * читання WASD/Space/Shift, натомість spectator дає рівно ту саму свободу руху нативно.
 */
public class DroneManager {

    private final BobsterDefence plugin;
    private final DroneItem droneItem;
    private final NamespacedKey entityKey;
    private final NamespacedKey stateKey;
    private final Map<UUID, DroneSession> sessions = new HashMap<>();
    private final Map<UUID, AutonomousDrone> autonomous = new HashMap<>();
    private final Map<String, DroneType> types = new LinkedHashMap<>();

    private DronePhysics physics;
    private BukkitTask tickTask;

    private boolean enabled;
    private boolean explosionFire;
    private boolean explosionBreakBlocks;
    private double interceptExplosionPower;
    private boolean operatorCanTakeDamage;

    public DroneManager(BobsterDefence plugin, DroneItem droneItem) {
        this.plugin = plugin;
        this.droneItem = droneItem;
        this.entityKey = new NamespacedKey(plugin, "fpv_drone_entity");
        this.stateKey = new NamespacedKey(plugin, "state_id");
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("fpv-drone.enabled", true);
        this.explosionFire = config.getBoolean("fpv-drone.explosion-fire", false);
        this.explosionBreakBlocks = config.getBoolean("fpv-drone.explosion-break-blocks", true);
        this.interceptExplosionPower = Math.max(0.0D,
                config.getDouble("fpv-drone.intercept-explosion-power", 4.0D));
        this.operatorCanTakeDamage = config.getBoolean("fpv-drone.operator.can-take-damage", true);

        loadTypes(config.getConfigurationSection("fpv-drone.drones"));
        if (physics != null) {
            physics.reload();
        }
    }

    private void loadTypes(ConfigurationSection section) {
        types.clear();
        if (section == null) {
            plugin.getLogger().warning("fpv-drone.drones відсутній — дронів не буде");
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection drone = section.getConfigurationSection(id);
            if (drone == null) {
                continue;
            }
            Material material = Material.matchMaterial(drone.getString("material", "TNT"));
            types.put(id.toLowerCase(), new DroneType(
                    id.toLowerCase(),
                    drone.getString("name", "<gold>🛩 " + id.toUpperCase()),
                    material == null || !material.isItem() ? Material.TNT : material,
                    Math.max(0.05D, drone.getDouble("speed", 1.0D)),
                    Math.max(0.05D, drone.getDouble("boost-speed", 1.5D)),
                    Math.max(16, drone.getInt("max-distance", 500)),
                    Math.max(1, drone.getInt("flight-time", 60)),
                    Math.max(0.0D, drone.getDouble("explosion-power", 4.0D)),
                    Math.max(0.0D, drone.getDouble("player-damage", 0.0D)),
                    Math.max(0.0D, drone.getDouble("player-damage-radius", 4.0D)),
                    drone.getStringList("lore")));
        }
    }

    public void start() {
        physics = new DronePhysics(plugin, this);
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            physics.tickAll();
            tickAutonomous();
        }, 1L, 1L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (DroneSession session : new ArrayList<>(sessions.values())) {
            end(session, DroneSession.EndReason.SHUTDOWN);
        }
        for (AutonomousDrone drone : new ArrayList<>(autonomous.values())) {
            if (drone.beginEnding()) {
                autonomous.remove(drone.hitbox().getUniqueId());
                drone.cleanup();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public NamespacedKey entityKey() {
        return entityKey;
    }

    public DroneItem item() {
        return droneItem;
    }

    public BobsterDefence plugin() {
        return plugin;
    }

    public List<DroneType> types() {
        return new ArrayList<>(types.values());
    }

    public DroneType type(String id) {
        return id == null ? null : types.get(id.toLowerCase());
    }

    public boolean operatorCanTakeDamage() {
        return operatorCanTakeDamage;
    }

    public Map<UUID, DroneSession> sessions() {
        return sessions;
    }

    public DroneSession sessionOf(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean isFlying(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public DroneSession sessionByDrone(Entity entity) {
        if (entity == null
                || !entity.getPersistentDataContainer().has(entityKey, PersistentDataType.BYTE)) {
            return null;
        }
        for (DroneSession session : sessions.values()) {
            if (session.hitbox().getUniqueId().equals(entity.getUniqueId())) {
                return session;
            }
        }
        return null;
    }

    public DroneSession sessionByBody(Entity entity) {
        if (entity == null) {
            return null;
        }
        for (DroneSession session : sessions.values()) {
            ArmorStand body = session.body();
            if (body != null && body.getUniqueId().equals(entity.getUniqueId())) {
                return session;
            }
        }
        return null;
    }

    // ─────────────────────────────── запуск ───────────────────────────────

    public boolean launch(Player player, DroneType type) {
        if (!enabled || isFlying(player)) {
            return false;
        }
        World world = player.getWorld();
        Location origin = player.getLocation().clone();
        Location start = player.getEyeLocation().clone().add(origin.getDirection().normalize().multiply(1.2D));

        ArmorStand hitbox = spawnHitbox(world, start, CombatPrincipal.player(player.getUniqueId()));
        BlockDisplay display = spawnDisplay(world, start, type);
        ArmorStand body = operatorCanTakeDamage ? spawnBody(world, origin, player) : null;

        DroneSession session = new DroneSession(player.getUniqueId(), type, origin, player.getGameMode(),
                player.getFlySpeed(), player.getAllowFlight(), hitbox, display, body);
        session.lastLocation(start.clone());
        sessions.put(player.getUniqueId(), session);

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(start);
        player.setFlySpeed((float) Math.clamp(0.1D * type.speed(), 0.001D, 1.0D));

        playLaunchEffects(world, start);
        sendMessage(player, "drone-launched", Map.of(
                "name", MessageUtil.raw(type.displayName())));
        return true;
    }

    public boolean launch(AutonomousDroneMission mission) {
        if (!enabled || mission == null || mission.owner() == null || mission.origin() == null || mission.target() == null) {
            return false;
        }
        World world = mission.origin().getWorld();
        DroneType type = type(mission.typeId());
        if (world == null || mission.target().getWorld() == null || !world.equals(mission.target().getWorld()) || type == null) {
            return false;
        }
        if (mission.origin().distanceSquared(mission.target()) > (double) type.maxDistance() * type.maxDistance()) {
            return false;
        }
        AutonomousDrone drone = new AutonomousDrone(this, mission, type);
        autonomous.put(drone.hitbox().getUniqueId(), drone);
        playLaunchEffects(world, mission.origin());
        return true;
    }

    private void tickAutonomous() {
        for (AutonomousDrone drone : new ArrayList<>(autonomous.values())) {
            drone.tick();
        }
    }

    public boolean isDrone(Entity entity) {
        return sessionByDrone(entity) != null || entity != null && autonomous.containsKey(entity.getUniqueId());
    }

    public boolean intercept(Entity entity, Player interceptor) {
        DroneSession session = sessionByDrone(entity);
        if (session != null && !session.isEnding()) {
            end(session, DroneSession.EndReason.INTERCEPTED, interceptor);
            return true;
        }
        AutonomousDrone drone = entity == null ? null : autonomous.get(entity.getUniqueId());
        if (drone == null || drone.ending()) {
            return false;
        }
        end(drone, true);
        return true;
    }

    boolean canAutonomousHit(CombatPrincipal owner, Entity hitbox, Entity display, Entity entity) {
        if (!(entity instanceof LivingEntity) || !entity.isValid() || entity.getUniqueId().equals(hitbox.getUniqueId()) || entity.getUniqueId().equals(display.getUniqueId())) {
            return false;
        }
        if (entity instanceof Player player && player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        CombatPrincipal target = principalOf(entity);
        return target == null || !plugin.allegiance().friendly(owner, target);
    }

    private CombatPrincipal principalOf(Entity entity) {
        CombatPrincipal tagged = plugin.combatIdentity().read(entity.getPersistentDataContainer());
        if (tagged != null) {
            return tagged;
        }
        if (entity instanceof Player player) {
            return CombatPrincipal.player(player.getUniqueId());
        }
        String rawState = entity.getPersistentDataContainer().get(stateKey, PersistentDataType.STRING);
        if (rawState == null) {
            return null;
        }
        try {
            return CombatPrincipal.state(UUID.fromString(rawState));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    ArmorStand spawnHitbox(World world, Location location, CombatPrincipal owner) {
        return world.spawn(location.clone().subtract(0, 0.5D, 0), ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setCanTick(false);
            stand.setPersistent(false);
            stand.setSilent(true);
            stand.getPersistentDataContainer().set(entityKey, PersistentDataType.BYTE, (byte) 1);
            // Мітка власника — щоб власна автоматична ППО не збивала свій же дрон.
            plugin.combatIdentity().write(stand.getPersistentDataContainer(), owner);
        });
    }

    BlockDisplay spawnDisplay(World world, Location location, DroneType type) {
        return world.spawn(location, BlockDisplay.class, display -> {
            Material block = type.material().isBlock() ? type.material() : Material.TNT;
            display.setBlock(block.createBlockData());
            float scale = (float) plugin.getConfig().getDouble("fpv-drone.model-scale", 0.6D);
            display.setTransformation(new Transformation(
                    new Vector3f(-scale / 2f, -scale / 2f, -scale / 2f),
                    new AxisAngle4f(),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f()));
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTeleportDuration(2); // згладжує рух між тіками
            display.setPersistent(false);
        });
    }

    private ArmorStand spawnBody(World world, Location origin, Player player) {
        return world.spawn(origin, ArmorStand.class, stand -> {
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setPersistent(false);
            stand.customName(MessageUtil.parse(
                    plugin.getConfig().getString("messages.drone-body-name",
                            "<gray>{player} <dark_gray>| <red>оператор FPV"),
                    Map.of("player", player.getName())));
            stand.setCustomNameVisible(true);
            stand.getEquipment().setHelmet(player.getInventory().getHelmet());
            stand.getEquipment().setChestplate(player.getInventory().getChestplate());
        });
    }

    private void playLaunchEffects(World world, Location location) {
        if (!plugin.getConfig().getBoolean("fpv-drone.effects.sounds", true)) {
            return;
        }
        world.playSound(location, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.8f);
        world.playSound(location, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 2.0f);
    }

    // ────────────────────────────── завершення ──────────────────────────────

    public void end(DroneSession session, DroneSession.EndReason reason) {
        end(session, reason, null);
    }

    public void end(DroneSession session, DroneSession.EndReason reason, Player interceptor) {
        if (!session.beginEnding()) {
            return;
        }
        sessions.remove(session.operator());

        Location location = session.hitbox().isValid()
                ? session.hitbox().getLocation().clone().add(0, 0.5D, 0)
                : session.lastLocation().clone();

        if (reason != DroneSession.EndReason.SHUTDOWN) {
            detonate(location, session.hitbox(), session.type(), shotDown(reason), CombatPrincipal.player(session.operator()));
        }

        session.display().remove();
        session.hitbox().remove();
        if (session.body() != null) {
            session.body().remove();
        }

        restoreOperator(session, reason, interceptor);
    }

    void end(AutonomousDrone drone, boolean shotDown) {
        if (!drone.beginEnding()) {
            return;
        }
        autonomous.remove(drone.hitbox().getUniqueId());
        Location location = drone.hitbox().getLocation().clone().add(0, 0.5D, 0);
        detonate(location, drone.hitbox(), drone.type(), shotDown, drone.owner());
        drone.cleanup();
    }

    void abort(AutonomousDrone drone) {
        if (!drone.beginEnding()) {
            return;
        }
        autonomous.remove(drone.hitbox().getUniqueId());
        drone.cleanup();
    }

    public void cancelAutonomous() {
        for (AutonomousDrone drone : new ArrayList<>(autonomous.values())) {
            abort(drone);
        }
    }

    /** Дрон збили в повітрі, а не він сам детонував по цілі. */
    private static boolean shotDown(DroneSession.EndReason reason) {
        return reason == DroneSession.EndReason.INTERCEPTED
                || reason == DroneSession.EndReason.RAMMED;
    }

    /**
     * @param shotDown true — дрон збили в повітрі. Тоді вибух не залежить від моделі:
     *                 боєкомплект нікуди не подівся і спрацьовує повністю.
     */
    private void detonate(Location location, Entity source, DroneType type, boolean shotDown, CombatPrincipal operator) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        double power = shotDown ? interceptExplosionPower : type == null ? 0.0D : type.explosionPower();
        if (power > 0.0D && plugin.strategicStates() != null) {
            plugin.strategicStates().recordImpact(location, operator, StrategicWeaponType.DRONE, power);
        }
        // Джерело вказуємо навмисно: тоді летить EntityExplodeEvent і ChestProtectionListener
        // встигає витягти скрині зі списку зруйнованих блоків.
        world.createExplosion(source, location, (float) power,
                explosionFire, explosionBreakBlocks);
        if (type != null) {
            damageNearbyPlayers(location, type);
        }
    }

    /**
     * Протипіхотна бойова частина: слабкий вибух по блоках, але відчутна шкода гравцям.
     * Ванільний вибух такого не вміє — сила вибуху там одночасно і радіус руйнувань, і шкода.
     */
    private void damageNearbyPlayers(Location location, DroneType type) {
        if (type.playerDamage() <= 0 || type.playerDamageRadius() <= 0) {
            return;
        }
        World world = location.getWorld();
        double radius = type.playerDamageRadius();
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) {
                continue;
            }
            double distance = player.getLocation().distance(location);
            if (distance > radius) {
                continue;
            }
            double factor = 1.0D - (distance / radius);
            double damage = type.playerDamage() * factor;
            if (damage > 0.1D) {
                player.damage(damage);
            }
        }
    }

    private void restoreOperator(DroneSession session, DroneSession.EndReason reason, Player interceptor) {
        Player player = plugin.getServer().getPlayer(session.operator());
        if (player == null || !player.isOnline()) {
            return;
        }
        player.setFlySpeed(session.originalFlySpeed());
        player.setAllowFlight(session.originalAllowFlight());
        player.setGameMode(session.originalGameMode());
        player.teleport(session.origin());
        player.sendActionBar(Component.empty());

        switch (reason) {
            case INTERCEPTED -> sendMessage(player, "drone-intercepted", interceptor == null
                    ? Map.of("player", "?")
                    : Map.of("player", interceptor.getName()));
            case RAMMED -> sendMessage(player, "drone-rammed", interceptor == null
                    ? Map.of("player", "?")
                    : Map.of("player", interceptor.getName()));
            case SIGNAL_LOST -> sendMessage(player, "drone-signal-lost", Map.of());
            case BATTERY_EMPTY -> sendMessage(player, "drone-battery-empty", Map.of());
            case COLLISION -> sendMessage(player, "drone-detonated", Map.of());
            case OPERATOR_DIED, SHUTDOWN -> {
                // Оператор або вже мертвий, або сервер вимикається — зайвий текст не потрібен.
            }
        }
    }

    public void sendMessage(Player player, String key) {
        sendMessage(player, key, Map.of());
    }

    public void sendMessage(Player player, String key, Map<String, ?> placeholders) {
        String raw = plugin.message(key);
        if (raw.isEmpty()) {
            return;
        }
        player.sendMessage(MessageUtil.parse(plugin.prefix() + raw, placeholders));
    }

    public List<DroneSession> activeSessions() {
        return new ArrayList<>(sessions.values());
    }
}
