package ua.bobster.defence;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import ua.bobster.defence.aa.AaItem;
import ua.bobster.defence.aa.AaListener;
import ua.bobster.defence.aa.AaManager;
import ua.bobster.defence.aa.AaRecipe;
import ua.bobster.defence.aa.TeamRegistry;
import ua.bobster.defence.airdefence.AirDefenceListener;
import ua.bobster.defence.ballistic.BallisticItem;
import ua.bobster.defence.ballistic.BallisticListener;
import ua.bobster.defence.ballistic.BallisticManager;
import ua.bobster.defence.ballistic.BallisticRecipe;
import ua.bobster.defence.chest.ChestProtectionListener;
import ua.bobster.defence.command.BallisticCommand;
import ua.bobster.defence.command.CoalitionCommand;
import ua.bobster.defence.command.DroneCommand;
import ua.bobster.defence.command.PpoCommand;
import ua.bobster.defence.drone.DroneItem;
import ua.bobster.defence.drone.DroneListener;
import ua.bobster.defence.drone.DroneManager;
import ua.bobster.defence.drone.DroneRecipe;
import ua.bobster.defence.raid.AirRaidService;
import ua.bobster.defence.stats.StatsManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

/**
 * П'ять незалежних систем в одному jar:
 * 1) Chest Protection — скрині не руйнуються вибухами;
 * 2) Air Defence — стрілою можна збити TNT, дрон або ракету;
 * 3) FPV Drone — крафтові дрони-камікадзе з видом від першої особи;
 * 4) Ballistic — артилерія з розвідником і наведенням по карті;
 * 5) Auto Air Defence — стаціонарна ППО, плюс повітряна тривога по територіях.
 * <p>
 * Кожну можна вимкнути окремо в config.yml.
 */
public class BobsterDefence extends JavaPlugin {

    private NamespacedKey interceptedKey;
    /** Спільна мітка «чий це снаряд» — на ній тримається система «свій/чужий» для ППО. */
    private NamespacedKey ownerKey;
    private StatsManager statsManager;
    private ChestProtectionListener chestProtectionListener;
    private AirDefenceListener airDefenceListener;
    private DroneItem droneItem;
    private DroneRecipe droneRecipe;
    private DroneManager droneManager;
    private BallisticItem ballisticItem;
    private BallisticRecipe ballisticRecipe;
    private BallisticManager ballisticManager;
    private TeamRegistry teamRegistry;
    private AaItem aaItem;
    private AaRecipe aaRecipe;
    private AaManager aaManager;
    private AirRaidService airRaidService;
    /** Ключі, про відсутність яких уже сказали в консоль — щоб не засмічувати лог. */
    private final Set<String> reportedMissing = new HashSet<>();

    @Override
    public void onEnable() {
        interceptedKey = new NamespacedKey(this, "intercepted");
        ownerKey = new NamespacedKey(this, "owner");

        saveDefaultConfig();
        applyBundledDefaults();

        statsManager = new StatsManager(this);
        if (getConfig().getBoolean("stats.enabled", true)) {
            statsManager.load();
            statsManager.startAutosave(getConfig().getInt("stats.autosave-seconds", 300));
        }

        droneItem = new DroneItem(this);
        droneManager = new DroneManager(this, droneItem);
        droneRecipe = new DroneRecipe(this, droneManager, droneItem);
        droneRecipe.register();
        droneManager.start();

        ballisticItem = new BallisticItem(this);
        ballisticManager = new BallisticManager(this, ballisticItem);
        ballisticRecipe = new BallisticRecipe(this, ballisticManager, ballisticItem);
        ballisticRecipe.register();
        ballisticManager.start();
        // Сервер міг впасти з ракетою в повітрі — її корпус лишився б висіти в світі назавжди.
        ballisticManager.removeStrayMissiles();
        BallisticListener ballisticListener = new BallisticListener(this, ballisticManager, ballisticItem);

        airRaidService = new AirRaidService(this);
        airRaidService.start();

        teamRegistry = new TeamRegistry(this);
        aaItem = new AaItem(this);
        aaManager = new AaManager(this, aaItem, teamRegistry);
        aaRecipe = new AaRecipe(this, aaManager, aaItem);
        aaRecipe.register();
        aaManager.start();

        chestProtectionListener = new ChestProtectionListener(this);
        airDefenceListener = new AirDefenceListener(this);
        getServer().getPluginManager().registerEvents(chestProtectionListener, this);
        getServer().getPluginManager().registerEvents(airDefenceListener, this);
        getServer().getPluginManager().registerEvents(
                new DroneListener(this, droneManager, droneItem), this);
        getServer().getPluginManager().registerEvents(ballisticListener, this);
        getServer().getPluginManager().registerEvents(new AaListener(this, aaManager, aaItem), this);

        registerCommand("ppo", new PpoCommand(this));
        registerCommand("drone", new DroneCommand(this));
        registerCommand("ballistic", new BallisticCommand(this, ballisticManager, ballisticListener));
        registerCommand("coalition", new CoalitionCommand(this, teamRegistry));

        logStartup();
    }

    private <T extends org.bukkit.command.CommandExecutor & org.bukkit.command.TabCompleter>
    void registerCommand(String name, T handler) {
        org.bukkit.command.PluginCommand command = Objects.requireNonNull(
                getCommand(name), "команда '" + name + "' відсутня в plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    @Override
    public void onDisable() {
        if (airRaidService != null) {
            airRaidService.shutdown();
        }
        if (aaManager != null) {
            aaManager.shutdown();
        }
        if (aaRecipe != null) {
            aaRecipe.unregister();
        }
        if (ballisticManager != null) {
            ballisticManager.shutdown();
        }
        if (ballisticRecipe != null) {
            ballisticRecipe.unregister();
        }
        if (droneManager != null) {
            droneManager.shutdown();
        }
        if (droneRecipe != null) {
            droneRecipe.unregister();
        }
        if (statsManager != null) {
            statsManager.stopAutosave();
            if (getConfig().getBoolean("stats.enabled", true)) {
                statsManager.saveIfDirty(true);
            }
        }
        getLogger().info("Bobster Defence вимкнено.");
    }

    /**
     * Перечитує config.yml і оновлює кеші всіх систем.
     * Активні польоти при цьому завершуються — інакше вони летіли б зі старими лімітами.
     */
    public void reloadAll() {
        reloadConfig();
        // reloadConfig() скидає підкладені defaults — без цього після релоаду знову
        // «зникли б» усі ключі, яких немає у файлі користувача.
        applyBundledDefaults();
        reportedMissing.clear();
        chestProtectionListener.reload();
        airDefenceListener.reload();
        droneManager.shutdown();
        droneManager.reload();
        droneManager.start();
        droneRecipe.register();
        ballisticManager.shutdown();
        ballisticManager.reload();
        ballisticManager.start();
        // Сервер міг впасти з ракетою в повітрі — її корпус лишився б висіти в світі назавжди.
        ballisticManager.removeStrayMissiles();
        ballisticRecipe.register();
        airRaidService.reload();
        teamRegistry.reload();
        aaManager.shutdown();
        aaManager.reload();
        aaManager.start();
        aaRecipe.register();
        statsManager.stopAutosave();
        if (getConfig().getBoolean("stats.enabled", true)) {
            statsManager.startAutosave(getConfig().getInt("stats.autosave-seconds", 300));
        }
    }

    private void logStartup() {
        String version = getPluginMeta().getVersion();
        getLogger().info("Bobster Defence v" + version + " enabled");
        getLogger().info("Chest Protection: " + state("chest-protection.enabled"));
        getLogger().info("Air Defence: " + state("air-defence.enabled"));
        getLogger().info("FPV Drone: " + state("fpv-drone.enabled"));
        getLogger().info("Ballistic: " + state("ballistic.enabled"));
        getLogger().info("Auto Air Defence: " + state("air-defence-system.enabled"));
        getLogger().info("Air Raid: " + (airRaidService.isEnabled()
                ? "ENABLED (" + airRaidService.territories().all().size() + " територій)"
                : airRaidService.territories().available() ? "DISABLED" : "територій не знайдено"));
        getLogger().info("Stats: " + state("stats.enabled"));
    }

    private String state(String path) {
        return getConfig().getBoolean(path, true) ? "ENABLED" : "DISABLED";
    }

    public NamespacedKey interceptedKey() {
        return interceptedKey;
    }

    public NamespacedKey ownerKey() {
        return ownerKey;
    }

    public AaManager aa() {
        return aaManager;
    }

    public AirRaidService airRaid() {
        return airRaidService;
    }

    public TeamRegistry teams() {
        return teamRegistry;
    }

    public StatsManager stats() {
        return statsManager;
    }

    public DroneManager drones() {
        return droneManager;
    }

    public DroneItem droneItem() {
        return droneItem;
    }

    public BallisticManager ballistic() {
        return ballisticManager;
    }

    /**
     * Підкладає config.yml із jar як значення за замовчуванням.
     * <p>
     * Без цього старий конфіг гравця, створений попередньою версією, не отримує нових ключів:
     * {@code saveDefaultConfig()} пише файл лише коли його немає. Раніше через це кожне нове
     * повідомлення поверталося порожнім рядком, і команди мовчки нічого не робили.
     * <p>
     * Файл користувача навмисно не переписуємо — інакше загубилися б усі коментарі.
     */
    private void applyBundledDefaults() {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                return;
            }
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            getConfig().setDefaults(bundled);
            getConfig().options().copyDefaults(false);

            int missing = 0;
            for (String key : bundled.getKeys(true)) {
                if (!getConfig().isSet(key) && !bundled.isConfigurationSection(key)) {
                    missing++;
                }
            }
            if (missing > 0) {
                getLogger().info("У config.yml бракує " + missing
                        + " ключів — узято значення за замовчуванням. "
                        + "Щоб бачити їх у файлі з коментарями, перейменуй config.yml і перезапусти сервер.");
            }
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Не вдалося прочитати вбудований config.yml", ex);
        }
    }

    /**
     * Текст повідомлення з config.yml.
     * <p>
     * Якщо ключа немає ніде — повертаємо помітну заглушку, а не порожній рядок. Порожній
     * рядок усі відправники мовчки пропускають, і зовні це виглядає так, ніби команда
     * взагалі не працює. Краще побачити «[ballistic-usage]» у чаті, ніж нічого.
     */
    public String message(String key) {
        String path = "messages." + key;
        String raw = getConfig().getString(path, "");
        if (!raw.isEmpty()) {
            return raw;
        }
        // Порожній рядок — це свідоме рішення адміна вимкнути повідомлення, його поважаємо.
        if (getConfig().isSet(path)) {
            return "";
        }
        if (reportedMissing.add(key)) {
            getLogger().warning("У config.yml немає повідомлення 'messages." + key
                    + "' — показано заглушку.");
        }
        return "<red>[" + key + "]";
    }

    public String prefix() {
        return getConfig().getString("messages.prefix", "");
    }
}
