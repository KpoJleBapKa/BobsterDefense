package ua.bobster.defence.strategicstates.construction;

import org.bukkit.Bukkit;
import ua.bobster.defence.BobsterDefence;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class DefaultStructureInstaller {

    private record StructureBlock(int x, int y, int z, String blockData) {
    }

    private static final class Blueprint {

        private final Map<String, StructureBlock> blocks = new LinkedHashMap<>();

        private void set(int x, int y, int z, String data) {
            blocks.put(x + ":" + y + ":" + z, new StructureBlock(x, y, z, data));
        }

        private List<StructureBlock> list() {
            return new ArrayList<>(blocks.values());
        }
    }

    private final BobsterDefence plugin;
    private final File folder;

    public DefaultStructureInstaller(BobsterDefence plugin, File folder) {
        this.plugin = plugin;
        this.folder = folder;
    }

    public void install() {
        install("house_02.nbt", 11, 8, 11, house());
        install("farm_02.nbt", 15, 6, 15, farm());
        install("warehouse_02.nbt", 19, 9, 23, warehouse());
        install("workshop_02.nbt", 15, 8, 15, workshop());
        install("barracks_02.nbt", 17, 8, 15, barracks());
        install("military_factory_02.nbt", 21, 11, 25, factory("MILITARY"));
        install("drone_factory_02.nbt", 21, 11, 25, factory("DRONE"));
        install("missile_factory_02.nbt", 21, 12, 29, factory("MISSILE"));
        install("air_defence_02.nbt", 21, 8, 21, airDefence());
    }

    private void install(String name, int sizeX, int sizeY, int sizeZ, List<StructureBlock> blocks) {
        File target = new File(folder, name);
        if (target.isFile()) {
            return;
        }
        File temporary = new File(folder, name + ".tmp");
        try {
            validate(name, sizeX, sizeY, sizeZ, blocks);
            write(temporary, sizeX, sizeY, sizeZ, blocks);
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Не вдалося встановити default structure " + name + ": " + ex.getMessage());
            if (temporary.isFile() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    private void validate(String name, int sizeX, int sizeY, int sizeZ, List<StructureBlock> blocks) throws IOException {
        for (StructureBlock block : blocks) {
            if (block.x() < 0 || block.x() >= sizeX || block.y() < 0 || block.y() >= sizeY || block.z() < 0 || block.z() >= sizeZ) {
                throw new IOException("Block поза межами " + name + ": " + block.x() + " " + block.y() + " " + block.z());
            }
            Bukkit.createBlockData(block.blockData());
        }
    }

    private void write(File file, int sizeX, int sizeY, int sizeZ, List<StructureBlock> blocks) throws IOException {
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (StructureBlock block : blocks) {
            palette.computeIfAbsent(block.blockData(), ignored -> palette.size());
        }
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            output.writeByte(10);
            output.writeUTF("");
            intTag(output, "DataVersion", 3955);
            intList(output, "size", sizeX, sizeY, sizeZ);
            output.writeByte(9);
            output.writeUTF("palette");
            output.writeByte(10);
            output.writeInt(palette.size());
            for (String data : palette.keySet()) {
                paletteEntry(output, data);
                output.writeByte(0);
            }
            output.writeByte(9);
            output.writeUTF("blocks");
            output.writeByte(10);
            output.writeInt(blocks.size());
            for (StructureBlock block : blocks) {
                intList(output, "pos", block.x(), block.y(), block.z());
                intTag(output, "state", palette.get(block.blockData()));
                output.writeByte(0);
            }
            output.writeByte(9);
            output.writeUTF("entities");
            output.writeByte(10);
            output.writeInt(0);
            output.writeByte(0);
        }
    }

    private void paletteEntry(DataOutputStream output, String data) throws IOException {
        int bracket = data.indexOf('[');
        stringTag(output, "Name", bracket < 0 ? data : data.substring(0, bracket));
        if (bracket < 0 || !data.endsWith("]")) {
            return;
        }
        output.writeByte(10);
        output.writeUTF("Properties");
        String properties = data.substring(bracket + 1, data.length() - 1);
        for (String property : properties.split(",")) {
            String[] pair = property.split("=", 2);
            if (pair.length == 2) {
                stringTag(output, pair[0], pair[1]);
            }
        }
        output.writeByte(0);
    }

    private List<StructureBlock> house() {
        Blueprint b = new Blueprint();
        rectangle(b, 0, 0, 0, 10, 10, "minecraft:stone_bricks");
        rectangle(b, 1, 1, 1, 9, 9, "minecraft:spruce_planks");
        for (int y = 1; y <= 4; y++) {
            perimeter(b, 0, y, 0, 10, 10, "minecraft:spruce_planks");
        }
        for (int x : new int[]{0, 10}) {
            for (int z : new int[]{0, 10}) {
                column(b, x, 1, z, 5, "minecraft:stripped_spruce_log[axis=y]");
            }
        }
        opening(b, 5, 1, 0, 2);
        b.set(5, 1, 0, "minecraft:spruce_door[facing=south,half=lower,hinge=left,open=false,powered=false]");
        b.set(5, 2, 0, "minecraft:spruce_door[facing=south,half=upper,hinge=left,open=false,powered=false]");
        for (int x : new int[]{2, 8}) {
            b.set(x, 2, 0, "minecraft:glass_pane");
            b.set(x, 3, 0, "minecraft:glass_pane");
            b.set(x, 2, 10, "minecraft:glass_pane");
            b.set(x, 3, 10, "minecraft:glass_pane");
        }
        for (int z : new int[]{3, 7}) {
            b.set(0, 2, z, "minecraft:glass_pane");
            b.set(10, 2, z, "minecraft:glass_pane");
        }
        for (int layer = 0; layer < 4; layer++) {
            for (int z = layer; z <= 10 - layer; z++) {
                b.set(layer, 5 + layer, z, "minecraft:spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
                b.set(10 - layer, 5 + layer, z, "minecraft:spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
            }
        }
        rectangle(b, 4, 7, 0, 6, 10, "minecraft:spruce_slab[type=top,waterlogged=false]");
        b.set(2, 1, 7, "minecraft:red_bed[facing=south,part=foot,occupied=false]");
        b.set(2, 1, 8, "minecraft:red_bed[facing=south,part=head,occupied=false]");
        b.set(8, 1, 8, "minecraft:crafting_table");
        b.set(8, 1, 7, "minecraft:furnace[facing=west,lit=false]");
        b.set(5, 4, 5, "minecraft:lantern[hanging=true,waterlogged=false]");
        return b.list();
    }

    private List<StructureBlock> farm() {
        Blueprint b = new Blueprint();
        rectangle(b, 0, 0, 0, 14, 14, "minecraft:mossy_cobblestone");
        for (int x = 1; x < 14; x++) {
            for (int z = 1; z < 10; z++) {
                if (x % 4 == 3) {
                    b.set(x, 1, z, "minecraft:water[level=0]");
                } else {
                    b.set(x, 1, z, "minecraft:farmland[moisture=7]");
                    b.set(x, 2, z, (x + z) % 3 == 0 ? "minecraft:carrots[age=7]" : "minecraft:wheat[age=7]");
                }
            }
        }
        perimeter(b, 0, 1, 0, 14, 14, "minecraft:oak_fence");
        opening(b, 7, 1, 0, 1);
        b.set(7, 1, 0, "minecraft:oak_fence_gate[facing=south,in_wall=false,open=false,powered=false]");
        rectangle(b, 2, 1, 11, 7, 14, "minecraft:oak_planks");
        for (int y = 2; y <= 4; y++) {
            perimeter(b, 2, y, 11, 7, 14, "minecraft:stripped_oak_log[axis=y]");
        }
        rectangle(b, 1, 5, 10, 8, 14, "minecraft:oak_slab[type=bottom,waterlogged=false]");
        b.set(3, 2, 12, "minecraft:composter[level=7]");
        b.set(5, 2, 12, "minecraft:barrel[facing=up,open=false]");
        for (int x : new int[]{1, 7, 13}) {
            b.set(x, 2, 10, "minecraft:oak_fence");
            b.set(x, 3, 10, "minecraft:lantern[hanging=false,waterlogged=false]");
        }
        return b.list();
    }

    private List<StructureBlock> warehouse() {
        Blueprint b = new Blueprint();
        industrialShell(b, 19, 9, 23, "minecraft:bricks", "minecraft:weathered_cut_copper");
        rectangle(b, 4, 1, 2, 14, 20, "minecraft:smooth_stone");
        for (int z = 3; z <= 19; z += 4) {
            for (int x : new int[]{2, 5, 13, 16}) {
                b.set(x, 1, z, chest(x < 9 ? "east" : "west"));
                b.set(x, 2, z, chest(x < 9 ? "east" : "west"));
            }
        }
        for (int z = 2; z <= 20; z += 3) {
            b.set(9, 7, z, "minecraft:chain[axis=y]");
            b.set(9, 6, z, "minecraft:lantern[hanging=true,waterlogged=false]");
        }
        for (int x = 3; x <= 15; x++) {
            b.set(x, 1, 21, x % 2 == 0 ? "minecraft:yellow_concrete" : "minecraft:black_concrete");
        }
        b.set(8, 1, 3, "minecraft:crafting_table");
        b.set(10, 1, 3, "minecraft:cartography_table");
        return b.list();
    }

    private List<StructureBlock> workshop() {
        Blueprint b = new Blueprint();
        industrialShell(b, 15, 8, 15, "minecraft:stone_bricks", "minecraft:exposed_cut_copper");
        for (int x = 3; x <= 11; x += 4) {
            b.set(x, 1, 5, "minecraft:smithing_table");
            b.set(x, 1, 7, "minecraft:anvil[facing=east]");
            b.set(x, 1, 9, "minecraft:blast_furnace[facing=north,lit=false]");
        }
        rectangle(b, 2, 1, 12, 12, 12, "minecraft:copper_grate[waterlogged=false]");
        b.set(7, 6, 7, "minecraft:redstone_lamp[lit=true]");
        b.set(7, 5, 7, "minecraft:chain[axis=y]");
        return b.list();
    }

    private List<StructureBlock> barracks() {
        Blueprint b = new Blueprint();
        industrialShell(b, 17, 8, 15, "minecraft:tuff_bricks", "minecraft:polished_tuff");
        for (int z = 3; z <= 11; z += 4) {
            bed(b, 3, 1, z, "east", "green");
            bed(b, 12, 1, z, "west", "green");
            b.set(6, 1, z, "minecraft:barrel[facing=up,open=false]");
            b.set(10, 1, z, "minecraft:barrel[facing=up,open=false]");
        }
        rectangle(b, 7, 1, 4, 9, 10, "minecraft:polished_andesite");
        b.set(8, 1, 11, "minecraft:fletching_table");
        b.set(8, 2, 12, "minecraft:target");
        return b.list();
    }

    private List<StructureBlock> factory(String kind) {
        int sizeZ = kind.equals("MISSILE") ? 29 : 25;
        int sizeY = kind.equals("MISSILE") ? 12 : 11;
        Blueprint b = new Blueprint();
        String wall = kind.equals("DRONE") ? "minecraft:light_gray_concrete" : kind.equals("MISSILE") ? "minecraft:deepslate_tiles" : "minecraft:polished_andesite";
        String roof = kind.equals("DRONE") ? "minecraft:oxidized_cut_copper" : "minecraft:dark_prismarine";
        industrialShell(b, 21, sizeY, sizeZ, wall, roof);
        for (int z = 4; z < sizeZ - 3; z += 5) {
            rectangle(b, 5, 1, z, 15, z + 1, "minecraft:yellow_concrete");
            for (int x = 4; x <= 16; x += 4) {
                machine(b, x, 1, z + 2, kind);
            }
        }
        for (int z = 4; z < sizeZ - 3; z += 6) {
            b.set(10, sizeY - 3, z, "minecraft:chain[axis=y]");
            b.set(10, sizeY - 4, z, "minecraft:redstone_lamp[lit=true]");
        }
        for (int z : new int[]{sizeZ - 6, sizeZ - 3}) {
            b.set(3, 1, z, "minecraft:blast_furnace[facing=east,lit=true]");
            b.set(17, 1, z, "minecraft:smoker[facing=west,lit=true]");
        }
        return b.list();
    }

    private List<StructureBlock> airDefence() {
        Blueprint b = new Blueprint();
        rectangle(b, 0, 0, 0, 20, 20, "minecraft:stone_bricks");
        perimeter(b, 0, 1, 0, 20, 20, "minecraft:iron_bars");
        opening(b, 9, 1, 0, 3);
        rectangle(b, 6, 1, 6, 14, 14, "minecraft:polished_andesite");
        circlePlatform(b, 10, 2, 10);
        column(b, 10, 3, 10, 3, "minecraft:lightning_rod[facing=up,waterlogged=false]");
        for (int[] point : new int[][]{{7, 7}, {13, 7}, {7, 13}, {13, 13}}) {
            column(b, point[0], 2, point[1], 3, "minecraft:iron_block");
            b.set(point[0], 5, point[1], "minecraft:observer[facing=up,powered=false]");
            b.set(point[0], 6, point[1], "minecraft:end_rod[facing=up]");
        }
        rectangle(b, 2, 1, 14, 6, 18, "minecraft:stone_bricks");
        for (int y = 2; y <= 5; y++) {
            perimeter(b, 2, y, 14, 6, 18, "minecraft:green_terracotta");
        }
        rectangle(b, 2, 6, 14, 6, 18, "minecraft:stone_slab[type=bottom,waterlogged=false]");
        b.set(4, 2, 16, "minecraft:cartography_table");
        b.set(4, 3, 16, "minecraft:lodestone");
        return b.list();
    }

    private void industrialShell(Blueprint b, int sizeX, int sizeY, int sizeZ, String wall, String roof) {
        rectangle(b, 0, 0, 0, sizeX - 1, sizeZ - 1, "minecraft:reinforced_deepslate");
        rectangle(b, 1, 1, 1, sizeX - 2, sizeZ - 2, "minecraft:smooth_stone");
        for (int y = 1; y < sizeY - 2; y++) {
            perimeter(b, 0, y, 0, sizeX - 1, sizeZ - 1, y == 1 ? "minecraft:stone_bricks" : wall);
        }
        for (int x = 0; x < sizeX; x += 4) {
            column(b, x, 1, 0, sizeY - 3, "minecraft:polished_basalt[axis=y]");
            column(b, x, 1, sizeZ - 1, sizeY - 3, "minecraft:polished_basalt[axis=y]");
        }
        for (int z = 0; z < sizeZ; z += 4) {
            column(b, 0, 1, z, sizeY - 3, "minecraft:polished_basalt[axis=y]");
            column(b, sizeX - 1, 1, z, sizeY - 3, "minecraft:polished_basalt[axis=y]");
        }
        for (int x = 2; x < sizeX - 2; x += 3) {
            b.set(x, 3, 0, "minecraft:light_blue_stained_glass_pane");
            b.set(x, 3, sizeZ - 1, "minecraft:light_blue_stained_glass_pane");
        }
        rectangle(b, 0, sizeY - 2, 0, sizeX - 1, sizeZ - 1, roof + "_slab[type=bottom,waterlogged=false]");
        opening(b, sizeX / 2 - 2, 1, 0, 4);
        for (int x = sizeX / 2 - 2; x <= sizeX / 2 + 2; x++) {
            b.set(x, 1, 0, "minecraft:smooth_stone");
        }
        for (int y = sizeY - 3; y < sizeY; y++) {
            b.set(2, y, sizeZ - 3, "minecraft:bricks");
            b.set(3, y, sizeZ - 3, "minecraft:bricks");
            b.set(2, y, sizeZ - 2, "minecraft:bricks");
            b.set(3, y, sizeZ - 2, "minecraft:bricks");
        }
    }

    private void machine(Blueprint b, int x, int y, int z, String kind) {
        String casing = kind.equals("DRONE") ? "minecraft:copper_block" : kind.equals("MISSILE") ? "minecraft:netherite_block" : "minecraft:iron_block";
        b.set(x, y, z, casing);
        b.set(x, y + 1, z, "minecraft:piston[facing=up,extended=false]");
        b.set(x, y + 2, z, "minecraft:observer[facing=south,powered=false]");
        b.set(x + 1, y, z, "minecraft:heavy_weighted_pressure_plate[power=0]");
        b.set(x - 1, y, z, "minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]");
    }

    private void circlePlatform(Blueprint b, int centerX, int y, int centerZ) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (x * x + z * z <= 18) {
                    b.set(centerX + x, y, centerZ + z, "minecraft:cut_copper");
                }
            }
        }
    }

    private void bed(Blueprint b, int x, int y, int z, String facing, String color) {
        int offsetX = facing.equals("east") ? 1 : facing.equals("west") ? -1 : 0;
        int offsetZ = facing.equals("south") ? 1 : facing.equals("north") ? -1 : 0;
        b.set(x, y, z, "minecraft:" + color + "_bed[facing=" + facing + ",occupied=false,part=foot]");
        b.set(x + offsetX, y, z + offsetZ, "minecraft:" + color + "_bed[facing=" + facing + ",occupied=false,part=head]");
    }

    private String chest(String facing) {
        return "minecraft:chest[facing=" + facing + ",type=single,waterlogged=false]";
    }

    private void rectangle(Blueprint b, int minimumX, int y, int minimumZ, int maximumX, int maximumZ, String data) {
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                b.set(x, y, z, data);
            }
        }
    }

    private void perimeter(Blueprint b, int minimumX, int y, int minimumZ, int maximumX, int maximumZ, String data) {
        for (int x = minimumX; x <= maximumX; x++) {
            b.set(x, y, minimumZ, data);
            b.set(x, y, maximumZ, data);
        }
        for (int z = minimumZ + 1; z < maximumZ; z++) {
            b.set(minimumX, y, z, data);
            b.set(maximumX, y, z, data);
        }
    }

    private void column(Blueprint b, int x, int minimumY, int z, int height, String data) {
        for (int y = minimumY; y < minimumY + height; y++) {
            b.set(x, y, z, data);
        }
    }

    private void opening(Blueprint b, int minimumX, int minimumY, int z, int width) {
        for (int x = minimumX; x < minimumX + width; x++) {
            for (int y = minimumY; y <= minimumY + 3; y++) {
                b.blocks.remove(x + ":" + y + ":" + z);
            }
        }
    }

    private void intTag(DataOutputStream output, String name, int value) throws IOException {
        output.writeByte(3);
        output.writeUTF(name);
        output.writeInt(value);
    }

    private void stringTag(DataOutputStream output, String name, String value) throws IOException {
        output.writeByte(8);
        output.writeUTF(name);
        output.writeUTF(value);
    }

    private void intList(DataOutputStream output, String name, int first, int second, int third) throws IOException {
        output.writeByte(9);
        output.writeUTF(name);
        output.writeByte(3);
        output.writeInt(3);
        output.writeInt(first);
        output.writeInt(second);
        output.writeInt(third);
    }
}
