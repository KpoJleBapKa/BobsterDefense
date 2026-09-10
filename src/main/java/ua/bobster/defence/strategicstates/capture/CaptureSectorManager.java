package ua.bobster.defence.strategicstates.capture;

import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.territory.TerritoryPartSnapshot;
import ua.bobster.defence.strategicstates.territory.TerritoryPointSnapshot;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;
import ua.bobster.defence.strategicstates.model.StatePosition;

import java.nio.charset.StandardCharsets;
import java.awt.geom.Line2D;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CaptureSectorManager {

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final Map<UUID, CaptureSector> sectors = new LinkedHashMap<>();

    private int sectorSize;

    public CaptureSectorManager(BobsterDefence plugin, StateRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        reload();
    }

    public void reload() {
        sectorSize = Math.max(16, plugin.getConfig().getInt("strategic-states.capture.sector-size", 64));
    }

    public void load(Collection<CaptureSector> loaded) {
        sectors.clear();
        for (CaptureSector sector : loaded) {
            if (currentGrid(sector)) {
                sectors.put(sector.id(), sector);
                continue;
            }
            try {
                repository.deleteCaptureSector(sector.id());
            } catch (SQLException ex) {
                plugin.getLogger().warning("Не вдалося прибрати сектор старої сітки " + sector.id() + ": " + ex.getMessage());
            }
        }
    }

    public void synchronize(Collection<TerritorySnapshot> territories) {
        for (TerritorySnapshot territory : territories) {
            generate(territory);
        }
    }

    public Collection<CaptureSector> forTerritory(UUID territoryId) {
        return sectors.values().stream().filter(sector -> sector.territoryId().equals(territoryId) && currentGrid(sector)).toList();
    }

    public Collection<CaptureSector> all() {
        return sectors.values().stream().filter(this::currentGrid).toList();
    }

    public int controlledBy(UUID controllerId) {
        return (int) sectors.values().stream().filter(sector -> controllerId.equals(sector.controllerId())).count();
    }

    public CaptureSector frontline(UUID territoryId, UUID attackerStateId, StatePosition origin) {
        List<CaptureSector> territory = forTerritory(territoryId).stream().filter(sector -> !attackerStateId.equals(sector.controllerId())).toList();
        if (territory.isEmpty()) {
            return null;
        }
        boolean foothold = sectors.values().stream().anyMatch(sector -> sector.territoryId().equals(territoryId) && attackerStateId.equals(sector.controllerId()));
        return territory.stream()
                .filter(sector -> !foothold || adjacentToControlled(sector, attackerStateId))
                .min((first, second) -> Double.compare(distance(first, origin), distance(second, origin)))
                .orElse(null);
    }

    public boolean fullyControlled(UUID territoryId, UUID controllerId) {
        Collection<CaptureSector> territory = forTerritory(territoryId);
        return !territory.isEmpty() && territory.stream().allMatch(sector -> controllerId.equals(sector.controllerId()));
    }

    public UUID completedController(UUID territoryId) {
        Collection<CaptureSector> territory = forTerritory(territoryId);
        if (territory.isEmpty()) {
            return null;
        }
        UUID controller = territory.iterator().next().controllerId();
        return controller != null && territory.stream().allMatch(sector -> controller.equals(sector.controllerId())) ? controller : null;
    }

    public void save(CaptureSector sector) {
        repository.saveCaptureSectorAsync(sector);
    }

    public boolean persist(CaptureSector sector) {
        try {
            repository.insertCaptureSector(sector);
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не вдалося синхронно зберегти сектор " + sector.id() + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean adjacentToControlled(CaptureSector candidate, UUID controllerId) {
        for (CaptureSector sector : sectors.values()) {
            if (sector.territoryId().equals(candidate.territoryId()) && controllerId.equals(sector.controllerId())
                    && Math.abs(sector.gridX() - candidate.gridX()) + Math.abs(sector.gridZ() - candidate.gridZ()) == 1) {
                return true;
            }
        }
        return false;
    }

    private double distance(CaptureSector sector, StatePosition origin) {
        if (origin == null || !sector.world().equals(origin.world())) {
            return Double.MAX_VALUE;
        }
        double x = sector.controlX() - origin.x();
        double z = sector.controlZ() - origin.z();
        return x * x + z * z;
    }

    private void generate(TerritorySnapshot territory) {
        prune(territory);
        for (TerritoryPartSnapshot part : territory.parts()) {
            if (part.outer().size() < 3) {
                continue;
            }
            double minimumX = part.outer().stream().mapToDouble(TerritoryPointSnapshot::x).min().orElse(0.0D);
            double maximumX = part.outer().stream().mapToDouble(TerritoryPointSnapshot::x).max().orElse(0.0D);
            double minimumZ = part.outer().stream().mapToDouble(TerritoryPointSnapshot::z).min().orElse(0.0D);
            double maximumZ = part.outer().stream().mapToDouble(TerritoryPointSnapshot::z).max().orElse(0.0D);
            int minimumGridX = Math.floorDiv((int) Math.floor(minimumX), sectorSize);
            int maximumGridX = Math.floorDiv((int) Math.floor(maximumX), sectorSize);
            int minimumGridZ = Math.floorDiv((int) Math.floor(minimumZ), sectorSize);
            int maximumGridZ = Math.floorDiv((int) Math.floor(maximumZ), sectorSize);
            for (int gridX = minimumGridX; gridX <= maximumGridX; gridX++) {
                for (int gridZ = minimumGridZ; gridZ <= maximumGridZ; gridZ++) {
                    double centerX = gridX * (double) sectorSize + sectorSize / 2.0D;
                    double centerZ = gridZ * (double) sectorSize + sectorSize / 2.0D;
                    double startX = gridX * (double) sectorSize;
                    double startZ = gridZ * (double) sectorSize;
                    if (intersects(part, startX, startZ, startX + sectorSize, startZ + sectorSize)) {
                        TerritoryPointSnapshot control = control(part, startX, startZ, centerX, centerZ);
                        add(territory, gridX, gridZ, control.x(), control.z());
                    }
                }
            }
        }
    }

    private void prune(TerritorySnapshot territory) {
        for (CaptureSector sector : List.copyOf(sectors.values())) {
            if (!sector.territoryId().equals(territory.id()) || territory.parts().stream().anyMatch(part -> contains(part, sector.controlX(), sector.controlZ()))) {
                continue;
            }
            sectors.remove(sector.id());
            try {
                repository.deleteCaptureSector(sector.id());
            } catch (SQLException ex) {
                plugin.getLogger().warning("Не вдалося прибрати сектор поза новою геометрією " + sector.id() + ": " + ex.getMessage());
            }
        }
    }

    private void add(TerritorySnapshot territory, int gridX, int gridZ, double centerX, double centerZ) {
        UUID id = UUID.nameUUIDFromBytes((territory.id() + ":" + sectorSize + ":" + gridX + ":" + gridZ).getBytes(StandardCharsets.UTF_8));
        if (sectors.containsKey(id)) {
            return;
        }
        CaptureSector sector = new CaptureSector(id, territory.id(), territory.ownerId(), territory.ownerKind(), territory.world(), gridX, gridZ, centerX, centerZ, territory.ownerId(), null, 0.0D, SectorStatus.CONTROLLED, 0L);
        try {
            repository.insertCaptureSector(sector);
            sectors.put(id, sector);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не вдалося створити capture sector " + id + ": " + ex.getMessage());
        }
    }

    private boolean currentGrid(CaptureSector sector) {
        UUID expected = UUID.nameUUIDFromBytes((sector.territoryId() + ":" + sectorSize + ":" + sector.gridX() + ":" + sector.gridZ()).getBytes(StandardCharsets.UTF_8));
        return expected.equals(sector.id());
    }

    private boolean contains(TerritoryPartSnapshot part, double x, double z) {
        if (!inside(part.outer(), x, z)) {
            return false;
        }
        for (List<TerritoryPointSnapshot> hole : part.holes()) {
            if (inside(hole, x, z)) {
                return false;
            }
        }
        return true;
    }

    private boolean intersects(TerritoryPartSnapshot part, double minimumX, double minimumZ, double maximumX, double maximumZ) {
        if (contains(part, minimumX + 0.01D, minimumZ + 0.01D) || contains(part, maximumX - 0.01D, minimumZ + 0.01D)
                || contains(part, maximumX - 0.01D, maximumZ - 0.01D) || contains(part, minimumX + 0.01D, maximumZ - 0.01D)) {
            return true;
        }
        for (TerritoryPointSnapshot point : part.outer()) {
            if (point.x() >= minimumX && point.x() <= maximumX && point.z() >= minimumZ && point.z() <= maximumZ) {
                return true;
            }
        }
        for (int index = 0; index < part.outer().size(); index++) {
            TerritoryPointSnapshot first = part.outer().get(index);
            TerritoryPointSnapshot second = part.outer().get((index + 1) % part.outer().size());
            if (Line2D.linesIntersect(first.x(), first.z(), second.x(), second.z(), minimumX, minimumZ, maximumX, minimumZ)
                    || Line2D.linesIntersect(first.x(), first.z(), second.x(), second.z(), maximumX, minimumZ, maximumX, maximumZ)
                    || Line2D.linesIntersect(first.x(), first.z(), second.x(), second.z(), maximumX, maximumZ, minimumX, maximumZ)
                    || Line2D.linesIntersect(first.x(), first.z(), second.x(), second.z(), minimumX, maximumZ, minimumX, minimumZ)) {
                return true;
            }
        }
        return false;
    }

    private TerritoryPointSnapshot control(TerritoryPartSnapshot part, double minimumX, double minimumZ, double centerX, double centerZ) {
        if (contains(part, centerX, centerZ)) {
            return new TerritoryPointSnapshot(centerX, centerZ);
        }
        for (double x = minimumX + 0.5D; x < minimumX + sectorSize; x += 1.0D) {
            for (double z = minimumZ + 0.5D; z < minimumZ + sectorSize; z += 1.0D) {
                if (contains(part, x, z)) {
                    return new TerritoryPointSnapshot(x, z);
                }
            }
        }
        return new TerritoryPointSnapshot(centerX, centerZ);
    }

    private boolean inside(List<TerritoryPointSnapshot> ring, double x, double z) {
        boolean inside = false;
        for (int current = 0, previous = ring.size() - 1; current < ring.size(); previous = current++) {
            TerritoryPointSnapshot a = ring.get(current);
            TerritoryPointSnapshot b = ring.get(previous);
            if ((a.z() > z) != (b.z() > z) && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }
}
