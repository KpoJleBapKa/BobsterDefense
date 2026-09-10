package ua.bobster.defence.strategicstates.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record StatePosition(String world, double x, double y, double z) {

    public static StatePosition from(Location location) {
        return new StatePosition(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    public Location location() {
        World target = Bukkit.getWorld(world);
        return target == null ? null : new Location(target, x, y, z);
    }
}
