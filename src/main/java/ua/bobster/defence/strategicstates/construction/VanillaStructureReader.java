package ua.bobster.defence.strategicstates.construction;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class VanillaStructureReader {

    public StructureTemplatePlan read(File file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (input.readUnsignedByte() != 10) {
                throw new IOException("NBT root не є compound");
            }
            readString(input);
            Map<String, Object> root = readCompound(input);
            List<?> size = list(root.get("size"));
            List<?> palette = list(root.get("palette"));
            List<?> rawBlocks = list(root.get("blocks"));
            if (size.size() != 3 || palette.isEmpty()) {
                throw new IOException("Structure не має size або palette");
            }
            List<String> blockData = palette(palette);
            List<StructureBlockPlacement> blocks = new ArrayList<>();
            for (Object raw : rawBlocks) {
                if (!(raw instanceof Map<?, ?> block)) {
                    continue;
                }
                List<?> position = list(block.get("pos"));
                int state = number(block.get("state")).intValue();
                if (position.size() != 3 || state < 0 || state >= blockData.size()) {
                    continue;
                }
                String data = blockData.get(state);
                if (!data.equals("minecraft:air") && !data.equals("minecraft:structure_void")) {
                    blocks.add(new StructureBlockPlacement(number(position.get(0)).intValue(), number(position.get(1)).intValue(), number(position.get(2)).intValue(), data));
                }
            }
            blocks.sort(Comparator.comparingInt(StructureBlockPlacement::y).thenComparingInt(StructureBlockPlacement::x).thenComparingInt(StructureBlockPlacement::z));
            return new StructureTemplatePlan(file.getName(), number(size.get(0)).intValue(), number(size.get(1)).intValue(), number(size.get(2)).intValue(), blocks);
        }
    }

    private List<String> palette(List<?> palette) throws IOException {
        List<String> result = new ArrayList<>();
        for (Object raw : palette) {
            if (!(raw instanceof Map<?, ?> entry) || !(entry.get("Name") instanceof String name)) {
                throw new IOException("Некоректний palette entry");
            }
            StringBuilder data = new StringBuilder(name);
            if (entry.get("Properties") instanceof Map<?, ?> properties && !properties.isEmpty()) {
                data.append('[');
                boolean first = true;
                for (Map.Entry<?, ?> property : properties.entrySet()) {
                    if (!first) {
                        data.append(',');
                    }
                    data.append(property.getKey()).append('=').append(property.getValue());
                    first = false;
                }
                data.append(']');
            }
            result.add(data.toString());
        }
        return result;
    }

    private Map<String, Object> readCompound(DataInputStream input) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        while (true) {
            int type = input.readUnsignedByte();
            if (type == 0) {
                return result;
            }
            result.put(readString(input), readPayload(input, type));
        }
    }

    private Object readPayload(DataInputStream input, int type) throws IOException {
        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> input.readNBytes(length(input));
            case 8 -> readString(input);
            case 9 -> readList(input);
            case 10 -> readCompound(input);
            case 11 -> readInts(input);
            case 12 -> readLongs(input);
            default -> throw new IOException("Невідомий NBT tag: " + type);
        };
    }

    private List<Object> readList(DataInputStream input) throws IOException {
        int type = input.readUnsignedByte();
        int length = length(input);
        List<Object> result = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            result.add(readPayload(input, type));
        }
        return result;
    }

    private int[] readInts(DataInputStream input) throws IOException {
        int[] result = new int[length(input)];
        for (int index = 0; index < result.length; index++) {
            result[index] = input.readInt();
        }
        return result;
    }

    private long[] readLongs(DataInputStream input) throws IOException {
        long[] result = new long[length(input)];
        for (int index = 0; index < result.length; index++) {
            result[index] = input.readLong();
        }
        return result;
    }

    private String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private int length(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 10_000_000) {
            throw new IOException("Некоректна довжина NBT: " + length);
        }
        return length;
    }

    private List<?> list(Object value) throws IOException {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IOException("Очікувався NBT list");
    }

    private Number number(Object value) throws IOException {
        if (value instanceof Number number) {
            return number;
        }
        throw new IOException("Очікувалося число NBT");
    }
}
