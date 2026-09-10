package ua.bobster.defence.strategicstates.construction;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import ua.bobster.defence.BobsterDefence;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PaperStructureTemplateAdapter implements StructureTemplateAdapter {

    private final BobsterDefence plugin;
    private final File folder;
    private final Map<String, Structure> templates = new HashMap<>();
    private final Map<String, StructureTemplatePlan> plans = new HashMap<>();
    private final VanillaStructureReader reader = new VanillaStructureReader();
    private final Set<Material> replaceable = EnumSet.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH, Material.SNOW, Material.VINE, Material.GLOW_LICHEN);
    private final boolean terrainOverwrite;

    public PaperStructureTemplateAdapter(BobsterDefence plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "states/schematics");
        this.terrainOverwrite = plugin.getConfig().getBoolean("strategic-states.construction.allow-terrain-overwrite", true);
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Не вдалося створити states/schematics");
        }
        new DefaultStructureInstaller(plugin, folder).install();
    }

    @Override
    public TemplateInfo inspect(String name) {
        Structure structure = load(name);
        if (structure == null) {
            return null;
        }
        BlockVector size = structure.getSize();
        return new TemplateInfo(name, size.getBlockX(), size.getBlockY(), size.getBlockZ());
    }

    @Override
    public StructureTemplatePlan plan(String name) {
        if (plans.containsKey(name)) {
            return plans.get(name);
        }
        if (load(name) == null) {
            return null;
        }
        try {
            StructureTemplatePlan plan = reader.read(new File(folder, name));
            plans.put(name, plan);
            return plan;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Не вдалося розібрати blocks із structure " + name + ": " + ex.getMessage());
            return null;
        }
    }

    @Override
    public MaterializationResult validate(StateBuilding building, StructureTemplatePlan plan, int completedBlocks) {
        if (plan == null) {
            return new MaterializationResult(false, false, "Шаблон відсутній або пошкоджений: " + building.template());
        }
        if (plan.sizeX() != building.sizeX() || plan.sizeY() != building.sizeY() || plan.sizeZ() != building.sizeZ()) {
            return new MaterializationResult(false, false, "Розмір шаблону змінився після планування");
        }
        World world = Bukkit.getWorld(building.origin().world());
        if (world == null) {
            return new MaterializationResult(false, false, "Світ не завантажений: " + building.origin().world());
        }
        if (!allChunksLoaded(world, building)) {
            return new MaterializationResult(false, true, "Очікування чанків");
        }
        Block occupied = firstOccupied(world, building, plan, completedBlocks);
        if (occupied != null) {
            return new MaterializationResult(false, false, "Footprint зайнятий блоком " + occupied.getType() + " at " + occupied.getX() + " " + occupied.getY() + " " + occupied.getZ());
        }
        return new MaterializationResult(true, false, "Footprint вільний");
    }

    @Override
    public int recoverCompletedBlocks(StateBuilding building, StructureTemplatePlan plan, int completedBlocks) {
        int recovered = Math.clamp(completedBlocks, 0, plan.blocks().size());
        while (recovered < plan.blocks().size() && matches(building, plan.blocks().get(recovered))) {
            recovered++;
        }
        return recovered;
    }

    @Override
    public Location place(StateBuilding building, StructureBlockPlacement placement) {
        World world = Bukkit.getWorld(building.origin().world());
        if (world == null) {
            return null;
        }
        Block block = block(world, building, placement);
        if (matches(building, placement)) {
            return block.getLocation().add(0.5D, 0.5D, 0.5D);
        }
        if (!canReplace(block.getType())) {
            return null;
        }
        block.setBlockData(Bukkit.createBlockData(placement.blockData()), false);
        return block.getLocation().add(0.5D, 0.5D, 0.5D);
    }

    private Structure load(String name) {
        if (templates.containsKey(name)) {
            return templates.get(name);
        }
        File file = new File(folder, name);
        if (!file.isFile()) {
            return null;
        }
        try {
            Structure structure = Bukkit.getStructureManager().loadStructure(file);
            templates.put(name, structure);
            return structure;
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Не вдалося прочитати structure " + file.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private boolean allChunksLoaded(World world, StateBuilding building) {
        int minimumX = (int) building.origin().x() >> 4;
        int maximumX = ((int) building.origin().x() + building.sizeX() - 1) >> 4;
        int minimumZ = (int) building.origin().z() >> 4;
        int maximumZ = ((int) building.origin().z() + building.sizeZ() - 1) >> 4;
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Block firstOccupied(World world, StateBuilding building, StructureTemplatePlan plan, int completedBlocks) {
        int originX = (int) building.origin().x();
        int originY = (int) building.origin().y();
        int originZ = (int) building.origin().z();
        for (int x = 0; x < building.sizeX(); x++) {
            for (int y = 0; y < building.sizeY(); y++) {
                for (int z = 0; z < building.sizeZ(); z++) {
                    Block block = world.getBlockAt(originX + x, originY + y, originZ + z);
                    StructureBlockPlacement placement = placementAt(plan, x, y, z);
                    boolean completed = placement != null && plan.blocks().indexOf(placement) < completedBlocks;
                    if (!canReplace(block.getType()) && !(completed && block.getBlockData().matches(Bukkit.createBlockData(placement.blockData())))) {
                        return block;
                    }
                }
            }
        }
        return null;
    }

    private boolean canReplace(Material material) {
        if (replaceable.contains(material)) {
            return true;
        }
        return terrainOverwrite && material != Material.BEDROCK && material != Material.BARRIER
                && material != Material.END_PORTAL && material != Material.END_PORTAL_FRAME
                && material != Material.NETHER_PORTAL && material != Material.REINFORCED_DEEPSLATE;
    }

    private StructureBlockPlacement placementAt(StructureTemplatePlan plan, int x, int y, int z) {
        for (StructureBlockPlacement placement : plan.blocks()) {
            if (placement.x() == x && placement.y() == y && placement.z() == z) {
                return placement;
            }
        }
        return null;
    }

    private boolean matches(StateBuilding building, StructureBlockPlacement placement) {
        World world = Bukkit.getWorld(building.origin().world());
        if (world == null) {
            return false;
        }
        return block(world, building, placement).getBlockData().matches(Bukkit.createBlockData(placement.blockData()));
    }

    private Block block(World world, StateBuilding building, StructureBlockPlacement placement) {
        return world.getBlockAt((int) building.origin().x() + placement.x(), (int) building.origin().y() + placement.y(), (int) building.origin().z() + placement.z());
    }
}
