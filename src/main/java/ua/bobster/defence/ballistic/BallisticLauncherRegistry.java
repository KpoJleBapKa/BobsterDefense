package ua.bobster.defence.ballistic;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ua.bobster.defence.BobsterDefence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class BallisticLauncherRegistry {

    public record Entry(UUID id, UUID owner, UUID worldId, int x, int y, int z, int number) {

        public String locationText() {
            World world = Bukkit.getWorld(worldId);
            return (world == null ? worldId.toString() : world.getName()) + " " + x + " " + y + " " + z;
        }

        public Block block(boolean loadChunk) {
            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                return null;
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4) && !loadChunk) {
                return null;
            }
            if (loadChunk) {
                world.getChunkAt(x >> 4, z >> 4);
            }
            return world.getBlockAt(x, y, z);
        }
    }

    private final BobsterDefence plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<String, UUID> byLocation = new HashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> selections = new HashMap<>();
    private final Set<String> ignoredLocations = new LinkedHashSet<>();

    public BallisticLauncherRegistry(BobsterDefence plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ballistic-launchers.yml");
    }

    public void load() {
        entries.clear();
        byLocation.clear();
        selections.clear();
        ignoredLocations.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection launcherRoot = yaml.getConfigurationSection("launchers");
        if (launcherRoot != null) {
            for (String rawId : launcherRoot.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(rawId);
                    String path = "launchers." + rawId;
                    Entry entry = new Entry(id, UUID.fromString(yaml.getString(path + ".owner")), UUID.fromString(yaml.getString(path + ".world")), yaml.getInt(path + ".x"), yaml.getInt(path + ".y"), yaml.getInt(path + ".z"), yaml.getInt(path + ".number"));
                    entries.put(id, entry);
                    byLocation.put(key(entry.worldId(), entry.x(), entry.y(), entry.z()), id);
                } catch (IllegalArgumentException | NullPointerException ex) {
                    plugin.getLogger().warning("Некоректний запис балістичної установки " + rawId);
                }
            }
        }
        ConfigurationSection selectionRoot = yaml.getConfigurationSection("selections");
        if (selectionRoot != null) {
            for (String rawPlayer : selectionRoot.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(rawPlayer);
                    LinkedHashSet<UUID> selected = new LinkedHashSet<>();
                    for (String rawId : yaml.getStringList("selections." + rawPlayer)) {
                        UUID id = UUID.fromString(rawId);
                        if (entries.containsKey(id)) {
                            selected.add(id);
                        }
                    }
                    if (!selected.isEmpty()) {
                        selections.put(playerId, selected);
                    }
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Некоректний вибір балістичних установок " + rawPlayer);
                }
            }
        }
        ignoredLocations.addAll(yaml.getStringList("ignored-locations"));
    }

    public Entry register(Block block, UUID owner) {
        ignoredLocations.remove(key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        return registerInternal(block, owner);
    }

    public Entry registerMigrated(Block block, UUID owner) {
        String locationKey = key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        if (ignoredLocations.contains(locationKey)) {
            return null;
        }
        return registerInternal(block, owner);
    }

    private Entry registerInternal(Block block, UUID owner) {
        String locationKey = key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        UUID existingId = byLocation.get(locationKey);
        if (existingId != null) {
            Entry existing = entries.get(existingId);
            if (existing != null && existing.owner().equals(owner)) {
                return existing;
            }
            remove(existingId);
        }
        int number = nextNumber(owner);
        Entry entry = new Entry(UUID.randomUUID(), owner, block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), number);
        entries.put(entry.id(), entry);
        byLocation.put(locationKey, entry.id());
        save();
        return entry;
    }

    public int clear(UUID owner) {
        List<UUID> removed = entries.values().stream().filter(entry -> entry.owner().equals(owner)).map(Entry::id).toList();
        for (UUID id : removed) {
            Entry entry = entries.get(id);
            if (entry != null) {
                ignoredLocations.add(key(entry.worldId(), entry.x(), entry.y(), entry.z()));
            }
            remove(id);
        }
        selections.remove(owner);
        save();
        return removed.size();
    }

    public void unregister(Block block) {
        UUID id = byLocation.get(key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        if (id != null) {
            remove(id);
            save();
        }
    }

    public Entry at(Block block) {
        UUID id = byLocation.get(key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        return id == null ? null : entries.get(id);
    }

    public List<Entry> owned(UUID owner) {
        return entries.values().stream().filter(entry -> entry.owner().equals(owner)).sorted(Comparator.comparingInt(Entry::number)).toList();
    }

    public Entry owned(UUID owner, int number) {
        return entries.values().stream().filter(entry -> entry.owner().equals(owner) && entry.number() == number).findFirst().orElse(null);
    }

    public void select(UUID playerId, List<Entry> selected) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Entry entry : selected) {
            if (entry.owner().equals(playerId)) {
                ids.add(entry.id());
            }
        }
        if (ids.isEmpty()) {
            selections.remove(playerId);
        } else {
            selections.put(playerId, ids);
        }
        save();
    }

    public List<Entry> selected(UUID playerId) {
        Set<UUID> ids = selections.get(playerId);
        if (ids == null) {
            return List.of();
        }
        List<Entry> result = new ArrayList<>();
        for (UUID id : ids) {
            Entry entry = entries.get(id);
            if (entry != null && entry.owner().equals(playerId)) {
                result.add(entry);
            }
        }
        result.sort(Comparator.comparingInt(Entry::number));
        return result;
    }

    private void remove(UUID id) {
        Entry entry = entries.remove(id);
        if (entry == null) {
            return;
        }
        byLocation.remove(key(entry.worldId(), entry.x(), entry.y(), entry.z()));
        for (LinkedHashSet<UUID> selected : selections.values()) {
            selected.remove(id);
        }
        selections.values().removeIf(Set::isEmpty);
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Entry entry : entries.values()) {
            String path = "launchers." + entry.id();
            yaml.set(path + ".owner", entry.owner().toString());
            yaml.set(path + ".world", entry.worldId().toString());
            yaml.set(path + ".x", entry.x());
            yaml.set(path + ".y", entry.y());
            yaml.set(path + ".z", entry.z());
            yaml.set(path + ".number", entry.number());
        }
        for (Map.Entry<UUID, LinkedHashSet<UUID>> selected : selections.entrySet()) {
            yaml.set("selections." + selected.getKey(), selected.getValue().stream().map(UUID::toString).toList());
        }
        yaml.set("ignored-locations", new ArrayList<>(ignoredLocations));
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти ballistic-launchers.yml", ex);
        }
    }

    private String key(UUID world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private int nextNumber(UUID owner) {
        Set<Integer> used = new java.util.HashSet<>();
        for (Entry entry : entries.values()) {
            if (entry.owner().equals(owner)) {
                used.add(entry.number());
            }
        }
        int number = 1;
        while (used.contains(number)) {
            number++;
        }
        return number;
    }
}
