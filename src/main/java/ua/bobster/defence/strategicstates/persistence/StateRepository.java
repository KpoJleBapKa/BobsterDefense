package ua.bobster.defence.strategicstates.persistence;

import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.model.StateColor;
import ua.bobster.defence.strategicstates.economy.ResourceType;
import ua.bobster.defence.strategicstates.economy.StateEconomy;
import ua.bobster.defence.strategicstates.combat.StateThreat;
import ua.bobster.defence.strategicstates.combat.StrategicWeaponType;
import ua.bobster.defence.strategicstates.combat.StrategicTargetCache;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.construction.BuildingStatus;
import ua.bobster.defence.strategicstates.construction.BuildingType;
import ua.bobster.defence.strategicstates.construction.StateBuilding;
import ua.bobster.defence.strategicstates.model.StatePersonality;
import ua.bobster.defence.strategicstates.model.StatePosition;
import ua.bobster.defence.strategicstates.model.StateStatus;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.population.CitizenMode;
import ua.bobster.defence.strategicstates.population.CitizenProfession;
import ua.bobster.defence.strategicstates.population.StateCitizen;
import ua.bobster.defence.strategicstates.capture.CaptureSector;
import ua.bobster.defence.strategicstates.capture.SectorStatus;
import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarStatus;
import ua.bobster.defence.strategicstates.army.ArmyMode;
import ua.bobster.defence.strategicstates.army.StateArmy;
import ua.bobster.defence.strategicstates.arsenal.ArsenalItem;
import ua.bobster.defence.strategicstates.arsenal.StateArsenal;
import ua.bobster.defence.strategicstates.diplomacy.DiplomaticStatus;
import ua.bobster.defence.strategicstates.diplomacy.StateRelation;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class StateRepository {

    private final BobsterDefence plugin;
    private final File databaseFile;
    private final Object lock = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BobsterDefence-StateDB");
        thread.setDaemon(true);
        return thread;
    });

    private Connection connection;

    public StateRepository(BobsterDefence plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "states/states.db");
    }

    public void initialize() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("SQLite JDBC driver відсутній", ex);
        }
        File parent = databaseFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Не вдалося створити " + parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        }
        migrate();
    }

    private void migrate() throws SQLException {
        synchronized (lock) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            }
            int version = schemaVersion();
            if (version == 0) {
                createVersionOne();
                setSchemaVersion(1);
                version = 1;
            }
            if (version == 1) {
                createVersionTwo();
                setSchemaVersion(2);
                version = 2;
            }
            if (version == 2) {
                createVersionThree();
                setSchemaVersion(3);
                version = 3;
            }
            if (version == 3) {
                createVersionFour();
                setSchemaVersion(4);
                version = 4;
            }
            if (version == 4) {
                createVersionFive();
                setSchemaVersion(5);
                version = 5;
            }
            if (version == 5) {
                createVersionSix();
                setSchemaVersion(6);
                version = 6;
            }
            if (version == 6) {
                createVersionSeven();
                setSchemaVersion(7);
                version = 7;
            }
            if (version == 7) {
                createVersionEight();
                setSchemaVersion(8);
                version = 8;
            }
            if (version == 8) {
                createVersionNine();
                setSchemaVersion(9);
                version = 9;
            }
            if (version == 9) {
                createVersionTen();
                setSchemaVersion(10);
                version = 10;
            }
            if (version == 10) {
                createVersionEleven();
                setSchemaVersion(11);
                version = 11;
            }
            if (version == 11) {
                createVersionTwelve();
                setSchemaVersion(12);
                version = 12;
            }
            if (version > 12) {
                throw new SQLException("Непідтримувана версія states.db: " + version);
            }
        }
    }

    private void setSchemaVersion(int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM schema_version");
        }
        try (PreparedStatement query = connection.prepareStatement("INSERT INTO schema_version(version) VALUES(?)")) {
            query.setInt(1, version);
            query.executeUpdate();
        }
    }

    private int schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void createVersionOne() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS states (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL UNIQUE,
                        short_name TEXT NOT NULL,
                        color TEXT NOT NULL UNIQUE,
                        status TEXT NOT NULL,
                        world TEXT NOT NULL,
                        capital_x REAL NOT NULL,
                        capital_y REAL NOT NULL,
                        capital_z REAL NOT NULL,
                        core_x INTEGER NOT NULL,
                        core_y INTEGER NOT NULL,
                        core_z INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        development_level INTEGER NOT NULL,
                        aggression INTEGER NOT NULL,
                        economy_focus INTEGER NOT NULL,
                        technology_focus INTEGER NOT NULL,
                        defence_focus INTEGER NOT NULL,
                        expansion_focus INTEGER NOT NULL,
                        population_focus INTEGER NOT NULL,
                        territory_id TEXT NOT NULL UNIQUE,
                        last_simulation INTEGER NOT NULL,
                        UNIQUE(world, core_x, core_y, core_z)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS citizens (
                        id TEXT PRIMARY KEY,
                        state_id TEXT NOT NULL,
                        profession TEXT NOT NULL,
                        age INTEGER NOT NULL,
                        combat_experience REAL NOT NULL,
                        health REAL NOT NULL,
                        home_building TEXT,
                        work_building TEXT,
                        current_task TEXT,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        mode TEXT NOT NULL,
                        entity_id TEXT,
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_citizens_state ON citizens(state_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_citizens_location ON citizens(world, x, z)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS protected_owners (owner_uuid TEXT PRIMARY KEY)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS resources (state_id TEXT NOT NULL, resource TEXT NOT NULL, amount REAL NOT NULL, PRIMARY KEY(state_id, resource), FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS buildings (id TEXT PRIMARY KEY, state_id TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL, data TEXT NOT NULL, FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS wars (id TEXT PRIMARY KEY, attacker_id TEXT NOT NULL, target_id TEXT NOT NULL, status TEXT NOT NULL, data TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS capture_sectors (id TEXT PRIMARY KEY, territory_id TEXT NOT NULL, controller_id TEXT, progress REAL NOT NULL, data TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS state_arsenal (state_id TEXT NOT NULL, weapon TEXT NOT NULL, amount INTEGER NOT NULL, PRIMARY KEY(state_id, weapon), FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS state_relations (first_id TEXT NOT NULL, second_id TEXT NOT NULL, relation TEXT NOT NULL, value REAL NOT NULL, PRIMARY KEY(first_id, second_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS state_events (id INTEGER PRIMARY KEY AUTOINCREMENT, state_id TEXT, created_at INTEGER NOT NULL, type TEXT NOT NULL, data TEXT NOT NULL)");
        }
    }

    private void createVersionTwo() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_economy (
                        state_id TEXT PRIMARY KEY,
                        housing_capacity INTEGER NOT NULL,
                        stability REAL NOT NULL,
                        population_progress REAL NOT NULL,
                        last_economy_tick INTEGER NOT NULL,
                        last_population_tick INTEGER NOT NULL,
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private void createVersionThree() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_threats (
                        state_id TEXT NOT NULL,
                        attacker_type TEXT NOT NULL,
                        attacker_id TEXT NOT NULL,
                        threat REAL NOT NULL,
                        last_attack INTEGER NOT NULL,
                        ballistic_hits INTEGER NOT NULL,
                        drone_hits INTEGER NOT NULL,
                        PRIMARY KEY(state_id, attacker_type, attacker_id),
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private void createVersionFour() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_buildings (
                        id TEXT PRIMARY KEY,
                        state_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        template TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        size_x INTEGER NOT NULL,
                        size_y INTEGER NOT NULL,
                        size_z INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        progress REAL NOT NULL,
                        last_progress INTEGER NOT NULL,
                        effects_applied INTEGER NOT NULL,
                        integrity REAL NOT NULL,
                        blocked_reason TEXT,
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_state_buildings_state ON state_buildings(state_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_state_buildings_location ON state_buildings(world, x, z)");
        }
    }

    private void createVersionFive() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(state_buildings)")) {
            while (columns.next()) {
                if (columns.getString("name").equals("materialized_blocks")) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE state_buildings ADD COLUMN materialized_blocks INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void createVersionSix() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_capture_sectors (
                        id TEXT PRIMARY KEY,
                        territory_id TEXT NOT NULL,
                        original_owner_id TEXT,
                        original_owner_kind TEXT NOT NULL,
                        controller_id TEXT,
                        attacker_state_id TEXT,
                        world TEXT NOT NULL,
                        grid_x INTEGER NOT NULL,
                        grid_z INTEGER NOT NULL,
                        control_x REAL NOT NULL,
                        control_z REAL NOT NULL,
                        progress REAL NOT NULL,
                        status TEXT NOT NULL,
                        last_presence INTEGER NOT NULL,
                        UNIQUE(territory_id, grid_x, grid_z)
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_capture_sectors_territory ON state_capture_sectors(territory_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_capture_sectors_controller ON state_capture_sectors(controller_id)");
        }
    }

    private void createVersionSeven() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_wars (
                        id TEXT PRIMARY KEY,
                        attacker_state_id TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        territory_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER NOT NULL,
                        end_reason TEXT,
                        FOREIGN KEY(attacker_state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_state_wars_status ON state_wars(status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_state_wars_territory ON state_wars(territory_id)");
        }
    }

    private void createVersionEight() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_armies (
                        state_id TEXT PRIMARY KEY,
                        mode TEXT NOT NULL,
                        war_id TEXT,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        destination_world TEXT,
                        destination_x REAL,
                        destination_y REAL,
                        destination_z REAL,
                        strength INTEGER NOT NULL,
                        last_update INTEGER NOT NULL,
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private void createVersionNine() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_arsenal_items (
                        state_id TEXT NOT NULL,
                        item TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        progress REAL NOT NULL,
                        last_tick INTEGER NOT NULL,
                        PRIMARY KEY(state_id, item),
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private void createVersionTen() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS strategic_target_cache (
                        territory_id TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        score INTEGER NOT NULL,
                        scanned_at INTEGER NOT NULL
                    )
                    """);
        }
    }

    private void createVersionEleven() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS state_relations (
                        state_id TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        score REAL NOT NULL,
                        status TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(state_id, target_type, target_id),
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    private void createVersionTwelve() throws SQLException {
        if (hasColumn("state_relations", "state_id")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE state_relations RENAME TO state_relations_legacy");
            statement.executeUpdate("""
                    CREATE TABLE state_relations (
                        state_id TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        score REAL NOT NULL,
                        status TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(state_id, target_type, target_id),
                        FOREIGN KEY(state_id) REFERENCES states(id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    INSERT OR IGNORE INTO state_relations(state_id, target_type, target_id, score, status, updated_at)
                    SELECT legacy.first_id, 'STRATEGIC_STATE', legacy.second_id, legacy.value,
                        CASE WHEN relation IN ('ALLIED', 'FRIENDLY', 'NEUTRAL', 'HOSTILE', 'WAR') THEN relation ELSE 'NEUTRAL' END,
                        CAST(strftime('%s', 'now') AS INTEGER) * 1000
                    FROM state_relations_legacy legacy
                    INNER JOIN states state ON state.id = legacy.first_id
                    """);
            statement.executeUpdate("DROP TABLE state_relations_legacy");
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<StateRelation> loadRelations() throws SQLException {
        synchronized (lock) {
            List<StateRelation> relations = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT * FROM state_relations")) {
                while (result.next()) {
                    CombatPrincipal target = new CombatPrincipal(UUID.fromString(result.getString("target_id")), CombatPrincipalType.valueOf(result.getString("target_type")));
                    relations.add(new StateRelation(UUID.fromString(result.getString("state_id")), target, result.getDouble("score"), DiplomaticStatus.valueOf(result.getString("status")), result.getLong("updated_at")));
                }
            }
            return relations;
        }
    }

    public Map<UUID, StrategicTargetCache> loadStrategicTargets() throws SQLException {
        synchronized (lock) {
            Map<UUID, StrategicTargetCache> targets = new HashMap<>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT * FROM strategic_target_cache")) {
                while (result.next()) {
                    UUID territoryId = UUID.fromString(result.getString("territory_id"));
                    targets.put(territoryId, new StrategicTargetCache(territoryId, result.getString("world"), result.getDouble("x"), result.getDouble("y"), result.getDouble("z"), result.getInt("score"), result.getLong("scanned_at")));
                }
            }
            return targets;
        }
    }

    public List<StrategicState> loadStates() throws SQLException {
        synchronized (lock) {
            List<StrategicState> states = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM states WHERE status <> 'DESTROYED'")) {
                while (result.next()) {
                    StatePosition capital = new StatePosition(result.getString("world"), result.getDouble("capital_x"), result.getDouble("capital_y"), result.getDouble("capital_z"));
                    StatePosition core = new StatePosition(result.getString("world"), result.getInt("core_x"), result.getInt("core_y"), result.getInt("core_z"));
                    StatePersonality personality = new StatePersonality(result.getInt("aggression"), result.getInt("economy_focus"), result.getInt("technology_focus"), result.getInt("defence_focus"), result.getInt("expansion_focus"), result.getInt("population_focus"));
                    states.add(new StrategicState(UUID.fromString(result.getString("id")), result.getString("name"), result.getString("short_name"), StateColor.valueOf(result.getString("color")), capital, core, result.getLong("created_at"), personality, UUID.fromString(result.getString("territory_id")), StateStatus.valueOf(result.getString("status")), result.getInt("development_level"), result.getLong("last_simulation")));
                }
            }
            return states;
        }
    }

    public List<StateCitizen> loadCitizens() throws SQLException {
        synchronized (lock) {
            List<StateCitizen> citizens = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM citizens WHERE mode <> 'DEAD'")) {
                while (result.next()) {
                    StatePosition position = new StatePosition(result.getString("world"), result.getDouble("x"), result.getDouble("y"), result.getDouble("z"));
                    citizens.add(new StateCitizen(UUID.fromString(result.getString("id")), UUID.fromString(result.getString("state_id")), CitizenProfession.valueOf(result.getString("profession")), result.getInt("age"), result.getDouble("combat_experience"), result.getDouble("health"), result.getString("home_building"), result.getString("work_building"), result.getString("current_task"), position, CitizenMode.VIRTUAL, null));
                }
            }
            return citizens;
        }
    }

    public Map<UUID, StateEconomy> loadEconomies() throws SQLException {
        synchronized (lock) {
            Map<UUID, Map<ResourceType, Double>> resources = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT state_id, resource, amount FROM resources")) {
                while (rows.next()) {
                    try {
                        UUID stateId = UUID.fromString(rows.getString("state_id"));
                        ResourceType type = ResourceType.valueOf(rows.getString("resource"));
                        resources.computeIfAbsent(stateId, ignored -> new EnumMap<>(ResourceType.class)).put(type, rows.getDouble("amount"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            Map<UUID, StateEconomy> result = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM state_economy")) {
                while (rows.next()) {
                    UUID stateId = UUID.fromString(rows.getString("state_id"));
                    result.put(stateId, new StateEconomy(stateId, resources.getOrDefault(stateId, Map.of()),
                            rows.getInt("housing_capacity"), rows.getDouble("stability"),
                            rows.getDouble("population_progress"), rows.getLong("last_economy_tick"),
                            rows.getLong("last_population_tick")));
                }
            }
            return result;
        }
    }

    public List<StateThreat> loadThreats() throws SQLException {
        synchronized (lock) {
            List<StateThreat> threats = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM state_threats")) {
                while (rows.next()) {
                    try {
                        UUID stateId = UUID.fromString(rows.getString("state_id"));
                        CombatPrincipal attacker = new CombatPrincipal(UUID.fromString(rows.getString("attacker_id")), CombatPrincipalType.valueOf(rows.getString("attacker_type")));
                        threats.add(new StateThreat(stateId, attacker, rows.getDouble("threat"), rows.getLong("last_attack"), rows.getInt("ballistic_hits"), rows.getInt("drone_hits")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return threats;
        }
    }

    public List<StateBuilding> loadBuildings() throws SQLException {
        synchronized (lock) {
            List<StateBuilding> buildings = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM state_buildings WHERE status <> 'DESTROYED'")) {
                while (rows.next()) {
                    try {
                        StatePosition origin = new StatePosition(rows.getString("world"), rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
                        buildings.add(new StateBuilding(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("state_id")), BuildingType.valueOf(rows.getString("type")), rows.getString("template"), origin, rows.getInt("size_x"), rows.getInt("size_y"), rows.getInt("size_z"), rows.getLong("created_at"), BuildingStatus.valueOf(rows.getString("status")), rows.getDouble("progress"), rows.getInt("materialized_blocks"), rows.getLong("last_progress"), rows.getBoolean("effects_applied"), rows.getDouble("integrity"), rows.getString("blocked_reason")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return buildings;
        }
    }

    public List<CaptureSector> loadCaptureSectors() throws SQLException {
        synchronized (lock) {
            List<CaptureSector> sectors = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM state_capture_sectors")) {
                while (rows.next()) {
                    try {
                        sectors.add(new CaptureSector(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("territory_id")), uuid(rows.getString("original_owner_id")), TerritoryOwnerKind.valueOf(rows.getString("original_owner_kind")), rows.getString("world"), rows.getInt("grid_x"), rows.getInt("grid_z"), rows.getDouble("control_x"), rows.getDouble("control_z"), uuid(rows.getString("controller_id")), uuid(rows.getString("attacker_state_id")), rows.getDouble("progress"), SectorStatus.valueOf(rows.getString("status")), rows.getLong("last_presence")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return sectors;
        }
    }

    public List<StateWar> loadWars() throws SQLException {
        synchronized (lock) {
            List<StateWar> wars = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM state_wars WHERE status <> 'ENDED'")) {
                while (rows.next()) {
                    try {
                        CombatPrincipal target = new CombatPrincipal(UUID.fromString(rows.getString("target_id")), CombatPrincipalType.valueOf(rows.getString("target_type")));
                        wars.add(new StateWar(UUID.fromString(rows.getString("id")), UUID.fromString(rows.getString("attacker_state_id")), target, UUID.fromString(rows.getString("territory_id")), rows.getLong("started_at"), WarStatus.valueOf(rows.getString("status")), rows.getLong("ended_at"), rows.getString("end_reason")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return wars;
        }
    }

    public List<StateArmy> loadArmies() throws SQLException {
        synchronized (lock) {
            List<StateArmy> armies = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM state_armies")) {
                while (rows.next()) {
                    try {
                        StatePosition position = new StatePosition(rows.getString("world"), rows.getDouble("x"), rows.getDouble("y"), rows.getDouble("z"));
                        String destinationWorld = rows.getString("destination_world");
                        StatePosition destination = destinationWorld == null ? null : new StatePosition(destinationWorld, rows.getDouble("destination_x"), rows.getDouble("destination_y"), rows.getDouble("destination_z"));
                        armies.add(new StateArmy(UUID.fromString(rows.getString("state_id")), ArmyMode.valueOf(rows.getString("mode")), uuid(rows.getString("war_id")), position, destination, rows.getInt("strength"), rows.getLong("last_update")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return armies;
        }
    }

    public Map<UUID, StateArsenal> loadArsenals(long now) throws SQLException {
        synchronized (lock) {
            Map<UUID, StateArsenal> arsenals = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT * FROM state_arsenal_items")) {
                while (rows.next()) {
                    try {
                        UUID stateId = UUID.fromString(rows.getString("state_id"));
                        ArsenalItem item = ArsenalItem.valueOf(rows.getString("item"));
                        StateArsenal arsenal = arsenals.computeIfAbsent(stateId, StateArsenal::new);
                        arsenal.amount(item, rows.getInt("amount"));
                        arsenal.progress(item, rows.getDouble("progress"));
                        arsenal.lastTick(item, now);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return arsenals;
        }
    }

    public void insertState(StrategicState state, List<StateCitizen> citizens, StateEconomy economy) throws SQLException {
        synchronized (lock) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertState(state);
                for (StateCitizen citizen : citizens) {
                    upsertCitizen(citizen);
                }
                upsertEconomy(economy);
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public void saveEconomyAsync(StateEconomy economy) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertEconomy(economy);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти економіку " + economy.stateId(), ex);
                }
            }
        });
    }

    public void saveImpactAsync(StateThreat threat, StrategicWeaponType weapon, double power, boolean groundImpact, long now) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertThreat(threat);
                    try (PreparedStatement query = connection.prepareStatement("INSERT INTO state_events(state_id, created_at, type, data) VALUES(?, ?, ?, ?)")) {
                        query.setString(1, threat.stateId().toString());
                        query.setLong(2, now);
                        query.setString(3, "STRATEGIC_IMPACT");
                        query.setString(4, "weapon=" + weapon.name() + ";attackerType=" + threat.attacker().type().name() + ";attacker=" + threat.attacker().id() + ";power=" + power + ";ground=" + groundImpact);
                        query.executeUpdate();
                    }
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти стратегічний удар по " + threat.stateId(), ex);
                }
            }
        });
    }

    public void saveBuildingAsync(StateBuilding building) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertBuilding(building);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти будівлю " + building.id(), ex);
                }
            }
        });
    }

    public void insertBuilding(StateBuilding building) throws SQLException {
        synchronized (lock) {
            upsertBuilding(building);
        }
    }

    public void insertBuilding(StateBuilding building, StateEconomy economy) throws SQLException {
        synchronized (lock) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertBuilding(building);
                upsertEconomy(economy);
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public void insertCaptureSector(CaptureSector sector) throws SQLException {
        synchronized (lock) {
            upsertCaptureSector(sector);
        }
    }

    public void deleteCaptureSector(UUID sectorId) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement query = connection.prepareStatement("DELETE FROM state_capture_sectors WHERE id=?")) {
                query.setString(1, sectorId.toString());
                query.executeUpdate();
            }
        }
    }

    public void insertWar(StateWar war) throws SQLException {
        synchronized (lock) {
            upsertWar(war);
        }
    }

    public void saveWarAsync(StateWar war) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertWar(war);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти війну " + war.id(), ex);
                }
            }
        });
    }

    public void saveCaptureSectorAsync(CaptureSector sector) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertCaptureSector(sector);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти сектор " + sector.id(), ex);
                }
            }
        });
    }

    public void saveArmyAsync(StateArmy army) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertArmy(army);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти армію " + army.stateId(), ex);
                }
            }
        });
    }

    public void saveArsenalAsync(StateArsenal arsenal, ArsenalItem item) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertArsenal(arsenal, item);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти арсенал " + arsenal.stateId(), ex);
                }
            }
        });
    }

    public void saveStrategicTargetAsync(StrategicTargetCache target) {
        writer.execute(() -> {
            synchronized (lock) {
                try (PreparedStatement query = connection.prepareStatement("""
                        INSERT INTO strategic_target_cache(territory_id, world, x, y, z, score, scanned_at)
                        VALUES(?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(territory_id) DO UPDATE SET world=excluded.world, x=excluded.x,
                            y=excluded.y, z=excluded.z, score=excluded.score, scanned_at=excluded.scanned_at
                        """)) {
                    query.setString(1, target.territoryId().toString());
                    query.setString(2, target.world());
                    query.setDouble(3, target.x());
                    query.setDouble(4, target.y());
                    query.setDouble(5, target.z());
                    query.setInt(6, target.score());
                    query.setLong(7, target.scannedAt());
                    query.executeUpdate();
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти стратегічну ціль " + target.territoryId(), ex);
                }
            }
        });
    }

    public void saveRelationAsync(StateRelation relation) {
        writer.execute(() -> {
            synchronized (lock) {
                try (PreparedStatement query = connection.prepareStatement("""
                        INSERT INTO state_relations(state_id, target_type, target_id, score, status, updated_at)
                        VALUES(?, ?, ?, ?, ?, ?)
                        ON CONFLICT(state_id, target_type, target_id) DO UPDATE SET score=excluded.score,
                            status=excluded.status, updated_at=excluded.updated_at
                        """)) {
                    query.setString(1, relation.stateId().toString());
                    query.setString(2, relation.target().type().name());
                    query.setString(3, relation.target().id().toString());
                    query.setDouble(4, relation.score());
                    query.setString(5, relation.status().name());
                    query.setLong(6, relation.updatedAt());
                    query.executeUpdate();
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти дипломатичні відносини " + relation.stateId(), ex);
                }
            }
        });
    }

    public void saveProductionStart(StateArsenal arsenal, ArsenalItem item, StateEconomy economy) throws SQLException {
        synchronized (lock) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertArsenal(arsenal, item);
                upsertEconomy(economy);
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public void saveStateAsync(StrategicState state) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertState(state);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти державу " + state.name(), ex);
                }
            }
        });
    }

    public void saveCitizenAsync(StateCitizen citizen) {
        writer.execute(() -> {
            synchronized (lock) {
                try {
                    upsertCitizen(citizen);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.SEVERE, "Не вдалося зберегти Citizen " + citizen.id(), ex);
                }
            }
        });
    }

    private void upsertState(StrategicState state) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO states(id, name, short_name, color, status, world, capital_x, capital_y, capital_z,
                    core_x, core_y, core_z, created_at, development_level, aggression, economy_focus,
                    technology_focus, defence_focus, expansion_focus, population_focus, territory_id, last_simulation)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status, development_level=excluded.development_level,
                    last_simulation=excluded.last_simulation
                """)) {
            int index = 1;
            query.setString(index++, state.id().toString());
            query.setString(index++, state.name());
            query.setString(index++, state.shortName());
            query.setString(index++, state.color().name());
            query.setString(index++, state.status().name());
            query.setString(index++, state.core().world());
            query.setDouble(index++, state.capital().x());
            query.setDouble(index++, state.capital().y());
            query.setDouble(index++, state.capital().z());
            query.setInt(index++, (int) state.core().x());
            query.setInt(index++, (int) state.core().y());
            query.setInt(index++, (int) state.core().z());
            query.setLong(index++, state.createdAt());
            query.setInt(index++, state.developmentLevel());
            query.setInt(index++, state.personality().aggression());
            query.setInt(index++, state.personality().economyFocus());
            query.setInt(index++, state.personality().technologyFocus());
            query.setInt(index++, state.personality().defenceFocus());
            query.setInt(index++, state.personality().expansionFocus());
            query.setInt(index++, state.personality().populationFocus());
            query.setString(index++, state.territoryId().toString());
            query.setLong(index, state.lastSimulation());
            query.executeUpdate();
        }
    }

    private void upsertCitizen(StateCitizen citizen) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO citizens(id, state_id, profession, age, combat_experience, health, home_building,
                    work_building, current_task, world, x, y, z, mode, entity_id)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET profession=excluded.profession, age=excluded.age,
                    combat_experience=excluded.combat_experience, health=excluded.health,
                    home_building=excluded.home_building, work_building=excluded.work_building,
                    current_task=excluded.current_task, world=excluded.world, x=excluded.x, y=excluded.y,
                    z=excluded.z, mode=excluded.mode, entity_id=excluded.entity_id
                """)) {
            int index = 1;
            query.setString(index++, citizen.id().toString());
            query.setString(index++, citizen.stateId().toString());
            query.setString(index++, citizen.profession().name());
            query.setInt(index++, citizen.age());
            query.setDouble(index++, citizen.combatExperience());
            query.setDouble(index++, citizen.health());
            query.setString(index++, citizen.homeBuilding());
            query.setString(index++, citizen.workBuilding());
            query.setString(index++, citizen.currentTask());
            query.setString(index++, citizen.position().world());
            query.setDouble(index++, citizen.position().x());
            query.setDouble(index++, citizen.position().y());
            query.setDouble(index++, citizen.position().z());
            query.setString(index++, citizen.mode().name());
            query.setString(index, citizen.entityId() == null ? null : citizen.entityId().toString());
            query.executeUpdate();
        }
    }

    private void upsertEconomy(StateEconomy economy) throws SQLException {
        boolean manageTransaction = connection.getAutoCommit();
        if (manageTransaction) {
            connection.setAutoCommit(false);
        }
        try {
            try (PreparedStatement query = connection.prepareStatement("""
                    INSERT INTO state_economy(state_id, housing_capacity, stability, population_progress,
                        last_economy_tick, last_population_tick)
                    VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(state_id) DO UPDATE SET housing_capacity=excluded.housing_capacity,
                        stability=excluded.stability, population_progress=excluded.population_progress,
                        last_economy_tick=excluded.last_economy_tick,
                        last_population_tick=excluded.last_population_tick
                    """)) {
                query.setString(1, economy.stateId().toString());
                query.setInt(2, economy.housingCapacity());
                query.setDouble(3, economy.stability());
                query.setDouble(4, economy.populationProgress());
                query.setLong(5, economy.lastEconomyTick());
                query.setLong(6, economy.lastPopulationTick());
                query.executeUpdate();
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    INSERT INTO resources(state_id, resource, amount) VALUES(?, ?, ?)
                    ON CONFLICT(state_id, resource) DO UPDATE SET amount=excluded.amount
                    """)) {
                for (Map.Entry<ResourceType, Double> entry : economy.resources().entrySet()) {
                    query.setString(1, economy.stateId().toString());
                    query.setString(2, entry.getKey().name());
                    query.setDouble(3, entry.getValue());
                    query.addBatch();
                }
                query.executeBatch();
            }
            if (manageTransaction) {
                connection.commit();
            }
        } catch (SQLException ex) {
            if (manageTransaction) {
                connection.rollback();
            }
            throw ex;
        } finally {
            if (manageTransaction) {
                connection.setAutoCommit(true);
            }
        }
    }

    private void upsertThreat(StateThreat threat) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO state_threats(state_id, attacker_type, attacker_id, threat, last_attack, ballistic_hits, drone_hits)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(state_id, attacker_type, attacker_id) DO UPDATE SET threat=excluded.threat,
                    last_attack=excluded.last_attack, ballistic_hits=excluded.ballistic_hits,
                    drone_hits=excluded.drone_hits
                """)) {
            query.setString(1, threat.stateId().toString());
            query.setString(2, threat.attacker().type().name());
            query.setString(3, threat.attacker().id().toString());
            query.setDouble(4, threat.threat());
            query.setLong(5, threat.lastAttack());
            query.setInt(6, threat.ballisticHits());
            query.setInt(7, threat.droneHits());
            query.executeUpdate();
        }
    }

    private void upsertBuilding(StateBuilding building) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO state_buildings(id, state_id, type, template, world, x, y, z, size_x, size_y,
                    size_z, created_at, status, progress, materialized_blocks, last_progress, effects_applied, integrity, blocked_reason)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                    status=excluded.status, progress=excluded.progress,
                    materialized_blocks=excluded.materialized_blocks, last_progress=excluded.last_progress, effects_applied=excluded.effects_applied,
                    integrity=excluded.integrity, blocked_reason=excluded.blocked_reason
                """)) {
            int index = 1;
            query.setString(index++, building.id().toString());
            query.setString(index++, building.stateId().toString());
            query.setString(index++, building.type().name());
            query.setString(index++, building.template());
            query.setString(index++, building.origin().world());
            query.setInt(index++, (int) building.origin().x());
            query.setInt(index++, (int) building.origin().y());
            query.setInt(index++, (int) building.origin().z());
            query.setInt(index++, building.sizeX());
            query.setInt(index++, building.sizeY());
            query.setInt(index++, building.sizeZ());
            query.setLong(index++, building.createdAt());
            query.setString(index++, building.status().name());
            query.setDouble(index++, building.progress());
            query.setInt(index++, building.materializedBlocks());
            query.setLong(index++, building.lastProgress());
            query.setBoolean(index++, building.effectsApplied());
            query.setDouble(index++, building.integrity());
            query.setString(index, building.blockedReason());
            query.executeUpdate();
        }
    }

    private void upsertCaptureSector(CaptureSector sector) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO state_capture_sectors(id, territory_id, original_owner_id, original_owner_kind,
                    controller_id, attacker_state_id, world, grid_x, grid_z, control_x, control_z, progress,
                    status, last_presence)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET controller_id=excluded.controller_id,
                    attacker_state_id=excluded.attacker_state_id, progress=excluded.progress,
                    status=excluded.status, last_presence=excluded.last_presence
                """)) {
            query.setString(1, sector.id().toString());
            query.setString(2, sector.territoryId().toString());
            query.setString(3, text(sector.originalOwnerId()));
            query.setString(4, sector.originalOwnerKind().name());
            query.setString(5, text(sector.controllerId()));
            query.setString(6, text(sector.attackerStateId()));
            query.setString(7, sector.world());
            query.setInt(8, sector.gridX());
            query.setInt(9, sector.gridZ());
            query.setDouble(10, sector.controlX());
            query.setDouble(11, sector.controlZ());
            query.setDouble(12, sector.progress());
            query.setString(13, sector.status().name());
            query.setLong(14, sector.lastPresence());
            query.executeUpdate();
        }
    }

    private void upsertWar(StateWar war) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO state_wars(id, attacker_state_id, target_type, target_id, territory_id, status,
                    started_at, ended_at, end_reason)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET status=excluded.status, ended_at=excluded.ended_at,
                    end_reason=excluded.end_reason
                """)) {
            query.setString(1, war.id().toString());
            query.setString(2, war.attackerStateId().toString());
            query.setString(3, war.target().type().name());
            query.setString(4, war.target().id().toString());
            query.setString(5, war.territoryId().toString());
            query.setString(6, war.status().name());
            query.setLong(7, war.startedAt());
            query.setLong(8, war.endedAt());
            query.setString(9, war.endReason());
            query.executeUpdate();
        }
    }

    private void upsertArmy(StateArmy army) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO state_armies(state_id, mode, war_id, world, x, y, z, destination_world,
                    destination_x, destination_y, destination_z, strength, last_update)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(state_id) DO UPDATE SET mode=excluded.mode, war_id=excluded.war_id,
                    world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z,
                    destination_world=excluded.destination_world, destination_x=excluded.destination_x,
                    destination_y=excluded.destination_y, destination_z=excluded.destination_z,
                    strength=excluded.strength, last_update=excluded.last_update
                """)) {
            StatePosition destination = army.destination();
            query.setString(1, army.stateId().toString());
            query.setString(2, army.mode().name());
            query.setString(3, text(army.warId()));
            query.setString(4, army.position().world());
            query.setDouble(5, army.position().x());
            query.setDouble(6, army.position().y());
            query.setDouble(7, army.position().z());
            query.setString(8, destination == null ? null : destination.world());
            if (destination == null) {
                query.setNull(9, java.sql.Types.REAL);
                query.setNull(10, java.sql.Types.REAL);
                query.setNull(11, java.sql.Types.REAL);
            } else {
                query.setDouble(9, destination.x());
                query.setDouble(10, destination.y());
                query.setDouble(11, destination.z());
            }
            query.setInt(12, army.strength());
            query.setLong(13, army.lastUpdate());
            query.executeUpdate();
        }
    }

    private void upsertArsenal(StateArsenal arsenal, ArsenalItem item) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                INSERT INTO state_arsenal_items(state_id, item, amount, progress, last_tick)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(state_id, item) DO UPDATE SET amount=excluded.amount,
                    progress=excluded.progress, last_tick=excluded.last_tick
                """)) {
            query.setString(1, arsenal.stateId().toString());
            query.setString(2, item.name());
            query.setInt(3, arsenal.amount(item));
            query.setDouble(4, arsenal.progress(item));
            query.setLong(5, arsenal.lastTick(item));
            query.executeUpdate();
        }
    }

    private UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private String text(UUID value) {
        return value == null ? null : value.toString();
    }

    public boolean settingBoolean(String key, boolean fallback) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO settings(key, value) VALUES(?, ?)");
                 PreparedStatement query = connection.prepareStatement("SELECT value FROM settings WHERE key=?")) {
                insert.setString(1, key);
                insert.setString(2, Boolean.toString(fallback));
                insert.executeUpdate();
                query.setString(1, key);
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? Boolean.parseBoolean(result.getString(1)) : fallback;
                }
            }
        }
    }

    public void setBoolean(String key, boolean value) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement query = connection.prepareStatement("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                query.setString(1, key);
                query.setString(2, Boolean.toString(value));
                query.executeUpdate();
            }
        }
    }

    public Set<UUID> loadProtectedOwners() throws SQLException {
        synchronized (lock) {
            Set<UUID> result = new LinkedHashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT owner_uuid FROM protected_owners")) {
                while (rows.next()) {
                    try {
                        result.add(UUID.fromString(rows.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return result;
        }
    }

    public void protectOwner(UUID owner) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement query = connection.prepareStatement("INSERT OR IGNORE INTO protected_owners(owner_uuid) VALUES(?)")) {
                query.setString(1, owner.toString());
                query.executeUpdate();
            }
        }
    }

    public void unprotectOwner(UUID owner) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement query = connection.prepareStatement("DELETE FROM protected_owners WHERE owner_uuid=?")) {
                query.setString(1, owner.toString());
                query.executeUpdate();
            }
        }
    }

    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        synchronized (lock) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Не вдалося закрити states.db", ex);
            }
        }
    }
}
