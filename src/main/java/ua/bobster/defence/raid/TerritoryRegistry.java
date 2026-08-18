package ua.bobster.defence.raid;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ua.bobster.defence.BobsterDefence;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Читає території, намальовані плагіном TerritoryMap.
 * <p>
 * Навмисно працюємо з його <code>territories.yml</code>, а не з API: тоді BobsterDefence
 * не треба перезбирати щоразу, коли змінюється TerritoryMap, і плагіни лишаються
 * повністю незалежними. Файл перечитується, щойно змінилася дата модифікації,
 * тож нова територія підхоплюється без рестарту.
 */
public class TerritoryRegistry {

    private final BobsterDefence plugin;

    private File file;
    private long lastModified;
    private long lastCheck;
    private int reloadMillis;
    private List<TerritoryArea> territories = List.of();

    public TerritoryRegistry(BobsterDefence plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String path = plugin.getConfig().getString("air-raid.territory-file", "");
        this.reloadMillis = Math.max(1, plugin.getConfig().getInt("air-raid.reload-seconds", 30)) * 1000;
        this.file = path.isBlank()
                ? new File(plugin.getDataFolder().getParentFile(), "TerritoryMap/territories.yml")
                : new File(path);
        this.lastModified = 0L;
        this.lastCheck = 0L;
        load();
    }

    public boolean available() {
        return file != null && file.isFile();
    }

    public File file() {
        return file;
    }

    public List<TerritoryArea> all() {
        refreshIfStale();
        return territories;
    }

    /** @return територія, всередині якої лежить точка, або null */
    public TerritoryArea at(String world, double x, double z) {
        for (TerritoryArea area : all()) {
            if (area.contains(world, x, z)) {
                return area;
            }
        }
        return null;
    }

    /** @return усі території, до межі яких від точки не далі за radius */
    public List<TerritoryArea> near(String world, double x, double z, double radius) {
        List<TerritoryArea> found = new ArrayList<>();
        for (TerritoryArea area : all()) {
            if (area.withinRadius(world, x, z, radius)) {
                found.add(area);
            }
        }
        return found;
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < reloadMillis) {
            return;
        }
        lastCheck = now;
        if (file != null && file.isFile() && file.lastModified() != lastModified) {
            load();
        }
    }

    private void load() {
        lastCheck = System.currentTimeMillis();
        if (file == null || !file.isFile()) {
            territories = List.of();
            return;
        }
        lastModified = file.lastModified();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("territories");
        if (root == null) {
            territories = List.of();
            return;
        }

        List<TerritoryArea> parsed = new ArrayList<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            List<TerritoryPolygon> parts = parseParts(section);
            if (parts.isEmpty()) {
                continue;
            }
            parsed.add(new TerritoryArea(
                    key,
                    section.getString("name", key),
                    section.getString("owner-name", "?"),
                    parseUuid(section.getString("owner")),
                    section.getString("world", "world"),
                    parts));
        }
        territories = List.copyOf(parsed);
        plugin.getLogger().info("Air Raid: територій завантажено — " + territories.size());
    }

    private java.util.UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private List<TerritoryPolygon> parseParts(ConfigurationSection section) {
        List<TerritoryPolygon> parts = new ArrayList<>();
        List<?> rawParts = section.getList("parts");
        if (rawParts == null) {
            return parts;
        }
        for (Object rawPart : rawParts) {
            if (!(rawPart instanceof Map<?, ?> part)) {
                continue;
            }
            double[][] outer = parseRing(part.get("outer"));
            if (outer == null) {
                continue;
            }
            List<double[][]> holes = new ArrayList<>();
            if (part.get("holes") instanceof List<?> rawHoles) {
                for (Object rawHole : rawHoles) {
                    double[][] hole = parseRing(rawHole);
                    if (hole != null) {
                        holes.add(hole);
                    }
                }
            }
            parts.add(new TerritoryPolygon(outer[0], outer[1], holes));
        }
        return parts;
    }

    /** @return {xs, zs} або null, якщо кільце некоректне */
    private double[][] parseRing(Object raw) {
        if (!(raw instanceof List<?> points) || points.size() < 3) {
            return null;
        }
        double[] xs = new double[points.size()];
        double[] zs = new double[points.size()];
        int index = 0;
        for (Object rawPoint : points) {
            if (!(rawPoint instanceof Map<?, ?> point)) {
                return null;
            }
            Object x = point.get("x");
            Object z = point.get("z");
            if (!(x instanceof Number nx) || !(z instanceof Number nz)) {
                return null;
            }
            xs[index] = nx.doubleValue();
            zs[index] = nz.doubleValue();
            index++;
        }
        return new double[][]{xs, zs};
    }
}
