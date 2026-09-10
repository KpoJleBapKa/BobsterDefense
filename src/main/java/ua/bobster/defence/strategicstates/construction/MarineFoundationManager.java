package ua.bobster.defence.strategicstates.construction;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public class MarineFoundationManager {

    public void prepare(StateBuilding building) {
        Location origin = building.origin().location();
        if (origin == null || !marine(building, origin.getWorld())) {
            return;
        }
        Material support = industrial(building.type()) ? Material.GRAY_CONCRETE : Material.STRIPPED_OAK_LOG;
        Material deck = industrial(building.type()) ? Material.SMOOTH_STONE : Material.OAK_PLANKS;
        int baseY = origin.getBlockY() - 1;
        for (int x = 0; x < building.sizeX(); x++) {
            for (int z = 0; z < building.sizeZ(); z++) {
                origin.getWorld().getBlockAt(origin.getBlockX() + x, baseY, origin.getBlockZ() + z).setType(deck, false);
            }
        }
        for (int x = 0; x < building.sizeX(); x += 4) {
            for (int z = 0; z < building.sizeZ(); z += 4) {
                pillar(origin.getWorld(), origin.getBlockX() + x, baseY - 1, origin.getBlockZ() + z, support);
            }
        }
        pillar(origin.getWorld(), origin.getBlockX() + building.sizeX() - 1, baseY - 1, origin.getBlockZ() + building.sizeZ() - 1, support);
        bridge(building, origin.getWorld(), baseY, deck, support);
    }

    private boolean marine(StateBuilding building, World world) {
        int y = (int) building.origin().y() - 1;
        for (int x = 0; x < building.sizeX(); x += Math.max(1, building.sizeX() / 4)) {
            for (int z = 0; z < building.sizeZ(); z += Math.max(1, building.sizeZ() / 4)) {
                Block block = world.getBlockAt((int) building.origin().x() + x, y, (int) building.origin().z() + z);
                if (block.isLiquid() || !block.getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void bridge(StateBuilding building, World world, int y, Material deck, Material support) {
        int centerX = (int) building.origin().x() + building.sizeX() / 2;
        int centerZ = (int) building.origin().z() + building.sizeZ() / 2;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] direction : directions) {
            int edge = direction[0] == 0 ? building.sizeZ() / 2 + 1 : building.sizeX() / 2 + 1;
            for (int distance = edge; distance <= 64; distance++) {
                int x = centerX + direction[0] * distance;
                int z = centerZ + direction[1] * distance;
                Block surface = world.getHighestBlockAt(x, z);
                if (surface.getType().isSolid() && !surface.getType().name().contains("LEAVES") && Math.abs(surface.getY() - y) <= 8) {
                    if (distance < bestDistance) {
                        best = direction;
                        bestDistance = distance;
                    }
                    break;
                }
            }
        }
        if (best == null) {
            return;
        }
        int edge = best[0] == 0 ? building.sizeZ() / 2 : building.sizeX() / 2;
        for (int distance = edge; distance < bestDistance; distance++) {
            int x = centerX + best[0] * distance;
            int z = centerZ + best[1] * distance;
            for (int width = -1; width <= 1; width++) {
                int pathX = x + best[1] * width;
                int pathZ = z + best[0] * width;
                world.getBlockAt(pathX, y, pathZ).setType(deck, false);
                if ((distance - edge) % 4 == 0 && width != 0) {
                    pillar(world, pathX, y - 1, pathZ, support);
                }
            }
        }
    }

    private void pillar(World world, int x, int y, int z, Material material) {
        int minimum = world.getMinHeight() + 1;
        for (int current = y; current >= minimum; current--) {
            Block block = world.getBlockAt(x, current, z);
            if (block.getType().isSolid() && !block.isLiquid()) {
                return;
            }
            block.setType(material, false);
        }
    }

    private boolean industrial(BuildingType type) {
        return type == BuildingType.WAREHOUSE || type == BuildingType.WORKSHOP || type == BuildingType.BARRACKS
                || type == BuildingType.MILITARY_FACTORY || type == BuildingType.DRONE_FACTORY
                || type == BuildingType.MISSILE_FACTORY || type == BuildingType.AIR_DEFENCE_SITE
                || type == BuildingType.COMMAND_CENTER;
    }
}
