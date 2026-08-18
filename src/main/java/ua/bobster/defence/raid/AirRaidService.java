package ua.bobster.defence.raid;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Повітряна тривога по територіях.
 * <p>
 * Тривога прив'язана не до координат, а до території з TerritoryMap: летить ракета в Solaria —
 * тривога оголошується саме для Solaria. Вимикається сама, коли задану кількість хвилин
 * над територією нічого не літало.
 */
public class AirRaidService {

    /** Активна тривога над однією територією. */
    private static final class Alert {
        final String name;
        final String owner;
        String threat;
        long lastActivity;

        Alert(String name, String owner, String threat, long lastActivity) {
            this.name = name;
            this.owner = owner;
            this.threat = threat;
            this.lastActivity = lastActivity;
        }
    }

    private final BobsterDefence plugin;
    private final TerritoryRegistry territories;
    private final DiscordBridge discord;
    private boolean skipFriendly;

    private final Map<String, Alert> active = new HashMap<>();
    /** Коли по території востаннє «стукали» дроном — щоб не спамити перевірками щотік. */
    private final Map<String, Long> droneThrottle = new HashMap<>();

    private BukkitTask tickTask;

    private boolean enabled;
    private long allClearMillis;
    private double droneRadius;
    private double missileRadius;
    private boolean sirenSound;
    private long droneThrottleMillis;

    public AirRaidService(BobsterDefence plugin) {
        this.plugin = plugin;
        this.territories = new TerritoryRegistry(plugin);
        this.discord = new DiscordBridge(plugin);
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.enabled = config.getBoolean("air-raid.enabled", true);
        this.allClearMillis = Math.max(10, config.getInt("air-raid.all-clear-minutes", 5)) * 60_000L;
        this.droneRadius = Math.max(0, config.getDouble("air-raid.drone-radius", 100));
        this.missileRadius = Math.max(0, config.getDouble("air-raid.missile-radius", 0));
        this.sirenSound = config.getBoolean("air-raid.siren-sound", true);
        this.droneThrottleMillis = Math.max(1, config.getInt("air-raid.drone-check-seconds", 2)) * 1000L;
        this.skipFriendly = config.getBoolean("air-raid.skip-friendly", true);
        territories.reload();
        discord.reload();
    }

    public void start() {
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        active.clear();
        droneThrottle.clear();
    }

    public boolean isEnabled() {
        return enabled && territories.available();
    }

    public TerritoryRegistry territories() {
        return territories;
    }

    public List<String> activeTerritories() {
        List<String> names = new ArrayList<>();
        for (Alert alert : active.values()) {
            names.add(alert.name);
        }
        return names;
    }

    // ─────────────────────────── тригери ───────────────────────────

    /**
     * Пуск балістичної ракети. Тривога оголошується території, у якій лежить точка удару
     * (і сусіднім, якщо в конфізі заданий запас).
     *
     * @return true, якщо тривогу оголошено хоч комусь
     */
    public boolean onMissile(Location impact, UUID shooter) {
        if (!isEnabled()) {
            return false;
        }
        String world = impact.getWorld().getName();
        List<TerritoryArea> hit = missileRadius > 0
                ? territories.near(world, impact.getX(), impact.getZ(), missileRadius)
                : single(territories.at(world, impact.getX(), impact.getZ()));

        boolean announced = false;
        for (TerritoryArea area : hit) {
            if (friendly(area, shooter)) {
                announced = true; // своя ракета над своєю землею — тривоги немає, але й
                continue;         // старе попередження з координатами теж зайве
            }
            trigger(area, plugin.message("air-raid-threat-missile"));
            announced = true;
        }
        return announced;
    }

    /** FPV-дрон у повітрі: тривога для всіх територій, до межі яких не далі за drone-radius. */
    public void onDrone(Location location, UUID pilot) {
        if (!isEnabled() || droneRadius <= 0) {
            return;
        }
        String world = location.getWorld().getName();
        for (TerritoryArea area : territories.near(world, location.getX(), location.getZ(), droneRadius)) {
            if (friendly(area, pilot)) {
                continue; // свій дрон над своєю територією тривоги не піднімає
            }
            Long last = droneThrottle.get(area.key());
            long now = System.currentTimeMillis();
            if (last != null && now - last < droneThrottleMillis) {
                // Уже рахували цю територію нещодавно — досить оновити час активності.
                Alert alert = active.get(area.key());
                if (alert != null) {
                    alert.lastActivity = now;
                }
                continue;
            }
            droneThrottle.put(area.key(), now);
            trigger(area, plugin.message("air-raid-threat-drone"));
        }
    }

    /**
     * Чи належить снаряд «своїм» для цієї території: сам власник або його коаліція.
     * <p>
     * Без цієї перевірки власна ракета вмикала б тривогу над власною ж базою — саме це
     * й було першим, що впало в очі на тестуванні.
     */
    private boolean friendly(TerritoryArea area, UUID shooter) {
        if (!skipFriendly || shooter == null || area.ownerUuid() == null) {
            return false;
        }
        return plugin.teams().friendly(area.ownerUuid(), shooter);
    }

    private List<TerritoryArea> single(TerritoryArea area) {
        return area == null ? List.of() : List.of(area);
    }

    private void trigger(TerritoryArea area, String threat) {
        long now = System.currentTimeMillis();
        Alert existing = active.get(area.key());
        if (existing != null) {
            existing.lastActivity = now;
            existing.threat = threat;
            return; // тривога вже йде — просто продовжуємо її
        }
        Alert alert = new Alert(area.name(), area.owner(), threat, now);
        active.put(area.key(), alert);
        announce(alert, true);
    }

    // ─────────────────────────── відбій ───────────────────────────

    private void tick() {
        if (active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Alert> finished = new ArrayList<>();
        active.values().removeIf(alert -> {
            if (now - alert.lastActivity < allClearMillis) {
                return false;
            }
            finished.add(alert);
            return true;
        });
        for (Alert alert : finished) {
            announce(alert, false);
        }
    }

    // ─────────────────────────── оголошення ───────────────────────────

    private void announce(Alert alert, boolean started) {
        Map<String, Object> placeholders = Map.of(
                "territory", alert.name,
                "owner", alert.owner,
                "threat", alert.threat,
                "minutes", allClearMillis / 60_000L);

        String chatKey = started ? "air-raid-start" : "air-raid-end";
        String raw = plugin.message(chatKey);
        if (!raw.isEmpty()) {
            Component message = MessageUtil.parse(raw, placeholders);
            plugin.getServer().broadcast(message);
        }
        if (started && sirenSound) {
            playSiren();
        }

        String discordKey = started ? "air-raid-discord-start" : "air-raid-discord-end";
        String discordRaw = plugin.message(discordKey);
        if (!discordRaw.isEmpty()) {
            discord.send(MessageUtil.plain(MessageUtil.parse(discordRaw, placeholders)));
        }

        plugin.getServer().getPluginManager().callEvent(
                new AirRaidEvent(alert.name, alert.owner, started ? alert.threat : "", started));
    }

    private void playSiren() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 0.8f);
        }
    }
}
