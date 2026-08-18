package ua.bobster.defence.raid;

import java.util.List;
import java.util.UUID;

/**
 * Територія, виділена в TerritoryMap.
 *
 * @param key   ключ у territories.yml (нік у нижньому регістрі)
 * @param name  відображувана назва, напр. «Solaria»
 * @param owner     нік власника
 * @param ownerUuid UUID власника — саме за ним визначається, чи снаряд свій
 * @param world світ, у якому вона намальована
 */
public record TerritoryArea(String key,
                            String name,
                            String owner,
                            UUID ownerUuid,
                            String world,
                            List<TerritoryPolygon> parts) {

    public boolean contains(String world, double x, double z) {
        if (!this.world.equals(world)) {
            return false;
        }
        for (TerritoryPolygon part : parts) {
            if (part.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** @return відстань до межі території, або Double.MAX_VALUE для іншого світу */
    public double distanceTo(String world, double x, double z) {
        if (!this.world.equals(world)) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (TerritoryPolygon part : parts) {
            if (!part.withinBoundingBox(x, z, best == Double.MAX_VALUE ? 4096 : best)) {
                continue;
            }
            best = Math.min(best, part.distanceTo(x, z));
            if (best == 0.0D) {
                return 0.0D;
            }
        }
        return best;
    }

    public boolean withinRadius(String world, double x, double z, double radius) {
        if (!this.world.equals(world)) {
            return false;
        }
        for (TerritoryPolygon part : parts) {
            if (part.withinBoundingBox(x, z, radius) && part.distanceTo(x, z) <= radius) {
                return true;
            }
        }
        return false;
    }
}
