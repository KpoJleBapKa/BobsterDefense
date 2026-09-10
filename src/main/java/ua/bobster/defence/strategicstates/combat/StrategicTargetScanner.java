package ua.bobster.defence.strategicstates.combat;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.scheduler.BukkitTask;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.capture.CaptureSector;
import ua.bobster.defence.strategicstates.persistence.StateRepository;
import ua.bobster.defence.strategicstates.territory.TerritoryMapHook;
import ua.bobster.defence.strategicstates.territory.TerritorySnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StrategicTargetScanner {

    private record Cached(Location location, int score, long scannedAt) {
    }

    private record ChunkPosition(String world, int x, int z) {
    }

    private static final class ScanJob {

        private final TerritorySnapshot territory;
        private final Deque<ChunkPosition> chunks;
        private ChunkPosition chunk;
        private int localX;
        private int localZ;
        private int y;
        private int minimumY;
        private int columnScore;
        private int chunkScore;
        private int focusScore;
        private Location focus;
        private int bestScore;
        private Location best;

        private ScanJob(TerritorySnapshot territory, Deque<ChunkPosition> chunks) {
            this.territory = territory;
            this.chunks = chunks;
        }
    }

    private final BobsterDefence plugin;
    private final TerritoryMapHook territories;
    private final StateRepository repository;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private final Map<UUID, Long> completedScans = new HashMap<>();
    private final Map<UUID, ScanJob> jobs = new HashMap<>();
    private final Deque<UUID> order = new ArrayDeque<>();
    private BukkitTask task;

    public StrategicTargetScanner(BobsterDefence plugin, TerritoryMapHook territories, StateRepository repository) {
        this.plugin = plugin;
        this.territories = territories;
        this.repository = repository;
    }

    public void load(Map<UUID, StrategicTargetCache> loaded) {
        cache.clear();
        completedScans.clear();
        for (StrategicTargetCache target : loaded.values()) {
            World world = plugin.getServer().getWorld(target.world());
            if (world != null) {
                cache.put(target.territoryId(), new Cached(new Location(world, target.x(), target.y(), target.z()), target.score(), target.scannedAt()));
                completedScans.put(target.territoryId(), target.scannedAt());
            }
        }
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        jobs.clear();
        order.clear();
    }

    public Location target(TerritorySnapshot territory, UUID attackerStateId, Iterable<CaptureSector> sectors) {
        if (territory == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        long lifetime = lifetimeMillis();
        int minimum = minimumScore();
        Cached cached = cache.get(territory.id());
        if (cached != null && now - cached.scannedAt() <= lifetime && cached.score() >= minimum && valid(cached.location(), territory, attackerStateId, sectors)) {
            return cached.location().clone();
        }
        long completed = completedScans.getOrDefault(territory.id(), 0L);
        if (now - completed > lifetime) {
            enqueue(territory);
            return null;
        }
        return fallback(territory, attackerStateId, sectors);
    }

    private void enqueue(TerritorySnapshot territory) {
        if (jobs.containsKey(territory.id())) {
            return;
        }
        World world = plugin.getServer().getWorld(territory.world());
        if (world == null) {
            completedScans.put(territory.id(), System.currentTimeMillis());
            return;
        }
        Deque<ChunkPosition> chunks = new ArrayDeque<>();
        for (Chunk chunk : world.getLoadedChunks()) {
            double x = (chunk.getX() << 4) + 8.5D;
            double z = (chunk.getZ() << 4) + 8.5D;
            TerritorySnapshot at = territories.at(world.getName(), x, z);
            if (at != null && at.id().equals(territory.id())) {
                chunks.addLast(new ChunkPosition(world.getName(), chunk.getX(), chunk.getZ()));
            }
        }
        if (chunks.isEmpty()) {
            completedScans.put(territory.id(), System.currentTimeMillis());
            return;
        }
        jobs.put(territory.id(), new ScanJob(territory, chunks));
        order.addLast(territory.id());
    }

    private void tick() {
        int budget = Math.max(128, plugin.getConfig().getInt("strategic-states.strikes.scan-blocks-per-tick", 2048));
        while (budget > 0 && !order.isEmpty()) {
            UUID territoryId = order.removeFirst();
            ScanJob job = jobs.get(territoryId);
            if (job == null) {
                continue;
            }
            int used = process(job, budget);
            budget -= Math.max(1, used);
            if (finished(job)) {
                complete(territoryId, job);
            } else {
                order.addLast(territoryId);
            }
        }
    }

    private int process(ScanJob job, int budget) {
        int used = 0;
        while (used < budget) {
            if (job.chunk == null && !nextChunk(job)) {
                break;
            }
            World world = plugin.getServer().getWorld(job.chunk.world());
            if (world == null || !world.isChunkLoaded(job.chunk.x(), job.chunk.z())) {
                finishChunk(job);
                continue;
            }
            int x = (job.chunk.x() << 4) + job.localX;
            int z = (job.chunk.z() << 4) + job.localZ;
            Block block = world.getBlockAt(x, job.y, z);
            int value = value(block);
            job.columnScore += value;
            job.chunkScore += value;
            used++;
            job.y--;
            if (job.y < job.minimumY) {
                finishColumn(job, world, x, z);
            }
        }
        return used;
    }

    private boolean nextChunk(ScanJob job) {
        job.chunk = job.chunks.pollFirst();
        if (job.chunk == null) {
            return false;
        }
        job.localX = 0;
        job.localZ = 0;
        job.chunkScore = 0;
        job.focusScore = 0;
        job.focus = null;
        prepareColumn(job);
        return true;
    }

    private void prepareColumn(ScanJob job) {
        World world = plugin.getServer().getWorld(job.chunk.world());
        if (world == null || !world.isChunkLoaded(job.chunk.x(), job.chunk.z())) {
            job.y = 0;
            job.minimumY = 1;
            return;
        }
        int x = (job.chunk.x() << 4) + job.localX;
        int z = (job.chunk.z() << 4) + job.localZ;
        job.y = world.getHighestBlockYAt(x, z);
        job.minimumY = Math.max(world.getMinHeight(), job.y - 12);
        job.columnScore = 0;
    }

    private void finishColumn(ScanJob job, World world, int x, int z) {
        if (job.columnScore > job.focusScore) {
            job.focusScore = job.columnScore;
            job.focus = new Location(world, x + 0.5D, world.getHighestBlockYAt(x, z) + 1.0D, z + 0.5D);
        }
        job.localZ++;
        if (job.localZ >= 16) {
            job.localZ = 0;
            job.localX++;
        }
        if (job.localX >= 16) {
            finishChunk(job);
        } else {
            prepareColumn(job);
        }
    }

    private void finishChunk(ScanJob job) {
        if (job.chunk != null && job.chunkScore > job.bestScore && job.focus != null) {
            job.bestScore = job.chunkScore;
            job.best = job.focus.clone();
        }
        job.chunk = null;
    }

    private boolean finished(ScanJob job) {
        return job.chunk == null && job.chunks.isEmpty();
    }

    private void complete(UUID territoryId, ScanJob job) {
        jobs.remove(territoryId);
        long now = System.currentTimeMillis();
        completedScans.put(territoryId, now);
        if (job.best == null) {
            cache.remove(territoryId);
            return;
        }
        Cached result = new Cached(job.best.clone(), job.bestScore, now);
        cache.put(territoryId, result);
        repository.saveStrategicTargetAsync(new StrategicTargetCache(territoryId, job.best.getWorld().getName(), job.best.getX(), job.best.getY(), job.best.getZ(), job.bestScore, now));
    }

    private Location fallback(TerritorySnapshot territory, UUID attackerStateId, Iterable<CaptureSector> sectors) {
        List<CaptureSector> candidates = new ArrayList<>();
        for (CaptureSector sector : sectors) {
            if (sector.territoryId().equals(territory.id()) && !attackerStateId.equals(sector.controllerId())) {
                candidates.add(sector);
            }
        }
        Collections.shuffle(candidates);
        for (CaptureSector sector : candidates) {
            World world = plugin.getServer().getWorld(sector.world());
            if (world == null) {
                continue;
            }
            int x = (int) Math.floor(sector.controlX());
            int z = (int) Math.floor(sector.controlZ());
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location result = new Location(world, x + 0.5D, y, z + 0.5D);
            if (valid(result, territory, attackerStateId, sectors)) {
                return result;
            }
        }
        return null;
    }

    private int value(Block block) {
        Material material = block.getType();
        if (block.getState() instanceof Container) {
            return 6;
        }
        String name = material.name();
        if (name.contains("REDSTONE") || name.contains("PISTON") || name.contains("OBSERVER") || name.contains("DISPENSER")) {
            return 6;
        }
        if (name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("BRICKS")) {
            return 10;
        }
        if (name.contains("CONCRETE")) {
            return 8;
        }
        if (name.contains("PLANKS")) {
            return 2;
        }
        return material == Material.IRON_BLOCK || material == Material.COPPER_BLOCK ? 4 : 0;
    }

    private boolean valid(Location location, TerritorySnapshot territory, UUID attackerStateId, Iterable<CaptureSector> sectors) {
        if (location == null || location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }
        TerritorySnapshot at = territories.at(location.getWorld().getName(), location.getX(), location.getZ());
        if (at == null || !at.id().equals(territory.id())) {
            return false;
        }
        Material ground = location.getWorld().getBlockAt(location.getBlockX(), Math.max(location.getWorld().getMinHeight(), location.getBlockY() - 1), location.getBlockZ()).getType();
        if (ground == Material.WATER || ground == Material.LAVA || ground == Material.BEDROCK) {
            return false;
        }
        for (CaptureSector sector : sectors) {
            if (sector.territoryId().equals(territory.id()) && attackerStateId.equals(sector.controllerId())) {
                double dx = sector.controlX() - location.getX();
                double dz = sector.controlZ() - location.getZ();
                int size = Math.max(16, plugin.getConfig().getInt("strategic-states.capture.sector-size", 64));
                if (Math.abs(dx) <= size / 2.0D && Math.abs(dz) <= size / 2.0D) {
                    return false;
                }
            }
        }
        return true;
    }

    private long lifetimeMillis() {
        return Math.max(10L, plugin.getConfig().getLong("strategic-states.strikes.target-cache-seconds", 900L)) * 1000L;
    }

    private int minimumScore() {
        return Math.max(1, plugin.getConfig().getInt("strategic-states.strikes.minimum-structure-score", 80));
    }
}
