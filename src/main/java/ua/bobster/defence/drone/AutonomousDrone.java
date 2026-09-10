package ua.bobster.defence.drone;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.bobster.defence.combat.CombatPrincipal;

import java.util.HashSet;
import java.util.Set;

final class AutonomousDrone {

    private final DroneManager manager;
    private final CombatPrincipal owner;
    private final DroneType type;
    private final Location origin;
    private final Location target;
    private final Vector p0;
    private final Vector p1;
    private final Vector p2;
    private final Vector p3;
    private final double pathLength;
    private final ArmorStand hitbox;
    private final BlockDisplay display;
    private final Set<Long> tickets = new HashSet<>();
    private Location current;
    private double progress;
    private int ticks;
    private boolean ending;

    AutonomousDrone(DroneManager manager, AutonomousDroneMission mission, DroneType type) {
        this.manager = manager;
        this.owner = mission.owner();
        this.type = type;
        this.origin = mission.origin().clone();
        this.target = mission.target().clone();
        this.current = origin.clone();
        this.p0 = origin.toVector();
        this.p3 = target.toVector();
        Vector horizontal = p3.clone().subtract(p0).setY(0);
        double horizontalLength = horizontal.length();
        Vector forward = horizontalLength < 1.0E-6D ? new Vector(0, 0, 1) : horizontal.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX()).multiply(mission.laneOffset());
        double cruiseY = Math.max(origin.getY() + 8.0D, target.getY() + Math.max(1.0D, mission.heightAboveTarget()));
        this.p1 = p0.clone().add(forward.clone().multiply(horizontalLength * 0.28D)).add(side).setY(cruiseY);
        this.p2 = p3.clone().add(side).setY(cruiseY);
        this.pathLength = estimateLength();
        this.hitbox = manager.spawnHitbox(origin.getWorld(), origin, owner);
        this.display = manager.spawnDisplay(origin.getWorld(), origin, type);
        holdChunks(origin);
    }

    public ArmorStand hitbox() {
        return hitbox;
    }

    public CombatPrincipal owner() {
        return owner;
    }

    public DroneType type() {
        return type;
    }

    public boolean ending() {
        return ending;
    }

    public void tick() {
        if (ending) {
            return;
        }
        ticks++;
        if (!hitbox.isValid() || ticks > type.flightTime() * 20) {
            manager.end(this, true);
            return;
        }
        progress = Math.min(1.0D, progress + type.speed() / pathLength);
        Location next = pointAt(progress);
        Vector movement = next.toVector().subtract(current.toVector());
        if (progress >= 1.0D || movement.lengthSquared() < 1.0E-6D) {
            current = target.clone();
            move();
            manager.end(this, false);
            return;
        }
        Vector direction = movement.clone().normalize();
        RayTraceResult blockCollision = current.getWorld().rayTraceBlocks(current, direction, movement.length(), FluidCollisionMode.NEVER, true);
        RayTraceResult entityCollision = current.getWorld().rayTraceEntities(current, direction, movement.length(), 0.6D, this::canHit);
        RayTraceResult collision = nearest(blockCollision, entityCollision);
        if (collision != null) {
            current = collision.getHitPosition().toLocation(current.getWorld());
            move();
            manager.end(this, false);
            return;
        }
        current = next;
        current.setDirection(movement);
        move();
        holdChunks(current);
        current.getWorld().spawnParticle(Particle.SMOKE, current, 1, 0.03D, 0.03D, 0.03D, 0.0D);
    }

    private boolean canHit(Entity entity) {
        return manager.canAutonomousHit(owner, hitbox, display, entity);
    }

    private RayTraceResult nearest(RayTraceResult first, RayTraceResult second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.getHitPosition().distanceSquared(current.toVector()) <= second.getHitPosition().distanceSquared(current.toVector()) ? first : second;
    }

    private void move() {
        hitbox.teleport(current.clone().subtract(0, 0.5D, 0));
        Location model = current.clone().add(current.getDirection().normalize().multiply(-0.35D)).add(0, -0.55D, 0);
        model.setPitch(0);
        display.teleport(model);
    }

    private Location pointAt(double t) {
        double u = 1.0D - t;
        Vector point = p0.clone().multiply(u * u * u)
                .add(p1.clone().multiply(3.0D * u * u * t))
                .add(p2.clone().multiply(3.0D * u * t * t))
                .add(p3.clone().multiply(t * t * t));
        return point.toLocation(origin.getWorld());
    }

    private double estimateLength() {
        double length = 0.0D;
        Vector previous = p0.clone();
        for (int index = 1; index <= 32; index++) {
            double t = index / 32.0D;
            double u = 1.0D - t;
            Vector point = p0.clone().multiply(u * u * u)
                    .add(p1.clone().multiply(3.0D * u * u * t))
                    .add(p2.clone().multiply(3.0D * u * t * t))
                    .add(p3.clone().multiply(t * t * t));
            length += point.distance(previous);
            previous = point;
        }
        return Math.max(1.0D, length);
    }

    private void holdChunks(Location location) {
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        Set<Long> wanted = new HashSet<>();
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                wanted.add(((long) x << 32) | (z & 0xFFFFFFFFL));
            }
        }
        for (Long key : wanted) {
            if (tickets.add(key)) {
                origin.getWorld().addPluginChunkTicket((int) (key >> 32), key.intValue(), manager.plugin());
            }
        }
        tickets.removeIf(key -> {
            if (wanted.contains(key)) {
                return false;
            }
            origin.getWorld().removePluginChunkTicket((int) (key >> 32), key.intValue(), manager.plugin());
            return true;
        });
    }

    public boolean beginEnding() {
        if (ending) {
            return false;
        }
        ending = true;
        return true;
    }

    public void cleanup() {
        for (Long key : tickets) {
            origin.getWorld().removePluginChunkTicket((int) (key >> 32), key.intValue(), manager.plugin());
        }
        tickets.clear();
        display.remove();
        hitbox.remove();
    }
}
