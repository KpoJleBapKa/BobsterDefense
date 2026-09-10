package ua.bobster.defence.strategicstates.capture;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.StrategicStateManager;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.population.CitizenManager;
import ua.bobster.defence.strategicstates.population.CitizenNavigator;
import ua.bobster.defence.strategicstates.population.CitizenProfession;
import ua.bobster.defence.strategicstates.population.StateCitizen;
import ua.bobster.defence.strategicstates.territory.TerritoryHookException;
import ua.bobster.defence.strategicstates.territory.TerritoryMapHook;
import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;
import ua.bobster.defence.strategicstates.war.StateWar;
import ua.bobster.defence.strategicstates.war.WarManager;
import ua.bobster.defence.strategicstates.war.WarStatus;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Collectors;

public class CaptureEngine {

    private final BobsterDefence plugin;
    private final StrategicStateManager states;
    private final CitizenManager citizens;
    private final CaptureSectorManager sectors;
    private final WarManager wars;
    private final TerritoryMapHook territories;
    private final CitizenNavigator navigator = new CitizenNavigator();

    private BukkitTask task;
    private long intervalTicks;
    private double radius;
    private double captureTime;
    private long graceMillis;
    private double decayPerSecond;
    private double movementSpeed;
    private int minimumAttackers;
    private double bossBarRadius;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    public CaptureEngine(BobsterDefence plugin, StrategicStateManager states, CitizenManager citizens, CaptureSectorManager sectors, WarManager wars, TerritoryMapHook territories) {
        this.plugin = plugin;
        this.states = states;
        this.citizens = citizens;
        this.sectors = sectors;
        this.wars = wars;
        this.territories = territories;
        reload();
    }

    public void reload() {
        intervalTicks = Math.max(1L, plugin.getConfig().getLong("strategic-states.capture.tick-interval-ticks", 5L));
        radius = Math.max(1.0D, plugin.getConfig().getDouble("strategic-states.capture.capture-radius", 16.0D));
        captureTime = Math.max(1.0D, plugin.getConfig().getDouble("strategic-states.capture.capture-time-seconds", 420.0D));
        minimumAttackers = Math.max(1, plugin.getConfig().getInt("strategic-states.capture.minimum-attackers", 10));
        bossBarRadius = Math.max(16.0D, plugin.getConfig().getDouble("strategic-states.capture.bossbar-radius", 128.0D));
        graceMillis = Math.max(0L, plugin.getConfig().getLong("strategic-states.capture.grace-period-seconds", 30L)) * 1000L;
        decayPerSecond = Math.max(0.0D, plugin.getConfig().getDouble("strategic-states.capture.decay-per-second", 2.0D)) / 100.0D;
        movementSpeed = Math.clamp(plugin.getConfig().getDouble("strategic-states.population.physical-movement-speed", 0.35D), 0.05D, 1.0D);
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(), intervalTicks, intervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (BossBar bossBar : bossBars.values()) {
            bossBar.removeAll();
        }
        bossBars.clear();
    }

    private void tick() {
        Set<UUID> active = wars.active().stream().map(StateWar::id).collect(Collectors.toSet());
        citizens.releaseInactiveWars(active);
        if (!states.attacksEnabled()) {
            return;
        }
        for (StateWar war : wars.active()) {
            tick(war);
        }
        Set<UUID> activeTerritories = wars.active().stream().map(StateWar::territoryId).collect(Collectors.toSet());
        long now = System.currentTimeMillis();
        for (CaptureSector sector : sectors.all()) {
            if (!activeTerritories.contains(sector.territoryId()) && sector.progress() > 0.0D && now - sector.lastPresence() >= graceMillis) {
                sector.decay(decayPerSecond * intervalTicks / 20.0D);
                sectors.save(sector);
            }
        }
    }

    private void tick(StateWar war) {
        StrategicState attacker = states.byId(war.attackerStateId());
        if (attacker == null) {
            wars.finish(war, WarStatus.ENDED, "ATTACKER_DESTROYED");
            return;
        }
        if (!territories.available()) {
            return;
        }
        TerritorySnapshot territory = territories.byId(war.territoryId());
        if (territory == null || territory.ownerId() == null) {
            wars.finish(war, WarStatus.ENDED, "TERRITORY_MISSING");
            return;
        }
        if (!territory.ownerId().equals(war.target().id())) {
            wars.finish(war, WarStatus.ENDED, "TERRITORY_OWNER_CHANGED");
            return;
        }
        CaptureSector sector = sectors.frontline(war.territoryId(), attacker.id(), attacker.core());
        if (sector == null) {
            completeTerritory(war, attacker);
            return;
        }
        World world = Bukkit.getWorld(sector.world());
        int chunkX = (int) Math.floor(sector.controlX()) >> 4;
        int chunkZ = (int) Math.floor(sector.controlZ()) >> 4;
        if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        Location point = new Location(world, sector.controlX(), world.getHighestBlockYAt((int) Math.floor(sector.controlX()), (int) Math.floor(sector.controlZ())) + 1.0D, sector.controlZ());
        int attackers = attackers(war, point);
        int defenders = defenders(war, point);
        long now = System.currentTimeMillis();
        if (attackers > 0 && defenders > 0) {
            sector.contested(now);
            sectors.save(sector);
            updateBossBar(sector, point, "Оборона чанку: бій", BarColor.RED);
            return;
        }
        if (attackers > 0 && attackers < minimumAttackers) {
            updateBossBar(sector, point, "Очікування підмоги: " + attackers + "/" + minimumAttackers, BarColor.YELLOW);
            return;
        }
        if (attackers >= minimumAttackers) {
            if (!war.attackerStateId().equals(sector.attackerStateId())) {
                sector.begin(war.attackerStateId(), now);
            }
            sector.advance(intervalTicks / 20.0D / captureTime, now);
            updateBossBar(sector, point, "Захоплення чанку: " + attackers + " військових", BarColor.RED);
            if (sector.progress() >= 1.0D) {
                try {
                    if (!territories.claimChunk(war.territoryId(), attacker.id(), attacker.name(), sector.gridX(), sector.gridZ())) {
                        sector.advance(-0.001D, now);
                        return;
                    }
                } catch (TerritoryHookException ex) {
                    sector.begin(war.attackerStateId(), now);
                    sector.advance(0.999D, now);
                    plugin.getLogger().warning("Не вдалося передати захоплений чанк TerritoryMap: " + ex.getMessage());
                    return;
                }
                sector.capture(war.attackerStateId());
                if (!sectors.persist(sector)) {
                    return;
                }
                removeBossBar(sector.id());
            } else {
                sectors.save(sector);
            }
            if (sectors.fullyControlled(war.territoryId(), war.attackerStateId())) {
                completeTerritory(war, attacker);
            }
            return;
        }
        if (sector.progress() > 0.0D && now - sector.lastPresence() >= graceMillis) {
            sector.decay(decayPerSecond * intervalTicks / 20.0D);
            sectors.save(sector);
        } else if (sector.progress() <= 0.0D && sector.status() != SectorStatus.CONTROLLED) {
            sector.decay(0.0D);
            sectors.save(sector);
        }
        if (attackers == 0) {
            removeBossBar(sector.id());
        }
    }

    private int attackers(StateWar war, Location point) {
        int count = 0;
        for (StateCitizen citizen : citizens.physicalCitizens()) {
            if (!citizen.stateId().equals(war.attackerStateId()) || !military(citizen.profession())) {
                continue;
            }
            Pillager entity = citizens.physicalEntity(citizen);
            if (entity == null || entity.getWorld() != point.getWorld()) {
                continue;
            }
            citizens.assignment(citizen, "WAR:" + war.id(), citizen.homeBuilding(), citizen.workBuilding());
            if (entity.getTarget() == null || entity.getTarget().isDead()) {
                navigator.move(entity, point, movementSpeed);
            }
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.CROSSBOW));
            if (entity.getLocation().getChunk().getX() == ((int) Math.floor(point.getX()) >> 4)
                    && entity.getLocation().getChunk().getZ() == ((int) Math.floor(point.getZ()) >> 4)) {
                count++;
            }
        }
        return count;
    }

    private void updateBossBar(CaptureSector sector, Location point, String title, BarColor color) {
        BossBar bossBar = bossBars.computeIfAbsent(sector.id(), ignored -> Bukkit.createBossBar(title, color, BarStyle.SEGMENTED_10));
        bossBar.setTitle(title);
        bossBar.setColor(color);
        bossBar.setProgress(Math.clamp(sector.progress(), 0.0D, 1.0D));
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean visible = player.getWorld() == point.getWorld() && horizontalDistanceSquared(player.getLocation(), point) <= bossBarRadius * bossBarRadius;
            if (visible && !bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            } else if (!visible && bossBar.getPlayers().contains(player)) {
                bossBar.removePlayer(player);
            }
        }
    }

    private void removeBossBar(UUID sectorId) {
        BossBar bossBar = bossBars.remove(sectorId);
        if (bossBar != null) {
            bossBar.removeAll();
        }
    }

    private int defenders(StateWar war, Location point) {
        if (war.target().type() == CombatPrincipalType.PLAYER) {
            Player player = Bukkit.getPlayer(war.target().id());
            return player != null && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                    && player.getWorld() == point.getWorld() && horizontalDistanceSquared(player.getLocation(), point) <= radius * radius ? 1 : 0;
        }
        int count = 0;
        for (StateCitizen citizen : citizens.physicalCitizens()) {
            if (!citizen.stateId().equals(war.target().id()) || !military(citizen.profession())) {
                continue;
            }
            Pillager entity = citizens.physicalEntity(citizen);
            if (entity == null || entity.getWorld() != point.getWorld()) {
                continue;
            }
            citizens.assignment(citizen, "WAR:" + war.id(), citizen.homeBuilding(), citizen.workBuilding());
            if (entity.getTarget() == null || entity.getTarget().isDead()) {
                navigator.move(entity, point, movementSpeed);
            }
            entity.getEquipment().setItemInMainHand(new ItemStack(Material.CROSSBOW));
            if (horizontalDistanceSquared(entity.getLocation(), point) <= radius * radius) {
                count++;
            }
        }
        return count;
    }

    private void completeTerritory(StateWar war, StrategicState attacker) {
        if (!sectors.fullyControlled(war.territoryId(), attacker.id())) {
            return;
        }
        if (territories.writeAvailable()) {
            try {
                if (!territories.transferOwner(war.territoryId(), attacker.id(), attacker.name(), TerritoryOwnerKind.STRATEGIC_STATE)) {
                    plugin.getLogger().warning("Sector overlay захоплено, але TerritoryMap відхилив зміну owner: " + war.territoryId());
                }
            } catch (TerritoryHookException ex) {
                plugin.getLogger().warning("Sector overlay захоплено, але TerritoryMap owner не змінено: " + ex.getMessage());
            }
        } else {
            plugin.getLogger().warning("Sector overlay захоплено без TerritoryMap write API: " + war.territoryId());
        }
        wars.finish(war, WarStatus.ENDED, "TERRITORY_CAPTURED");
    }

    private boolean military(CitizenProfession profession) {
        return profession == CitizenProfession.SOLDIER || profession == CitizenProfession.CROSSBOWMAN
                || profession == CitizenProfession.GUARD || profession == CitizenProfession.COMMANDER;
    }

    private double horizontalDistanceSquared(Location first, Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }
}
