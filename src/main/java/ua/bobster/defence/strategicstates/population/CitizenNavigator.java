package ua.bobster.defence.strategicstates.population;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Pillager;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CitizenNavigator {

    public enum MoveResult {
        ARRIVED,
        MOVING,
        UNLOADED
    }

    private static final class NavigationState {

        private Location target;
        private Location lastPosition;
        private long lastPath;
        private long lastProgress;
        private Boat boat;
    }

    private static final Map<UUID, NavigationState> states = new HashMap<>();

    public static void forget(UUID entityId) {
        NavigationState state = states.remove(entityId);
        if (state != null && state.boat != null && state.boat.isValid()) {
            state.boat.remove();
        }
    }

    public MoveResult move(Pillager pillager, Location target, double speed) {
        if (pillager == null || target == null || pillager.getWorld() != target.getWorld()) {
            return MoveResult.UNLOADED;
        }
        pillager.setAware(true);
        NavigationState state = states.computeIfAbsent(pillager.getUniqueId(), ignored -> new NavigationState());
        cleanupBoat(state, pillager);
        if (state.boat != null) {
            return sail(pillager, target, speed, state);
        }
        Location current = pillager.getLocation();
        if (horizontalDistanceSquared(current, target) <= 4.0D) {
            pillager.getPathfinder().stopPathfinding();
            state.target = target.clone();
            return MoveResult.ARRIVED;
        }
        Location rescue = rescueLocation(current);
        if (rescue != null) {
            pillager.teleport(rescue);
            current = rescue;
        }
        Location water = waterAhead(current, target);
        if (water != null && horizontalDistanceSquared(current, target) > 64.0D && board(pillager, water, state)) {
            return MoveResult.MOVING;
        }
        Location waypoint = loadedWaypoint(current, target, 32.0D);
        if (waypoint == null || horizontalDistanceSquared(current, waypoint) < 1.0D && horizontalDistanceSquared(current, target) > 16.0D) {
            return MoveResult.UNLOADED;
        }
        long now = System.currentTimeMillis();
        boolean targetChanged = state.target == null || state.target.getWorld() != target.getWorld() || state.target.distanceSquared(target) > 4.0D;
        Pathfinder pathfinder = pillager.getPathfinder();
        if (targetChanged || !pathfinder.hasPath() || now - state.lastPath >= 1500L) {
            pathfinder.moveTo(waypoint, Math.clamp(speed * 2.5D, 0.65D, 1.25D));
            state.target = target.clone();
            state.lastPath = now;
        }
        recoverStuck(pillager, waypoint, state, now, speed);
        pillager.lookAt(waypoint);
        return MoveResult.MOVING;
    }

    private void recoverStuck(Pillager pillager, Location waypoint, NavigationState state, long now, double speed) {
        Location current = pillager.getLocation();
        if (state.lastPosition == null || state.lastPosition.getWorld() != current.getWorld() || horizontalDistanceSquared(state.lastPosition, current) >= 0.16D) {
            state.lastPosition = current.clone();
            state.lastProgress = now;
            return;
        }
        if (now - state.lastProgress < 4000L) {
            return;
        }
        Location detour = detour(current, waypoint, pillager.getUniqueId());
        if (detour != null) {
            pillager.getPathfinder().moveTo(detour, Math.clamp(speed * 2.75D, 0.7D, 1.3D));
            state.lastPath = now;
        }
        state.lastPosition = current.clone();
        state.lastProgress = now;
    }

    private Location detour(Location current, Location target, UUID id) {
        Vector direction = target.toVector().subtract(current.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.01D) {
            return null;
        }
        direction.normalize();
        double sign = Math.floorMod(id.hashCode(), 2) == 0 ? 1.0D : -1.0D;
        Vector side = new Vector(-direction.getZ() * sign, 0.0D, direction.getX() * sign).multiply(4.0D);
        Location candidate = current.clone().add(direction.clone().multiply(2.0D)).add(side);
        return safeSurface(candidate, 3);
    }

    private Location loadedWaypoint(Location current, Location target, double maximum) {
        Vector delta = target.toVector().subtract(current.toVector()).setY(0.0D);
        double distance = delta.length();
        if (distance < 0.01D) {
            return target.clone();
        }
        Vector direction = delta.normalize();
        Location last = current.clone();
        double limit = Math.min(distance, maximum);
        for (double travelled = 2.0D; travelled <= limit; travelled += 2.0D) {
            Location candidate = current.clone().add(direction.clone().multiply(travelled));
            if (!candidate.getWorld().isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) {
                break;
            }
            last = candidate;
        }
        if (horizontalDistanceSquared(last, target) <= 4.0D) {
            return target.clone();
        }
        Location surface = safeSurface(last, 6);
        return surface == null ? last : surface;
    }

    private Location waterAhead(Location current, Location target) {
        Vector direction = target.toVector().subtract(current.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 1.0D) {
            return null;
        }
        direction.normalize();
        for (double step = 0.0D; step <= 3.0D; step += 0.5D) {
            Location candidate = current.clone().add(direction.clone().multiply(step));
            if (!candidate.getWorld().isChunkLoaded(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4)) {
                return null;
            }
            Location water = waterSurface(candidate);
            if (water != null) {
                return water;
            }
        }
        return null;
    }

    private boolean board(Pillager pillager, Location water, NavigationState state) {
        Boat boat = water.getWorld().spawn(water, Boat.class, spawned -> {
            spawned.setPersistent(false);
            spawned.setInvulnerable(true);
        });
        if (!boat.addPassenger(pillager)) {
            boat.remove();
            return false;
        }
        pillager.getPathfinder().stopPathfinding();
        state.boat = boat;
        state.lastProgress = System.currentTimeMillis();
        return true;
    }

    private MoveResult sail(Pillager pillager, Location target, double speed, NavigationState state) {
        Boat boat = state.boat;
        Location current = boat.getLocation();
        if (horizontalDistanceSquared(current, target) <= 9.0D) {
            disembark(pillager, state, target);
            return MoveResult.ARRIVED;
        }
        Vector direction = target.toVector().subtract(current.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.01D) {
            disembark(pillager, state, target);
            return MoveResult.ARRIVED;
        }
        direction.normalize();
        Location next = current.clone().add(direction.clone().multiply(2.0D));
        if (!next.getWorld().isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)) {
            return MoveResult.UNLOADED;
        }
        if (waterSurface(next) == null) {
            Location shore = safeSurface(next, 5);
            disembark(pillager, state, shore == null ? current : shore);
            return MoveResult.MOVING;
        }
        double boatSpeed = Math.clamp(speed * 1.35D, 0.28D, 0.55D);
        boat.setVelocity(direction.multiply(boatSpeed));
        boat.setRotation((float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ())), 0.0F);
        return MoveResult.MOVING;
    }

    private void disembark(Pillager pillager, NavigationState state, Location preferred) {
        Boat boat = state.boat;
        if (boat != null) {
            boat.eject();
        }
        Location safe = safeSurface(preferred, 6);
        if (safe != null && safe.getWorld().isChunkLoaded(safe.getBlockX() >> 4, safe.getBlockZ() >> 4)) {
            pillager.teleport(safe);
        }
        if (boat != null) {
            boat.remove();
        }
        state.boat = null;
        state.lastPosition = pillager.getLocation().clone();
        state.lastProgress = System.currentTimeMillis();
    }

    private void cleanupBoat(NavigationState state, Pillager pillager) {
        if (state.boat == null) {
            return;
        }
        if (!state.boat.isValid() || !state.boat.getPassengers().contains(pillager)) {
            if (state.boat.isValid()) {
                state.boat.remove();
            }
            state.boat = null;
        }
    }

    private Location rescueLocation(Location current) {
        if (passable(current)) {
            return null;
        }
        return safeSurface(current, 5);
    }

    private Location safeSurface(Location around, int radius) {
        World world = around.getWorld();
        if (world == null) {
            return null;
        }
        for (int distance = 0; distance <= radius; distance++) {
            for (int x = -distance; x <= distance; x++) {
                for (int z = -distance; z <= distance; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != distance) {
                        continue;
                    }
                    int blockX = around.getBlockX() + x;
                    int blockZ = around.getBlockZ() + z;
                    if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                        continue;
                    }
                    int baseY = Math.clamp(around.getBlockY(), world.getMinHeight() + 1, world.getMaxHeight() - 2);
                    for (int offset = 4; offset >= -6; offset--) {
                        int y = baseY + offset;
                        if (safe(world, blockX, y, blockZ)) {
                            return new Location(world, blockX + 0.5D, y, blockZ + 0.5D, around.getYaw(), around.getPitch());
                        }
                    }
                }
            }
        }
        return null;
    }

    private Location waterSurface(Location around) {
        World world = around.getWorld();
        if (world == null) {
            return null;
        }
        int x = around.getBlockX();
        int z = around.getBlockZ();
        int baseY = Math.clamp(around.getBlockY(), world.getMinHeight(), world.getMaxHeight() - 1);
        for (int offset = 3; offset >= -5; offset--) {
            int y = baseY + offset;
            if (world.getBlockAt(x, y, z).getType() == Material.WATER && world.getBlockAt(x, y + 1, z).isPassable()) {
                return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D, around.getYaw(), 0.0F);
            }
        }
        return null;
    }

    private boolean passable(Location location) {
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return world.getBlockAt(x, y, z).isPassable() && world.getBlockAt(x, y + 1, z).isPassable();
    }

    private boolean safe(World world, int x, int y, int z) {
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        return ground.isSolid() && ground != Material.MAGMA_BLOCK && ground != Material.CACTUS
                && world.getBlockAt(x, y, z).isPassable() && world.getBlockAt(x, y + 1, z).isPassable();
    }

    private double horizontalDistanceSquared(Location first, Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }
}
