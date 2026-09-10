package ua.bobster.defence.strike;

import net.kyori.adventure.text.Component;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Dropper;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.drone.AutonomousDroneMission;
import ua.bobster.defence.drone.DroneType;
import ua.bobster.defence.missile.MissilePayload;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StrikeLauncherManager {

    private final class GuidedFlight {
        private final GuidedMissileType type;
        private final MissilePayload payload;
        private final UUID owner;
        private final UUID operator;
        private final Location origin;
        private final Location returnLocation;
        private final GameMode returnMode;
        private final float returnFlySpeed;
        private final boolean returnFlight;
        private final int returnSendViewDistance;
        private final Vector launchDirection;
        private final Trident missile;
        private final ArmorStand hitbox;
        private Location previous;
        private final Set<Long> tickets = new java.util.HashSet<>();
        private Vector flightVelocity;
        private double streamingFactor = 1.0D;
        private double travelled;
        private int ticks;
        private boolean fuelEmptyNotified;
        private boolean ending;

        GuidedFlight(Player player, Block launcher, GuidedMissileType type, MissilePayload payload) {
            this.type = type;
            this.payload = payload;
            this.owner = player.getUniqueId();
            this.operator = player.getUniqueId();
            this.returnLocation = player.getLocation().clone();
            this.returnMode = player.getGameMode();
            this.returnFlySpeed = player.getFlySpeed();
            this.returnFlight = player.getAllowFlight();
            this.returnSendViewDistance = player.getSendViewDistance();
            Vector facing = facing(launcher);
            this.launchDirection = facing.clone();
            this.flightVelocity = facing.clone().multiply(type.speed());
            this.origin = launcher.getLocation().add(0.5D, 0.65D, 0.5D).add(facing.clone().multiply(1.4D));
            this.previous = origin.clone();
            World world = launcher.getWorld();
            this.missile = world.spawn(origin, Trident.class, entity -> {
                entity.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                entity.setGravity(false);
                entity.setDamage(0.0D);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setSilent(true);
                entity.setVelocity(facing.clone().multiply(type.speed()));
                entity.getPersistentDataContainer().set(guidedEntityKey, PersistentDataType.BYTE, (byte) 1);
                plugin.combatIdentity().write(entity.getPersistentDataContainer(), CombatPrincipal.player(owner));
            });
            this.hitbox = world.spawn(origin.clone().subtract(0, 0.4D, 0), ArmorStand.class, stand -> {
                stand.setInvisible(true);
                stand.setSmall(true);
                stand.setGravity(false);
                stand.setBasePlate(false);
                stand.setArms(false);
                stand.setPersistent(false);
                stand.setSilent(true);
                stand.getPersistentDataContainer().set(guidedHitboxKey, PersistentDataType.BYTE, (byte) 1);
                plugin.combatIdentity().write(stand.getPersistentDataContainer(), CombatPrincipal.player(owner));
            });
            player.setGameMode(GameMode.SPECTATOR);
            if (guidedSendViewDistance > 0 && player.getSendViewDistance() < guidedSendViewDistance) {
                player.setSendViewDistance(guidedSendViewDistance);
            }
            Location view = origin.clone();
            view.setDirection(facing);
            player.teleport(view);
            if (!missile.addPassenger(player)) {
                player.setSpectatorTarget(missile);
            }
            holdChunks(origin, flightVelocity, player);
            world.playSound(origin, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0f, 0.7f);
        }

        void tick() {
            if (ending) {
                return;
            }
            Player player = plugin.getServer().getPlayer(operator);
            if (player == null || !player.isOnline() || !missile.isValid() || !hitbox.isValid()) {
                finish(false, false);
                return;
            }
            if (player.getVehicle() != missile && player.getSpectatorTarget() != missile) {
                if (!missile.addPassenger(player)) {
                    player.setSpectatorTarget(missile);
                }
            }
            ticks++;
            int fuelTicks = Math.max(1, (int) Math.round(type.fuelSeconds() * 20.0D));
            boolean powered = ticks <= fuelTicks;
            if (!powered && !fuelEmptyNotified) {
                fuelEmptyNotified = true;
                message(player, "strike-guided-fuel-empty");
            }
            flightVelocity = ticks <= guidedLaunchControlTicks ? launchDirection.clone().multiply(type.speed()) : elytraVelocity(player, powered);
            Location current = missile.getLocation();
            holdChunks(current, flightVelocity, player);
            updateStreaming(player, current, flightVelocity);
            Vector movement = flightVelocity.clone().multiply(streamingFactor);
            double speed = movement.length();
            RayTraceResult blockHit = current.getWorld().rayTraceBlocks(current, movement.clone().normalize(), speed, FluidCollisionMode.NEVER, true);
            RayTraceResult entityHit = launchProtected() ? null : current.getWorld().rayTraceEntities(current, movement.clone().normalize(), speed, 0.7D, this::canHit);
            RayTraceResult collision = nearest(current, blockHit, entityHit);
            if (collision != null && ticks > 3) {
                Location collisionLocation = collision.getHitPosition().toLocation(current.getWorld());
                if (launchProtected()) {
                    finish(false, false, collisionLocation);
                    message(player, "strike-guided-launch-blocked");
                } else {
                    finish(true, false, collisionLocation);
                }
                return;
            }
            travelled += speed;
            double fuel = Math.max(0.0D, (fuelTicks - ticks) / 20.0D);
            player.sendActionBar(MessageUtil.parse(plugin.message("strike-guided-hud"), Map.of(
                    "name", MessageUtil.raw(type.displayName()),
                    "fuel", String.format(Locale.US, "%.1f", fuel),
                    "distance", (int) Math.round(travelled),
                    "speed", String.format(Locale.US, "%.2f", speed))));
            missile.setVelocity(movement);
            hitbox.teleport(current.clone().add(movement).subtract(0, 0.4D, 0));
            previous = current;
            if (powered) {
                current.getWorld().spawnParticle(Particle.FLAME, current.clone().subtract(movement.clone().normalize().multiply(0.5D)), 2, 0.04D, 0.04D, 0.04D, 0.01D, null, true);
            }
            current.getWorld().spawnParticle(Particle.SMOKE, current, 1, 0.05D, 0.05D, 0.05D, 0.0D, null, true);
        }

        private Vector elytraVelocity(Player player, boolean powered) {
            Vector look = player.getEyeLocation().getDirection().normalize();
            Vector velocity = flightVelocity.clone();
            if (velocity.lengthSquared() < 1.0E-6D) {
                velocity = look.clone().multiply(0.1D);
            }
            double pitch = Math.toRadians(player.getLocation().getPitch());
            double lookHorizontal = Math.hypot(look.getX(), look.getZ());
            double speedHorizontal = Math.hypot(velocity.getX(), velocity.getZ());
            double lift = Math.cos(pitch);
            lift = lift * lift * Math.min(1.0D, look.length() / 0.4D);
            double physicsScale = Math.max(1.0D, type.speed() / guidedPhysicsReferenceSpeed);
            velocity.add(new Vector(0.0D, (-0.08D + lift * 0.06D) * physicsScale, 0.0D));
            if (velocity.getY() < 0.0D && lookHorizontal > 0.0D) {
                double recovery = velocity.getY() * -0.1D * lift;
                velocity.add(new Vector(look.getX() / lookHorizontal * recovery, recovery, look.getZ() / lookHorizontal * recovery));
            }
            if (pitch < 0.0D && lookHorizontal > 0.0D) {
                double climb = speedHorizontal * -Math.sin(pitch) * 0.04D;
                velocity.add(new Vector(-look.getX() / lookHorizontal * climb, climb * 3.2D, -look.getZ() / lookHorizontal * climb));
            }
            if (lookHorizontal > 0.0D) {
                velocity.add(new Vector((look.getX() / lookHorizontal * speedHorizontal - velocity.getX()) * 0.1D, 0.0D, (look.getZ() / lookHorizontal * speedHorizontal - velocity.getZ()) * 0.1D));
            }
            double steeringResponse = powered ? guidedSteeringResponse : guidedSteeringResponse * 0.65D;
            Vector desiredDirection = look.clone().multiply(velocity.length());
            velocity.add(desiredDirection.subtract(velocity).multiply(steeringResponse));
            if (powered) {
                double factor = look.getY() < 0.0D ? 1.0D + (guidedPoweredDiveMaxMultiplier - 1.0D) * -look.getY() : 1.0D - (1.0D - guidedPoweredClimbMinMultiplier) * look.getY();
                double targetSpeed = type.speed() * Math.clamp(factor, guidedPoweredClimbMinMultiplier, guidedPoweredDiveMaxMultiplier);
                double currentSpeed = velocity.length();
                double correctedSpeed = currentSpeed + (targetSpeed - currentSpeed) * guidedEngineResponse;
                velocity.multiply(correctedSpeed / Math.max(1.0E-6D, currentSpeed));
            } else {
                velocity.setX(velocity.getX() * 0.99D);
                velocity.setY(velocity.getY() * 0.98D);
                velocity.setZ(velocity.getZ() * 0.99D);
            }
            return velocity;
        }

        private boolean canHit(Entity entity) {
            if (entity.getUniqueId().equals(missile.getUniqueId()) || entity.getUniqueId().equals(hitbox.getUniqueId()) || entity.getUniqueId().equals(operator)) {
                return false;
            }
            if (!(entity instanceof LivingEntity)) {
                return false;
            }
            CombatPrincipal target = entity instanceof Player player ? CombatPrincipal.player(player.getUniqueId()) : plugin.combatIdentity().read(entity.getPersistentDataContainer());
            return target == null || !plugin.allegiance().friendly(CombatPrincipal.player(owner), target);
        }

        private boolean launchProtected() {
            return ticks <= guidedLaunchProtectionTicks;
        }

        boolean finish(boolean explode, boolean intercepted) {
            return finish(explode, intercepted, missile.isValid() ? missile.getLocation() : previous);
        }

        boolean finish(boolean explode, boolean intercepted, Location location) {
            if (ending) {
                return false;
            }
            ending = true;
            guided.remove(missile.getUniqueId());
            guidedByHitbox.remove(hitbox.getUniqueId());
            Player player = plugin.getServer().getPlayer(operator);
            if (player != null) {
                player.leaveVehicle();
                player.setSpectatorTarget(null);
                player.setGameMode(returnMode);
                player.setFlySpeed(returnFlySpeed);
                player.setAllowFlight(returnFlight);
                player.setSendViewDistance(returnSendViewDistance);
                player.teleport(returnLocation);
                player.sendActionBar(Component.empty());
            }
            releaseChunks();
            boolean spent = false;
            if (explode && location.getWorld() != null && payload.kind() == MissilePayload.Kind.EXPLOSIVE) {
                location.getWorld().createExplosion(hitbox, location, (float) type.explosionPower(), false, true);
                location.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 8.0f, intercepted ? 1.4f : 0.8f);
            } else if (explode && location.getWorld() != null && payload.kind() == MissilePayload.Kind.POTION) {
                plugin.payloads().applyPotion(payload, location, type.explosionPower());
            } else if (explode && payload.kind() == MissilePayload.Kind.EMPTY && !intercepted) {
                plugin.payloads().leaveSpent(missile, location, flightVelocity);
                spent = true;
            }
            if (!spent) {
                missile.remove();
            }
            hitbox.remove();
            return true;
        }

        private void holdChunks(Location location, Vector movement, Player player) {
            Set<Long> wanted = new java.util.HashSet<>();
            Vector direction = movement.lengthSquared() < 1.0E-6D ? launchDirection.clone() : movement.clone().normalize();
            Vector look = player.getEyeLocation().getDirection().normalize();
            Vector forecast = direction.clone().multiply(0.65D).add(look.multiply(0.35D));
            if (forecast.lengthSquared() < 1.0E-6D) {
                forecast = direction;
            } else {
                forecast.normalize();
            }
            double distance = Math.max(48.0D, type.speed() * 20.0D * guidedChunkLookAheadSeconds);
            for (double offset = 0.0D; offset <= distance; offset += 16.0D) {
                collect(wanted, location.clone().add(forecast.clone().multiply(offset)));
            }
            for (Long key : wanted) {
                if (tickets.add(key)) {
                    int chunkX = (int) (key >> 32);
                    int chunkZ = key.intValue();
                    location.getWorld().getChunkAtAsync(chunkX, chunkZ, true, true);
                    location.getWorld().addPluginChunkTicket(chunkX, chunkZ, plugin);
                }
            }
            tickets.removeIf(key -> {
                if (wanted.contains(key)) {
                    return false;
                }
                location.getWorld().removePluginChunkTicket((int) (key >> 32), key.intValue(), plugin);
                return true;
            });
        }

        private void updateStreaming(Player player, Location location, Vector movement) {
            Vector direction = movement.lengthSquared() < 1.0E-6D ? launchDirection.clone() : movement.clone().normalize();
            Location probe = location.clone().add(direction.multiply(guidedClientReadyDistance));
            long key = ((long) (probe.getBlockX() >> 4) << 32) | ((probe.getBlockZ() >> 4) & 0xFFFFFFFFL);
            double target = player.isChunkSent(key) ? 1.0D : guidedChunkThrottle;
            double step = target > streamingFactor ? guidedChunkRecoveryStep : guidedChunkSlowdownStep;
            streamingFactor += Math.clamp(target - streamingFactor, -step, step);
        }

        private void collect(Set<Long> into, Location location) {
            int centerX = location.getBlockX() >> 4;
            int centerZ = location.getBlockZ() >> 4;
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                    into.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                }
            }
        }

        private void releaseChunks() {
            World world = origin.getWorld();
            for (Long key : tickets) {
                world.removePluginChunkTicket((int) (key >> 32), key.intValue(), plugin);
            }
            tickets.clear();
        }
    }

    private final BobsterDefence plugin;
    private final StrikeLauncherItem item;
    private final NamespacedKey launcherKindKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey targetXKey;
    private final NamespacedKey targetZKey;
    private final NamespacedKey redstoneKey;
    private final NamespacedKey lastLaunchKey;
    private final NamespacedKey panelLaunchKey;
    private final NamespacedKey laneKey;
    private final NamespacedKey guidedEntityKey;
    private final NamespacedKey guidedHitboxKey;
    private final Map<String, GuidedMissileType> types = new LinkedHashMap<>();
    private final Map<UUID, Block> selected = new HashMap<>();
    private final DroneLauncherRegistry droneRegistry;
    private final Map<UUID, GuidedFlight> guided = new HashMap<>();
    private final Map<UUID, GuidedFlight> guidedByHitbox = new HashMap<>();
    private final Map<String, Long> redstoneDebounce = new HashMap<>();
    private double droneHeightAboveTarget;
    private double droneLaneOffset;
    private long droneLaneResetMillis;
    private int guidedLaunchProtectionTicks;
    private int guidedLaunchControlTicks;
    private double guidedPhysicsReferenceSpeed;
    private double guidedEngineResponse;
    private double guidedSteeringResponse;
    private double guidedPoweredClimbMinMultiplier;
    private double guidedPoweredDiveMaxMultiplier;
    private double guidedChunkLookAheadSeconds;
    private double guidedClientReadyDistance;
    private double guidedChunkThrottle;
    private double guidedChunkRecoveryStep;
    private double guidedChunkSlowdownStep;
    private int guidedSendViewDistance;
    private BukkitTask task;

    public StrikeLauncherManager(BobsterDefence plugin, StrikeLauncherItem item) {
        this.plugin = plugin;
        this.item = item;
        this.launcherKindKey = new NamespacedKey(plugin, "strike_launcher_kind");
        this.ownerKey = new NamespacedKey(plugin, "strike_launcher_owner");
        this.targetXKey = new NamespacedKey(plugin, "strike_target_x");
        this.targetZKey = new NamespacedKey(plugin, "strike_target_z");
        this.redstoneKey = new NamespacedKey(plugin, "strike_redstone");
        this.lastLaunchKey = new NamespacedKey(plugin, "strike_last_launch");
        this.panelLaunchKey = new NamespacedKey(plugin, "strike_panel_launch");
        this.laneKey = new NamespacedKey(plugin, "strike_lane");
        this.guidedEntityKey = new NamespacedKey(plugin, "guided_missile_entity");
        this.guidedHitboxKey = new NamespacedKey(plugin, "guided_missile_hitbox");
        this.droneRegistry = new DroneLauncherRegistry(plugin);
        this.droneRegistry.load();
        reload();
    }

    public void reload() {
        droneHeightAboveTarget = Math.max(1.0D, plugin.getConfig().getDouble("drone-launcher.trajectory.height-above-target", 40.0D));
        droneLaneOffset = Math.max(0.0D, plugin.getConfig().getDouble("drone-launcher.trajectory.lane-offset", 3.0D));
        droneLaneResetMillis = Math.max(0L, Math.round(plugin.getConfig().getDouble("drone-launcher.trajectory.lane-reset-seconds", 2.0D) * 1000.0D));
        guidedLaunchProtectionTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("guided-missile.launch-protection-seconds", 2.0D) * 20.0D));
        guidedLaunchControlTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("guided-missile.launch-control-delay-seconds", 0.5D) * 20.0D));
        guidedPhysicsReferenceSpeed = Math.max(0.1D, plugin.getConfig().getDouble("guided-missile.flight.physics-reference-speed", 0.9D));
        guidedEngineResponse = Math.clamp(plugin.getConfig().getDouble("guided-missile.flight.engine-response", 0.12D), 0.01D, 1.0D);
        guidedSteeringResponse = Math.clamp(plugin.getConfig().getDouble("guided-missile.flight.steering-response", 0.055D), 0.005D, 0.5D);
        guidedPoweredClimbMinMultiplier = Math.clamp(plugin.getConfig().getDouble("guided-missile.flight.powered-climb-min-speed-multiplier", 0.65D), 0.1D, 1.0D);
        guidedPoweredDiveMaxMultiplier = Math.clamp(plugin.getConfig().getDouble("guided-missile.flight.powered-dive-max-speed-multiplier", 1.3D), 1.0D, 2.0D);
        guidedChunkLookAheadSeconds = Math.max(0.5D, plugin.getConfig().getDouble("guided-missile.chunk-loading.look-ahead-seconds", 3.0D));
        guidedClientReadyDistance = Math.max(16.0D, plugin.getConfig().getDouble("guided-missile.chunk-loading.client-ready-distance", 48.0D));
        guidedChunkThrottle = Math.clamp(plugin.getConfig().getDouble("guided-missile.chunk-loading.unready-speed-multiplier", 0.2D), 0.05D, 1.0D);
        guidedChunkRecoveryStep = Math.clamp(plugin.getConfig().getDouble("guided-missile.chunk-loading.recovery-step", 0.08D), 0.01D, 1.0D);
        guidedChunkSlowdownStep = Math.clamp(plugin.getConfig().getDouble("guided-missile.chunk-loading.slowdown-step", 0.2D), 0.01D, 1.0D);
        guidedSendViewDistance = Math.max(0, plugin.getConfig().getInt("guided-missile.chunk-loading.send-view-distance", 10));
        types.clear();
        org.bukkit.configuration.Configuration defaults = plugin.getConfig().getDefaults();
        if (defaults != null) {
            loadTypes(defaults.getConfigurationSection("guided-missile.types"));
        }
        loadTypes(plugin.getConfig().getConfigurationSection("guided-missile.types"));
    }

    private void loadTypes(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection value = section.getConfigurationSection(id);
            if (value == null) {
                continue;
            }
            boolean barrier = id.equalsIgnoreCase("barrier");
            double fallbackFuel = barrier ? 30.0D : 15.0D;
            double fallbackSpeed = barrier ? 3.6D : 3.0D;
            types.put(id.toLowerCase(), new GuidedMissileType(id.toLowerCase(), value.getString("name", id), Math.max(1.0D, value.getDouble("fuel-seconds", fallbackFuel)), value.getDouble("explosion-power", 6.0D), value.getInt("hits-to-intercept", 1), value.getDouble("speed", fallbackSpeed), value.getStringList("lore")));
        }
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> new ArrayList<>(guided.values()).forEach(GuidedFlight::tick), 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (GuidedFlight flight : new ArrayList<>(guided.values())) {
            flight.finish(false, false);
        }
    }

    public StrikeLauncherItem item() {
        return item;
    }

    public List<GuidedMissileType> types() {
        return new ArrayList<>(types.values());
    }

    public GuidedMissileType type(String id) {
        return id == null ? null : types.get(id.toLowerCase());
    }

    public void initialize(Block block, StrikeLauncherItem.Kind kind, UUID owner) {
        mutate(block, pdc -> {
            pdc.set(launcherKindKey, PersistentDataType.STRING, kind.name());
            pdc.set(ownerKey, PersistentDataType.STRING, owner.toString());
            pdc.set(redstoneKey, PersistentDataType.BYTE, (byte) 0);
        });
        if (kind == StrikeLauncherItem.Kind.DRONE) {
            droneRegistry.register(block, owner);
        }
    }

    public StrikeLauncherItem.Kind kind(Block block) {
        PersistentDataContainer pdc = pdc(block);
        if (pdc == null) {
            return null;
        }
        String raw = pdc.get(launcherKindKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return StrikeLauncherItem.Kind.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public UUID owner(Block block) {
        PersistentDataContainer pdc = pdc(block);
        String raw = pdc == null ? null : pdc.get(ownerKey, PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void select(Player player, Block block) {
        selected.put(player.getUniqueId(), block);
    }

    public Block selected(Player player, StrikeLauncherItem.Kind expected) {
        Block block = selected.get(player.getUniqueId());
        return kind(block) == expected ? block : null;
    }

    public List<Block> selectedDroneLaunchers(Player player) {
        List<DroneLauncherRegistry.Entry> entries = droneRegistry.selected(player.getUniqueId());
        if (entries.isEmpty()) {
            Block block = selected(player, StrikeLauncherItem.Kind.DRONE);
            return block == null || !player.getUniqueId().equals(owner(block)) ? List.of() : List.of(block);
        }
        List<Block> result = new ArrayList<>();
        for (DroneLauncherRegistry.Entry entry : entries) {
            Block block = entry.block(true);
            if (kind(block) == StrikeLauncherItem.Kind.DRONE && player.getUniqueId().equals(owner(block))) {
                result.add(block);
            }
        }
        return result;
    }

    public DroneLauncherRegistry droneRegistry() {
        return droneRegistry;
    }

    public void registerMigratedDroneLauncher(Block block, UUID owner) {
        droneRegistry.registerMigrated(block, owner);
    }

    public void unregisterDroneLauncher(Block block) {
        droneRegistry.unregister(block);
    }

    public int clearDroneLaunchers(UUID owner) {
        return droneRegistry.clear(owner);
    }

    public void selectDroneLaunchers(Player player, List<DroneLauncherRegistry.Entry> entries) {
        droneRegistry.select(player.getUniqueId(), entries);
    }

    public void clearDroneSelection(Player player) {
        droneRegistry.select(player.getUniqueId(), List.of());
        selected.remove(player.getUniqueId());
    }

    public void target(Block block, int x, int z) {
        mutate(block, pdc -> {
            pdc.set(targetXKey, PersistentDataType.INTEGER, x);
            pdc.set(targetZKey, PersistentDataType.INTEGER, z);
        });
    }

    public int[] target(Block block) {
        PersistentDataContainer pdc = pdc(block);
        if (pdc == null || !pdc.has(targetXKey, PersistentDataType.INTEGER) || !pdc.has(targetZKey, PersistentDataType.INTEGER)) {
            return null;
        }
        return new int[]{pdc.get(targetXKey, PersistentDataType.INTEGER), pdc.get(targetZKey, PersistentDataType.INTEGER)};
    }

    public boolean redstone(Block block) {
        PersistentDataContainer pdc = pdc(block);
        return pdc != null && pdc.getOrDefault(redstoneKey, PersistentDataType.BYTE, (byte) 0) == 1;
    }

    public void redstone(Block block, boolean enabled) {
        mutate(block, pdc -> pdc.set(redstoneKey, PersistentDataType.BYTE, enabled ? (byte) 1 : (byte) 0));
    }

    public boolean launch(Player player, Block block) {
        if (player == null || block == null || !player.getUniqueId().equals(owner(block))) {
            return false;
        }
        return kind(block) == StrikeLauncherItem.Kind.DRONE ? launchDrone(player, block) : launchGuided(player, block);
    }

    public boolean launchFromPanel(Player player, Block block) {
        if (kind(block) != StrikeLauncherItem.Kind.DRONE) {
            return launch(player, block);
        }
        PersistentDataContainer pdc = pdc(block);
        long now = System.currentTimeMillis();
        long last = pdc == null ? 0L : pdc.getOrDefault(panelLaunchKey, PersistentDataType.LONG, 0L);
        if (now - last < 1000L) {
            message(player, "strike-drone-panel-cooldown");
            return false;
        }
        if (!launch(player, block)) {
            return false;
        }
        mutate(block, data -> data.set(panelLaunchKey, PersistentDataType.LONG, now));
        return true;
    }

    private boolean launchDrone(Player player, Block block) {
        int[] coordinates = target(block);
        if (coordinates == null) {
            message(player, "strike-target-missing");
            return false;
        }
        Dropper dropper = (Dropper) block.getState();
        ItemStack ammo = first(dropper.getInventory(), stack -> plugin.drones().item().isDrone(stack));
        DroneType type = ammo == null ? null : plugin.drones().type(plugin.drones().item().typeIdOf(ammo));
        if (type == null) {
            message(player, "strike-drone-ammo-missing");
            return false;
        }
        Location origin = launchPoint(block);
        double dx = coordinates[0] + 0.5D - origin.getX();
        double dz = coordinates[1] + 0.5D - origin.getZ();
        if (dx * dx + dz * dz > (double) type.maxDistance() * type.maxDistance()) {
            message(player, "strike-out-of-range");
            return false;
        }
        int y = block.getWorld().getHighestBlockYAt(coordinates[0], coordinates[1]) + 1;
        Location target = new Location(block.getWorld(), coordinates[0] + 0.5D, y, coordinates[1] + 0.5D);
        double lane = nextLane(block);
        if (!plugin.drones().launch(new AutonomousDroneMission(CombatPrincipal.player(player.getUniqueId()), origin, target, type.id(), lane, droneHeightAboveTarget))) {
            return false;
        }
        consume(dropper.getInventory(), ammo);
        block.getWorld().playSound(origin, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.6f, 1.5f);
        message(player, "strike-drone-launched");
        return true;
    }

    private boolean launchGuided(Player player, Block block) {
        if (guided.values().stream().anyMatch(flight -> flight.operator.equals(player.getUniqueId()) && !flight.ending)) {
            message(player, "strike-guided-busy");
            return false;
        }
        Dropper dropper = (Dropper) block.getState();
        ItemStack ammo = first(dropper.getInventory(), stack -> item.missileType(stack) != null);
        GuidedMissileType type = ammo == null ? null : type(item.missileType(ammo));
        if (type == null) {
            message(player, "strike-guided-ammo-missing");
            return false;
        }
        if (!launchPathClear(block)) {
            message(player, "strike-guided-launch-blocked");
            return false;
        }
        MissilePayload payload = plugin.payloads().read(ammo);
        consume(dropper.getInventory(), ammo);
        GuidedFlight flight = new GuidedFlight(player, block, type, payload);
        guided.put(flight.missile.getUniqueId(), flight);
        guidedByHitbox.put(flight.hitbox.getUniqueId(), flight);
        message(player, "strike-guided-launched");
        return true;
    }

    public boolean intercept(Entity entity) {
        GuidedFlight flight = guidedOf(entity);
        return flight != null && !flight.launchProtected() && flight.finish(true, true);
    }

    public Entity guidedBody(Entity entity) {
        GuidedFlight flight = guidedOf(entity);
        return flight == null ? null : flight.missile;
    }

    public CombatPrincipal guidedOwner(Entity entity) {
        GuidedFlight flight = guidedOf(entity);
        return flight == null ? null : CombatPrincipal.player(flight.owner);
    }

    public boolean isGuided(Entity entity) {
        return guidedOf(entity) != null;
    }

    public boolean isGuidedLaunchProtected(Entity entity) {
        GuidedFlight flight = guidedOf(entity);
        return flight != null && flight.launchProtected();
    }

    public boolean isGuidedBody(Entity entity) {
        return entity != null && guided.containsKey(entity.getUniqueId());
    }

    public int ammoCount(Block block) {
        if (!(block.getState() instanceof Dropper dropper)) {
            return 0;
        }
        StrikeLauncherItem.Kind kind = kind(block);
        int amount = 0;
        for (ItemStack stack : dropper.getInventory().getContents()) {
            if (stack == null) {
                continue;
            }
            boolean accepted = kind == StrikeLauncherItem.Kind.DRONE ? plugin.drones().item().isDrone(stack) : item.missileType(stack) != null;
            if (accepted) {
                amount += stack.getAmount();
            }
        }
        return amount;
    }

    private GuidedFlight guidedOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        GuidedFlight direct = guided.get(entity.getUniqueId());
        return direct != null ? direct : guidedByHitbox.get(entity.getUniqueId());
    }

    public void handleRedstone(Block block) {
        if (!redstone(block)) {
            return;
        }
        String key = block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
        long now = System.currentTimeMillis();
        if (now - redstoneDebounce.getOrDefault(key, 0L) < 500L) {
            return;
        }
        redstoneDebounce.put(key, now);
        UUID owner = owner(block);
        Player player = owner == null ? null : plugin.getServer().getPlayer(owner);
        if (player != null && player.isOnline()) {
            launch(player, block);
        }
    }

    private double nextLane(Block block) {
        PersistentDataContainer pdc = pdc(block);
        long now = System.currentTimeMillis();
        long last = pdc == null ? 0L : pdc.getOrDefault(lastLaunchKey, PersistentDataType.LONG, 0L);
        int current = pdc == null ? 1 : pdc.getOrDefault(laneKey, PersistentDataType.INTEGER, 1);
        int next = now - last < droneLaneResetMillis ? (current + 1) % 3 : 1;
        mutate(block, data -> {
            data.set(lastLaunchKey, PersistentDataType.LONG, now);
            data.set(laneKey, PersistentDataType.INTEGER, next);
        });
        return switch (next) {
            case 0 -> -droneLaneOffset;
            case 2 -> droneLaneOffset;
            default -> 0.0D;
        };
    }

    private Location launchPoint(Block block) {
        return block.getLocation().add(0.5D, 0.65D, 0.5D).add(facing(block).multiply(1.4D));
    }

    private boolean launchPathClear(Block block) {
        Vector direction = facing(block);
        Location start = block.getLocation().add(0.5D, 0.65D, 0.5D).add(direction.clone().multiply(0.6D));
        return block.getWorld().rayTraceBlocks(start, direction, 2.2D, FluidCollisionMode.NEVER, true) == null;
    }

    private Vector facing(Block block) {
        return block.getBlockData() instanceof Directional directional ? directional.getFacing().getDirection().normalize() : new Vector(0, 0, 1);
    }

    private ItemStack first(Inventory inventory, java.util.function.Predicate<ItemStack> filter) {
        for (ItemStack stack : inventory.getContents()) {
            if (filter.test(stack)) {
                return stack;
            }
        }
        return null;
    }

    private void consume(Inventory inventory, ItemStack item) {
        int slot = inventory.first(item);
        if (slot < 0) {
            return;
        }
        ItemStack stored = inventory.getItem(slot);
        if (stored == null || stored.getAmount() <= 1) {
            inventory.setItem(slot, null);
            return;
        }
        ItemStack remainder = stored.clone();
        remainder.setAmount(stored.getAmount() - 1);
        inventory.setItem(slot, remainder);
    }

    private RayTraceResult nearest(Location from, RayTraceResult first, RayTraceResult second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.getHitPosition().distanceSquared(from.toVector()) <= second.getHitPosition().distanceSquared(from.toVector()) ? first : second;
    }

    private PersistentDataContainer pdc(Block block) {
        return block != null && block.getState() instanceof TileState state ? state.getPersistentDataContainer() : null;
    }

    private void mutate(Block block, java.util.function.Consumer<PersistentDataContainer> mutation) {
        if (!(block.getState() instanceof TileState state)) {
            return;
        }
        mutation.accept(state.getPersistentDataContainer());
        state.update(true, false);
    }

    public void message(Player player, String key) {
        player.sendMessage(ua.bobster.defence.util.MessageUtil.parse(plugin.prefix() + plugin.message(key)));
    }
}
