package ua.bobster.defence.aa;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ua.bobster.defence.BobsterDefence;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Коаліції: хто з ким воює на одному боці.
 * <p>
 * Потрібні для двох речей — щоб ППО не збивала снаряди своїх і щоб над своєю ж територією
 * не вмикалася повітряна тривога від власної ракети.
 * <p>
 * Склад коаліцій гравці змінюють самі командою {@code /coalition}, дані лежать у
 * {@code teams.yml} і зберігаються за UUID, тож зміна ніка нічого не ламає. Секція
 * {@code teams:} у config.yml лишається як статичний список для адміна й читається додатково.
 */
public class TeamRegistry {

    /** Одна коаліція. */
    public record Coalition(String key, String name, UUID owner, Set<UUID> members) {

        public boolean isOwner(UUID uuid) {
            return owner != null && owner.equals(uuid);
        }
    }

    private final BobsterDefence plugin;
    private final File file;

    /** Динамічні коаліції з teams.yml, ключ — назва в нижньому регістрі. */
    private final Map<String, Coalition> coalitions = new HashMap<>();
    /** Швидкий пошук: гравець → ключ коаліції. */
    private final Map<UUID, String> byMember = new HashMap<>();
    /** Статичні сторони з config.yml, за ніком. */
    private final Map<String, String> staticByName = new HashMap<>();

    public TeamRegistry(BobsterDefence plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
        reload();
    }

    public void reload() {
        loadStatic();
        load();
    }

    private void loadStatic() {
        staticByName.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("teams");
        if (section == null) {
            return;
        }
        for (String team : section.getKeys(false)) {
            for (String member : section.getStringList(team)) {
                staticByName.put(member.toLowerCase(Locale.ROOT), team.toLowerCase(Locale.ROOT));
            }
        }
    }

    private void load() {
        coalitions.clear();
        byMember.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("coalitions");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Set<UUID> members = new LinkedHashSet<>();
            for (String raw : section.getStringList("members")) {
                UUID uuid = parse(raw);
                if (uuid != null) {
                    members.add(uuid);
                }
            }
            Coalition coalition = new Coalition(key.toLowerCase(Locale.ROOT),
                    section.getString("name", key), parse(section.getString("owner")), members);
            coalitions.put(coalition.key(), coalition);
            for (UUID member : members) {
                byMember.put(member, coalition.key());
            }
        }
        plugin.getLogger().info("Coalitions: " + coalitions.size());
    }

    private UUID parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Coalition coalition : coalitions.values()) {
            String path = "coalitions." + coalition.key();
            yaml.set(path + ".name", coalition.name());
            if (coalition.owner() != null) {
                yaml.set(path + ".owner", coalition.owner().toString());
            }
            List<String> members = new ArrayList<>();
            for (UUID member : coalition.members()) {
                members.add(member.toString());
            }
            yaml.set(path + ".members", members);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Не вдалося створити теку для teams.yml");
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти teams.yml", ex);
        }
    }

    // ─────────────────────────────── запити ───────────────────────────────

    public Coalition of(UUID player) {
        String key = byMember.get(player);
        return key == null ? null : coalitions.get(key);
    }

    public Coalition byName(String name) {
        return name == null ? null : coalitions.get(name.toLowerCase(Locale.ROOT));
    }

    public List<Coalition> all() {
        return new ArrayList<>(coalitions.values());
    }

    /** @return назва сторони або null */
    public String teamOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Coalition coalition = of(uuid);
        if (coalition != null) {
            return coalition.key();
        }
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
        String name = offline.getName();
        return name == null ? null : staticByName.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Свої — це той самий гравець або двоє в одній коаліції.
     */
    public boolean friendly(UUID a, UUID b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String first = teamOf(a);
        return first != null && first.equals(teamOf(b));
    }

    // ─────────────────────────────── зміни ───────────────────────────────

    public Coalition create(String name, UUID owner) {
        Coalition coalition = new Coalition(name.toLowerCase(Locale.ROOT), name, owner,
                new LinkedHashSet<>(List.of(owner)));
        coalitions.put(coalition.key(), coalition);
        byMember.put(owner, coalition.key());
        save();
        return coalition;
    }

    public boolean add(Coalition coalition, UUID player) {
        if (!coalition.members().add(player)) {
            return false;
        }
        byMember.put(player, coalition.key());
        save();
        return true;
    }

    public boolean remove(Coalition coalition, UUID player) {
        if (!coalition.members().remove(player)) {
            return false;
        }
        byMember.remove(player);
        save();
        return true;
    }

    public void disband(Coalition coalition) {
        coalitions.remove(coalition.key());
        for (UUID member : coalition.members()) {
            byMember.remove(member);
        }
        save();
    }
}
