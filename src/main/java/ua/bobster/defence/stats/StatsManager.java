package ua.bobster.defence.stats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Статистика перехоплень. Тримається в пам'яті, на диск лягає в plugins/BobsterDefence/stats.yml.
 * <p>
 * Запис/читання мапи — з основного потоку, збереження файлу — асинхронно.
 */
public class StatsManager {

    private final BobsterDefence plugin;
    private final File file;

    private final Map<UUID, Integer> kills = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private BukkitTask autosaveTask;

    public StatsManager(BobsterDefence plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    public void load() {
        kills.clear();
        names.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("stats.yml: пропущено некоректний UUID '" + key + "'");
                continue;
            }
            int amount = players.getInt(key + ".kills", 0);
            if (amount > 0) {
                kills.put(uuid, amount);
            }
            String name = players.getString(key + ".name");
            if (name != null && !name.isEmpty()) {
                names.put(uuid, name);
            }
        }
        plugin.getLogger().info("Stats: завантажено записів — " + kills.size());
    }

    public void startAutosave(int seconds) {
        stopAutosave();
        if (seconds <= 0) {
            return;
        }
        long ticks = seconds * 20L;
        autosaveTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, () -> saveIfDirty(false), ticks, ticks);
    }

    public void stopAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
    }

    /**
     * @return нове значення лічильника гравця
     */
    public int increment(UUID uuid, String name) {
        int updated = kills.merge(uuid, 1, Integer::sum);
        if (name != null) {
            names.put(uuid, name);
        }
        dirty.set(true);
        return updated;
    }

    public int get(UUID uuid) {
        return kills.getOrDefault(uuid, 0);
    }

    public String nameOf(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString().substring(0, 8));
    }

    public int total() {
        int sum = 0;
        for (int value : kills.values()) {
            sum += value;
        }
        return sum;
    }

    public List<Entry> top(int limit) {
        List<Entry> list = new ArrayList<>(kills.size());
        for (Map.Entry<UUID, Integer> entry : kills.entrySet()) {
            list.add(new Entry(entry.getKey(), nameOf(entry.getKey()), entry.getValue()));
        }
        list.sort(Comparator.comparingInt(Entry::kills).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    public void saveIfDirty(boolean force) {
        if (!force && !dirty.getAndSet(false)) {
            return;
        }
        dirty.set(false);
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : kills.entrySet()) {
            String path = "players." + entry.getKey();
            yaml.set(path + ".kills", entry.getValue());
            String name = names.get(entry.getKey());
            if (name != null) {
                yaml.set(path + ".name", name);
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Не вдалося створити теку для stats.yml");
            }
            yaml.save(file);
        } catch (IOException ex) {
            dirty.set(true);
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти stats.yml", ex);
        }
    }

    public record Entry(UUID uuid, String name, int kills) {
    }
}
