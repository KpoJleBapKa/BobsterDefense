package ua.bobster.defence.strategicstates.territory;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import ua.bobster.defence.BobsterDefence;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class TerritoryMapHook {

    private final BobsterDefence plugin;

    private Object api;
    private ClassLoader territoryClassLoader;
    private Method allMethod;
    private Method atMethod;
    private Method createMethod;
    private Method transferMethod;
    private Method deleteMethod;
    private Method replaceGeometryMethod;
    private Method compactGeometryMethod;
    private Method claimChunkMethod;

    public TerritoryMapHook(BobsterDefence plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        api = null;
        territoryClassLoader = null;
        replaceGeometryMethod = null;
        compactGeometryMethod = null;
        claimChunkMethod = null;
        Plugin territoryMap = Bukkit.getPluginManager().getPlugin("TerritoryMap");
        if (territoryMap == null || !territoryMap.isEnabled()) {
            return;
        }
        try {
            territoryClassLoader = territoryMap.getClass().getClassLoader();
            Class<?> apiClass = Class.forName("ua.kroll.territorymap.service.TerritoryMapApi", true, territoryClassLoader);
            api = Bukkit.getServicesManager().load(apiClass);
            if (api == null) {
                return;
            }
            allMethod = apiClass.getMethod("all");
            atMethod = apiClass.getMethod("at", String.class, double.class, double.class);
            Class<?> ownerKind = Class.forName("ua.kroll.territorymap.model.TerritoryOwnerKind", true, territoryClassLoader);
            createMethod = apiClass.getMethod("createSystemTerritory", String.class, UUID.class,
                    String.class, String.class, List.class, String.class, String.class);
            transferMethod = apiClass.getMethod("transferOwner", UUID.class, UUID.class,
                    String.class, ownerKind);
            deleteMethod = apiClass.getMethod("deleteSystemTerritory", UUID.class, UUID.class);
            try {
                replaceGeometryMethod = apiClass.getMethod("replaceSystemGeometry", UUID.class, UUID.class, List.class);
                claimChunkMethod = apiClass.getMethod("claimSystemChunk", UUID.class, UUID.class, String.class, ownerKind, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                replaceGeometryMethod = null;
                claimChunkMethod = null;
            }
            try {
                compactGeometryMethod = apiClass.getMethod("compactSystemGeometry", UUID.class, UUID.class, List.class);
            } catch (NoSuchMethodException ignored) {
                compactGeometryMethod = null;
            }
        } catch (ReflectiveOperationException ex) {
            api = null;
            plugin.getLogger().log(Level.WARNING, "TerritoryMap API недоступний", ex);
        }
    }

    public boolean available() {
        return api != null;
    }

    public boolean writeAvailable() {
        return api != null && createMethod != null && transferMethod != null && deleteMethod != null;
    }

    public boolean geometryWriteAvailable() {
        return writeAvailable() && replaceGeometryMethod != null && claimChunkMethod != null;
    }

    public List<TerritorySnapshot> all() {
        if (api == null) {
            return List.of();
        }
        try {
            Object raw = allMethod.invoke(api);
            if (!(raw instanceof Collection<?> collection)) {
                return List.of();
            }
            List<TerritorySnapshot> result = new ArrayList<>();
            for (Object territory : collection) {
                TerritorySnapshot snapshot = snapshot(territory);
                if (snapshot != null) {
                    result.add(snapshot);
                }
            }
            return List.copyOf(result);
        } catch (ReflectiveOperationException ex) {
            fail(ex);
            return List.of();
        }
    }

    public TerritorySnapshot at(String world, double x, double z) {
        if (api == null) {
            return null;
        }
        try {
            return snapshot(atMethod.invoke(api, world, x, z));
        } catch (ReflectiveOperationException ex) {
            fail(ex);
            return null;
        }
    }

    public TerritorySnapshot byId(UUID id) {
        if (id == null) {
            return null;
        }
        for (TerritorySnapshot territory : all()) {
            if (territory.id().equals(id)) {
                return territory;
            }
        }
        return null;
    }

    public UUID createStateTerritory(UUID stateId, String stateName, String world, double x, double z, double radius, String color) throws TerritoryHookException {
        requireWrite();
        try {
            Class<?> pointClass = Class.forName("ua.kroll.territorymap.model.TerritoryPoint", true, territoryClassLoader);
            Class<?> partClass = Class.forName("ua.kroll.territorymap.model.TerritoryPart", true, territoryClassLoader);
            Constructor<?> pointConstructor = pointClass.getConstructor(double.class, double.class);
            Constructor<?> partConstructor = partClass.getConstructor(List.class, List.class);
            List<Object> ring = circle(pointConstructor, x, z, radius, 32);
            Object part = partConstructor.newInstance(ring, List.of());
            String technicalName = "state_" + stateId.toString().replace("-", "").substring(0, 12);
            Object created = createMethod.invoke(api, technicalName, stateId, stateName, world,
                    List.of(part), color, color + "40");
            TerritorySnapshot snapshot = snapshot(created);
            if (snapshot == null) {
                throw new TerritoryHookException("TerritoryMap повернув порожню територію");
            }
            return snapshot.id();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new TerritoryHookException(cause.getMessage(), cause);
        } catch (ReflectiveOperationException ex) {
            throw new TerritoryHookException("Не вдалося створити територію держави", ex);
        }
    }

    public boolean resizeStateTerritory(UUID territoryId, UUID stateId, double x, double z, double radius) throws TerritoryHookException {
        if (!geometryWriteAvailable()) {
            throw new TerritoryHookException("TerritoryMap geometry API недоступний");
        }
        try {
            Class<?> pointClass = Class.forName("ua.kroll.territorymap.model.TerritoryPoint", true, territoryClassLoader);
            Class<?> partClass = Class.forName("ua.kroll.territorymap.model.TerritoryPart", true, territoryClassLoader);
            Constructor<?> pointConstructor = pointClass.getConstructor(double.class, double.class);
            Constructor<?> partConstructor = partClass.getConstructor(List.class, List.class);
            Object part = partConstructor.newInstance(circle(pointConstructor, x, z, radius, 48), List.of());
            return replaceGeometryMethod.invoke(api, territoryId, stateId, List.of(part)) != null;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new TerritoryHookException(cause.getMessage(), cause);
        } catch (ReflectiveOperationException ex) {
            throw new TerritoryHookException("Не вдалося розширити територію держави", ex);
        }
    }

    public boolean claimChunk(UUID territoryId, UUID stateId, String stateName, int chunkX, int chunkZ) throws TerritoryHookException {
        if (!geometryWriteAvailable()) {
            throw new TerritoryHookException("TerritoryMap chunk claim API недоступний");
        }
        try {
            Class<?> ownerKindClass = Class.forName("ua.kroll.territorymap.model.TerritoryOwnerKind", true, territoryClassLoader);
            @SuppressWarnings("unchecked")
            Object kind = Enum.valueOf((Class<? extends Enum>) ownerKindClass.asSubclass(Enum.class), TerritoryOwnerKind.STRATEGIC_STATE.name());
            return claimChunkMethod.invoke(api, territoryId, stateId, stateName, kind, chunkX, chunkZ) != null;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new TerritoryHookException(cause.getMessage(), cause);
        } catch (ReflectiveOperationException ex) {
            throw new TerritoryHookException("Не вдалося захопити чанк", ex);
        }
    }

    public boolean compactStateTerritory(UUID territoryId, UUID stateId, double centerX, double centerZ, double radius) throws TerritoryHookException {
        if (!writeAvailable() || compactGeometryMethod == null) {
            return false;
        }
        try {
            Class<?> pointClass = Class.forName("ua.kroll.territorymap.model.TerritoryPoint", true, territoryClassLoader);
            Class<?> partClass = Class.forName("ua.kroll.territorymap.model.TerritoryPart", true, territoryClassLoader);
            Constructor<?> point = pointClass.getConstructor(double.class, double.class);
            Constructor<?> part = partClass.getConstructor(List.class, List.class);
            Object geometry = part.newInstance(circle(point, centerX, centerZ, radius, 48), List.of());
            return compactGeometryMethod.invoke(api, territoryId, stateId, List.of(geometry)) != null;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new TerritoryHookException(cause.getMessage(), cause);
        } catch (ReflectiveOperationException ex) {
            throw new TerritoryHookException("TerritoryMap compact API failed", ex);
        }
    }

    private List<Object> circle(Constructor<?> pointConstructor, double x, double z, double radius, int points) throws ReflectiveOperationException {
        List<Object> ring = new ArrayList<>();
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0D * index / points;
            ring.add(pointConstructor.newInstance(x + Math.cos(angle) * radius, z + Math.sin(angle) * radius));
        }
        return List.copyOf(ring);
    }

    public boolean transferOwner(UUID territoryId, UUID ownerId, String ownerName, TerritoryOwnerKind kind) throws TerritoryHookException {
        requireWrite();
        try {
            Class<?> ownerKindClass = Class.forName("ua.kroll.territorymap.model.TerritoryOwnerKind", true, territoryClassLoader);
            @SuppressWarnings("unchecked")
            Object rawKind = Enum.valueOf((Class<? extends Enum>) ownerKindClass.asSubclass(Enum.class), kind.name());
            return Boolean.TRUE.equals(transferMethod.invoke(api, territoryId, ownerId, ownerName, rawKind));
        } catch (ReflectiveOperationException ex) {
            throw new TerritoryHookException("Не вдалося змінити власника території", ex);
        }
    }

    public void deleteStateTerritory(UUID territoryId, UUID stateId) {
        if (!writeAvailable()) {
            return;
        }
        try {
            deleteMethod.invoke(api, territoryId, stateId);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING, "Не вдалося відкотити територію держави", ex);
        }
    }

    private TerritorySnapshot snapshot(Object raw) throws ReflectiveOperationException {
        if (raw == null) {
            return null;
        }
        Class<?> type = raw.getClass();
        UUID id = (UUID) type.getMethod("getId").invoke(raw);
        String name = (String) type.getMethod("getName").invoke(raw);
        UUID ownerId = (UUID) type.getMethod("getOwner").invoke(raw);
        String ownerName = (String) type.getMethod("getOwnerName").invoke(raw);
        Object rawOwnerKind = type.getMethod("getOwnerKind").invoke(raw);
        TerritoryOwnerKind ownerKind = rawOwnerKind == null ? TerritoryOwnerKind.NONE : TerritoryOwnerKind.valueOf(rawOwnerKind.toString());
        String world = (String) type.getMethod("getWorld").invoke(raw);
        Collection<?> rawParts = (Collection<?>) type.getMethod("getParts").invoke(raw);
        List<TerritoryPartSnapshot> parts = new ArrayList<>();
        for (Object rawPart : rawParts) {
            parts.add(part(rawPart));
        }
        return new TerritorySnapshot(id, name, ownerId, ownerName, ownerKind, world, parts);
    }

    private TerritoryPartSnapshot part(Object raw) throws ReflectiveOperationException {
        Class<?> type = raw.getClass();
        List<TerritoryPointSnapshot> outer = points((Collection<?>) type.getMethod("outer").invoke(raw));
        Collection<?> rawHoles = (Collection<?>) type.getMethod("holes").invoke(raw);
        List<List<TerritoryPointSnapshot>> holes = new ArrayList<>();
        for (Object rawHole : rawHoles) {
            holes.add(points((Collection<?>) rawHole));
        }
        return new TerritoryPartSnapshot(outer, holes);
    }

    private List<TerritoryPointSnapshot> points(Collection<?> raw) throws ReflectiveOperationException {
        List<TerritoryPointSnapshot> result = new ArrayList<>();
        for (Object point : raw) {
            Class<?> type = point.getClass();
            double x = ((Number) type.getMethod("x").invoke(point)).doubleValue();
            double z = ((Number) type.getMethod("z").invoke(point)).doubleValue();
            result.add(new TerritoryPointSnapshot(x, z));
        }
        return List.copyOf(result);
    }

    private void requireWrite() throws TerritoryHookException {
        if (!writeAvailable()) {
            throw new TerritoryHookException("TerritoryMap write API недоступний");
        }
    }

    private void fail(Exception ex) {
        plugin.getLogger().log(Level.WARNING, "TerritoryMap API вимкнено після помилки", ex);
        api = null;
    }
}
