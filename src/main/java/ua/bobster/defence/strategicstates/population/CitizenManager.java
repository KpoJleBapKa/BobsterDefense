package ua.bobster.defence.strategicstates.population;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pillager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strategicstates.model.StatePosition;
import ua.bobster.defence.strategicstates.model.StrategicState;
import ua.bobster.defence.strategicstates.model.StateStatus;
import ua.bobster.defence.strategicstates.persistence.StateRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public class CitizenManager {

    private final BobsterDefence plugin;
    private final StateRepository repository;
    private final NamespacedKey citizenKey;
    private final NamespacedKey stateKey;
    private final NamespacedKey professionKey;
    private final Map<UUID, StateCitizen> citizens = new HashMap<>();
    private final Map<UUID, UUID> byEntity = new HashMap<>();
    private final Consumer<UUID> populationChanged;
    private final CitizenNavigator builderNavigator = new CitizenNavigator();

    public CitizenManager(BobsterDefence plugin, StateRepository repository, Consumer<UUID> populationChanged) {
        this.plugin = plugin;
        this.repository = repository;
        this.populationChanged = populationChanged;
        this.citizenKey = new NamespacedKey(plugin, "state_citizen");
        this.stateKey = new NamespacedKey(plugin, "state_id");
        this.professionKey = new NamespacedKey(plugin, "state_profession");
    }

    public void load(Collection<StateCitizen> loaded) {
        citizens.clear();
        byEntity.clear();
        for (StateCitizen citizen : loaded) {
            citizen.mode(CitizenMode.VIRTUAL);
            citizen.entityId(null);
            citizens.put(citizen.id(), citizen);
        }
    }

    public List<StateCitizen> createInitial(StrategicState state, int amount) {
        List<StateCitizen> created = new ArrayList<>();
        List<CitizenProfession> professions = List.of(
                CitizenProfession.WORKER,
                CitizenProfession.WORKER,
                CitizenProfession.LUMBERJACK,
                CitizenProfession.FARMER);
        for (int index = 0; index < amount; index++) {
            CitizenProfession profession = index < professions.size() ? professions.get(index) : CitizenProfession.WORKER;
            StatePosition position = initialPosition(state, index);
            created.add(new StateCitizen(UUID.randomUUID(), state.id(), profession, 18 + index, 0.0D,
                    24.0D, null, null, "IDLE", position, CitizenMode.VIRTUAL, null));
        }
        return created;
    }

    public void addAll(Collection<StateCitizen> created) {
        for (StateCitizen citizen : created) {
            citizens.put(citizen.id(), citizen);
        }
    }

    public StateCitizen grow(StrategicState state) {
        int index = livingPopulation(state.id());
        StateCitizen citizen = new StateCitizen(UUID.randomUUID(), state.id(), CitizenProfession.WORKER,
                18, 0.0D, 24.0D, null, null, "IDLE", initialPosition(state, index),
                CitizenMode.VIRTUAL, null);
        citizens.put(citizen.id(), citizen);
        repository.saveCitizenAsync(citizen);
        materialize(citizen, state);
        return citizen;
    }

    public StateCitizen recruit(StrategicState state, CitizenProfession profession) {
        int index = livingPopulation(state.id());
        StateCitizen citizen = new StateCitizen(UUID.randomUUID(), state.id(), profession, 18, 0.0D, 24.0D, null, null, "MOBILIZED", initialPosition(state, index), CitizenMode.VIRTUAL, null);
        citizens.put(citizen.id(), citizen);
        repository.saveCitizenAsync(citizen);
        materialize(citizen, state);
        populationChanged.accept(state.id());
        return citizen;
    }

    public boolean starveOne(UUID stateId) {
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.stateId().equals(stateId) && citizen.mode() != CitizenMode.DEAD) {
                kill(citizen);
                populationChanged.accept(stateId);
                return true;
            }
        }
        return false;
    }

    public int livingPopulation(UUID stateId) {
        int count = 0;
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.stateId().equals(stateId) && citizen.mode() != CitizenMode.DEAD) {
                count++;
            }
        }
        return count;
    }

    public Map<CitizenProfession, Integer> professions(UUID stateId) {
        Map<CitizenProfession, Integer> result = new EnumMap<>(CitizenProfession.class);
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.stateId().equals(stateId) && citizen.mode() != CitizenMode.DEAD) {
                result.merge(citizen.profession(), 1, Integer::sum);
            }
        }
        return result;
    }

    public void rebalanceProfessions(StrategicState state) {
        UUID stateId = state.id();
        int population = livingPopulation(stateId);
        Map<CitizenProfession, Integer> desired = new EnumMap<>(CitizenProfession.class);
        desired.put(CitizenProfession.FARMER, Math.max(1, population / 8));
        desired.put(CitizenProfession.LUMBERJACK, 1);
        desired.put(CitizenProfession.BUILDER, 1);
        if (population >= 6) {
            desired.put(CitizenProfession.MINER, 1);
        }
        if (population >= 8) {
            desired.put(CitizenProfession.GUARD, 1);
        }
        if (state.developmentLevel() >= 1 && population >= 4) {
            desired.put(CitizenProfession.ENGINEER, 1);
        }
        if (state.developmentLevel() >= 3 && population >= 10) {
            desired.put(CitizenProfession.SOLDIER, Math.max(2, population / 6));
            desired.put(CitizenProfession.CROSSBOWMAN, Math.max(1, population / 10));
        }
        if (state.developmentLevel() >= 3 && population >= 15) {
            desired.put(CitizenProfession.COMMANDER, 1);
        }
        if (state.developmentLevel() >= 1 && population >= 4) {
            desired.put(CitizenProfession.DRONE_OPERATOR, 1);
        }
        if (state.developmentLevel() >= 4 && population >= 18) {
            desired.put(CitizenProfession.AIR_DEFENCE_OPERATOR, 1);
        }
        if (state.developmentLevel() >= 4 && population >= 18) {
            desired.put(CitizenProfession.MISSILE_OPERATOR, 1);
        }
        Map<CitizenProfession, Integer> current = professions(stateId);
        for (Map.Entry<CitizenProfession, Integer> entry : desired.entrySet()) {
            int missing = entry.getValue() - current.getOrDefault(entry.getKey(), 0);
            while (missing > 0) {
                StateCitizen worker = availableWorker(stateId);
                if (worker == null) {
                    break;
                }
                changeProfession(worker, entry.getKey(), state);
                missing--;
            }
        }
    }

    public int physicalPopulation(UUID stateId) {
        int count = 0;
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.stateId().equals(stateId) && citizen.mode() == CitizenMode.PHYSICAL) {
                count++;
            }
        }
        return count;
    }

    public Collection<StateCitizen> physicalCitizens() {
        List<StateCitizen> result = new ArrayList<>();
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.mode() == CitizenMode.PHYSICAL) {
                result.add(citizen);
            }
        }
        return List.copyOf(result);
    }

    public Collection<StateCitizen> militaryCitizens(UUID stateId) {
        List<StateCitizen> result = new ArrayList<>();
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.stateId().equals(stateId) && citizen.mode() != CitizenMode.DEAD && military(citizen.profession())) {
                result.add(citizen);
            }
        }
        return List.copyOf(result);
    }

    public void moveVirtual(StateCitizen citizen, StatePosition position) {
        if (citizen == null || citizen.mode() != CitizenMode.VIRTUAL || position == null) {
            return;
        }
        Location location = position.location();
        if (location != null && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            int y = location.getWorld().getHighestBlockYAt(location.getBlockX(), location.getBlockZ()) + 1;
            position = new StatePosition(position.world(), position.x(), y, position.z());
        }
        citizen.position(position);
        repository.saveCitizenAsync(citizen);
    }

    public void recordCombatHit(StateCitizen citizen, double experience) {
        if (citizen == null || citizen.mode() == CitizenMode.DEAD) {
            return;
        }
        citizen.combatExperience(citizen.combatExperience() + Math.max(0.0D, experience));
        repository.saveCitizenAsync(citizen);
    }

    public StatePosition currentPosition(StateCitizen citizen) {
        Pillager entity = physicalEntity(citizen);
        return entity == null ? citizen.position() : StatePosition.from(entity.getLocation());
    }

    public Pillager physicalEntity(StateCitizen citizen) {
        if (citizen == null || citizen.entityId() == null) {
            return null;
        }
        Entity entity = findEntity(citizen.entityId());
        return entity instanceof Pillager pillager ? pillager : null;
    }

    public boolean hasLivingProfession(UUID stateId, CitizenProfession profession) {
        return citizens.values().stream().anyMatch(citizen -> citizen.stateId().equals(stateId)
                && citizen.mode() != CitizenMode.DEAD && citizen.profession() == profession);
    }

    public StateCitizen physicalProfession(UUID stateId, CitizenProfession profession, Location near, double radius) {
        StateCitizen closest = null;
        double closestDistance = radius * radius;
        for (StateCitizen citizen : citizens.values()) {
            if (!citizen.stateId().equals(stateId) || citizen.mode() != CitizenMode.PHYSICAL || citizen.profession() != profession) {
                continue;
            }
            Pillager entity = physicalEntity(citizen);
            if (entity == null || near == null || entity.getWorld() != near.getWorld()) {
                continue;
            }
            double distance = entity.getLocation().distanceSquared(near);
            if (distance <= closestDistance) {
                closest = citizen;
                closestDistance = distance;
            }
        }
        return closest;
    }

    public void assignment(StateCitizen citizen, String task, String homeBuilding, String workBuilding) {
        if (citizen == null) {
            return;
        }
        boolean changed = !Objects.equals(citizen.currentTask(), task)
                || !Objects.equals(citizen.homeBuilding(), homeBuilding)
                || !Objects.equals(citizen.workBuilding(), workBuilding);
        citizen.currentTask(task);
        citizen.homeBuilding(homeBuilding);
        citizen.workBuilding(workBuilding);
        if (changed) {
            repository.saveCitizenAsync(citizen);
        }
    }

    public void animateBuilder(UUID stateId, UUID buildingId, Location movementTarget, Location actionTarget, Material material, double movementSpeed) {
        StateCitizen builder = findBuilder(stateId, buildingId);
        if (builder == null || builder.entityId() == null) {
            return;
        }
        Entity raw = findEntity(builder.entityId());
        if (!(raw instanceof Pillager pillager) || raw.getWorld() != movementTarget.getWorld()) {
            return;
        }
        String task = "BUILD:" + buildingId;
        if (!task.equals(builder.currentTask())) {
            builder.profession(CitizenProfession.BUILDER);
            builder.currentTask(task);
            pillager.getPersistentDataContainer().set(professionKey, PersistentDataType.STRING, CitizenProfession.BUILDER.name());
            repository.saveCitizenAsync(builder);
        }
        builderNavigator.move(pillager, movementTarget, movementSpeed);
        pillager.lookAt(actionTarget);
        pillager.swingMainHand();
        if (material.isItem()) {
            pillager.getEquipment().setItemInMainHand(new ItemStack(material));
        }
    }

    public void finishBuilding(UUID stateId, UUID buildingId) {
        String task = "BUILD:" + buildingId;
        for (StateCitizen citizen : citizens.values()) {
            if (!citizen.stateId().equals(stateId) || !task.equals(citizen.currentTask())) {
                continue;
            }
            citizen.currentTask("IDLE");
            if (citizen.entityId() != null) {
                Entity raw = findEntity(citizen.entityId());
                if (raw instanceof Pillager pillager) {
                    pillager.getEquipment().setItemInMainHand(new ItemStack(Material.CROSSBOW));
                }
            }
            repository.saveCitizenAsync(citizen);
        }
    }

    public void releaseInactiveWars(Set<UUID> activeWars) {
        for (StateCitizen citizen : citizens.values()) {
            String task = citizen.currentTask();
            if (task == null || !task.startsWith("WAR:")) {
                continue;
            }
            try {
                if (activeWars.contains(UUID.fromString(task.substring(4)))) {
                    continue;
                }
            } catch (IllegalArgumentException ignored) {
            }
            citizen.currentTask("IDLE");
            repository.saveCitizenAsync(citizen);
        }
    }

    private StateCitizen findBuilder(UUID stateId, UUID buildingId) {
        String task = "BUILD:" + buildingId;
        StateCitizen worker = null;
        for (StateCitizen citizen : citizens.values()) {
            if (!citizen.stateId().equals(stateId) || citizen.mode() != CitizenMode.PHYSICAL) {
                continue;
            }
            if (task.equals(citizen.currentTask())) {
                return citizen;
            }
            if (worker == null && (citizen.profession() == CitizenProfession.BUILDER || citizen.profession() == CitizenProfession.WORKER)) {
                worker = citizen;
            }
        }
        return worker;
    }

    private StateCitizen availableWorker(UUID stateId) {
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.stateId().equals(stateId) && citizen.mode() != CitizenMode.DEAD
                    && citizen.profession() == CitizenProfession.WORKER
                    && (citizen.currentTask() == null || !citizen.currentTask().startsWith("BUILD:"))) {
                return citizen;
            }
        }
        return null;
    }

    private void changeProfession(StateCitizen citizen, CitizenProfession profession, StrategicState state) {
        citizen.profession(profession);
        if (citizen.entityId() != null) {
            Entity raw = findEntity(citizen.entityId());
            if (raw instanceof Pillager pillager) {
                pillager.getPersistentDataContainer().set(professionKey, PersistentDataType.STRING, profession.name());
                pillager.customName(Component.text("[" + state.shortName() + "] " + display(profession), TextColor.fromHexString(state.color().hex())));
            }
        }
        repository.saveCitizenAsync(citizen);
    }

    public void materializeState(StrategicState state) {
        if (state.status() == StateStatus.PAUSED || state.status() == StateStatus.DESTROYED) {
            return;
        }
        for (StateCitizen citizen : citizens.values()) {
            if (citizen.stateId().equals(state.id())) {
                materialize(citizen, state);
            }
        }
    }

    public void materializeChunk(Chunk chunk, java.util.function.Function<UUID, StrategicState> stateLookup) {
        reconcileChunk(chunk);
        for (StateCitizen citizen : citizens.values()) {
            StatePosition position = citizen.position();
            if (citizen.mode() != CitizenMode.VIRTUAL || !position.world().equals(chunk.getWorld().getName())) {
                continue;
            }
            if (((int) Math.floor(position.x())) >> 4 != chunk.getX()
                    || ((int) Math.floor(position.z())) >> 4 != chunk.getZ()) {
                continue;
            }
            StrategicState state = stateLookup.apply(citizen.stateId());
            if (state != null && state.status() != StateStatus.PAUSED && state.status() != StateStatus.DESTROYED) {
                materialize(citizen, state);
            }
        }
    }

    public void virtualizeChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (entity.getPersistentDataContainer().has(citizenKey, PersistentDataType.STRING)) {
                virtualize(entity);
            }
        }
    }

    public void virtualizeAll() {
        List<UUID> entities = new ArrayList<>(byEntity.keySet());
        for (UUID entityId : entities) {
            Entity entity = findEntity(entityId);
            if (entity != null) {
                virtualize(entity);
            }
        }
    }

    public void virtualizeState(UUID stateId) {
        for (StateCitizen citizen : citizens.values()) {
            if (!citizen.stateId().equals(stateId) || citizen.entityId() == null) {
                continue;
            }
            Entity entity = findEntity(citizen.entityId());
            if (entity != null) {
                virtualize(entity);
            }
        }
    }

    public boolean handleDeath(Entity entity) {
        StateCitizen citizen = citizenByEntity(entity);
        if (citizen == null || citizen.mode() == CitizenMode.DEAD) {
            return false;
        }
        kill(citizen);
        populationChanged.accept(citizen.stateId());
        return true;
    }

    public void destroyState(UUID stateId) {
        for (StateCitizen citizen : citizens.values()) {
            if (!citizen.stateId().equals(stateId) || citizen.mode() == CitizenMode.DEAD) {
                continue;
            }
            if (citizen.entityId() != null) {
                Entity entity = findEntity(citizen.entityId());
                byEntity.remove(citizen.entityId());
                CitizenNavigator.forget(citizen.entityId());
                if (entity != null) {
                    entity.remove();
                }
            }
            citizen.entityId(null);
            citizen.health(0.0D);
            citizen.currentTask("DEAD");
            citizen.mode(CitizenMode.DEAD);
            repository.saveCitizenAsync(citizen);
        }
    }

    public StateCitizen citizenByEntity(Entity entity) {
        UUID citizenId = byEntity.get(entity.getUniqueId());
        if (citizenId == null) {
            String raw = entity.getPersistentDataContainer().get(citizenKey, PersistentDataType.STRING);
            if (raw == null) {
                return null;
            }
            try {
                citizenId = UUID.fromString(raw);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        return citizens.get(citizenId);
    }

    private void materialize(StateCitizen citizen, StrategicState state) {
        if (citizen.mode() != CitizenMode.VIRTUAL) {
            return;
        }
        Location location = citizen.position().location();
        if (location == null) {
            return;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!location.getWorld().isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        location = safeLocation(location);
        citizen.mode(CitizenMode.MATERIALIZING);
        try {
            Pillager pillager = location.getWorld().spawn(location, Pillager.class, spawned -> {
                spawned.setPersistent(true);
                spawned.setRemoveWhenFarAway(false);
                spawned.setAware(true);
                spawned.setCanPickupItems(false);
                spawned.getPersistentDataContainer().set(citizenKey, PersistentDataType.STRING, citizen.id().toString());
                spawned.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, citizen.stateId().toString());
                spawned.getPersistentDataContainer().set(professionKey, PersistentDataType.STRING, citizen.profession().name());
                TextColor color = TextColor.fromHexString(state.color().hex());
                spawned.customName(Component.text("[" + state.shortName() + "] " + display(citizen.profession()), color));
                spawned.setCustomNameVisible(true);
                spawned.setHealth(Math.min(spawned.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue(), citizen.health()));
            });
            citizen.entityId(pillager.getUniqueId());
            citizen.mode(CitizenMode.PHYSICAL);
            byEntity.put(pillager.getUniqueId(), citizen.id());
            repository.saveCitizenAsync(citizen);
        } catch (RuntimeException ex) {
            citizen.entityId(null);
            citizen.mode(CitizenMode.VIRTUAL);
            plugin.getLogger().warning("Не вдалося матеріалізувати Citizen " + citizen.id() + ": " + ex.getMessage());
        }
    }

    private void virtualize(Entity entity) {
        StateCitizen citizen = citizenByEntity(entity);
        if (citizen == null || citizen.mode() != CitizenMode.PHYSICAL) {
            return;
        }
        citizen.mode(CitizenMode.VIRTUALIZING);
        citizen.position(StatePosition.from(entity.getLocation()));
        if (entity instanceof Pillager pillager) {
            citizen.health(pillager.getHealth());
        }
        byEntity.remove(entity.getUniqueId());
        CitizenNavigator.forget(entity.getUniqueId());
        citizen.entityId(null);
        entity.remove();
        citizen.mode(CitizenMode.VIRTUAL);
        repository.saveCitizenAsync(citizen);
    }

    public void virtualizeForTravel(StateCitizen citizen) {
        Pillager pillager = physicalEntity(citizen);
        if (pillager == null) {
            return;
        }
        if (pillager.getVehicle() instanceof org.bukkit.entity.Boat boat) {
            boat.eject();
            boat.remove();
        }
        virtualize(pillager);
    }

    public void materializeForTravel(StateCitizen citizen, StrategicState state) {
        materialize(citizen, state);
    }

    private void kill(StateCitizen citizen) {
        if (citizen.entityId() != null) {
            Entity entity = findEntity(citizen.entityId());
            byEntity.remove(citizen.entityId());
            CitizenNavigator.forget(citizen.entityId());
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        }
        citizen.entityId(null);
        citizen.health(0.0D);
        citizen.mode(CitizenMode.DEAD);
        citizen.currentTask("DEAD");
        repository.saveCitizenAsync(citizen);
    }

    private Entity findEntity(UUID id) {
        for (World world : plugin.getServer().getWorlds()) {
            Entity entity = world.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private void reconcileChunk(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            String raw = entity.getPersistentDataContainer().get(citizenKey, PersistentDataType.STRING);
            if (raw == null) {
                continue;
            }
            StateCitizen citizen;
            try {
                citizen = citizens.get(UUID.fromString(raw));
            } catch (IllegalArgumentException ex) {
                entity.remove();
                continue;
            }
            if (citizen == null || citizen.mode() == CitizenMode.DEAD) {
                entity.remove();
                continue;
            }
            if (citizen.mode() == CitizenMode.PHYSICAL && citizen.entityId() != null
                    && !citizen.entityId().equals(entity.getUniqueId())) {
                entity.remove();
                continue;
            }
            citizen.mode(CitizenMode.PHYSICAL);
            citizen.entityId(entity.getUniqueId());
            citizen.position(StatePosition.from(entity.getLocation()));
            byEntity.put(entity.getUniqueId(), citizen.id());
        }
    }

    private Location safeLocation(Location preferred) {
        World world = preferred.getWorld();
        int x = preferred.getBlockX();
        int z = preferred.getBlockZ();
        int baseY = preferred.getBlockY();
        for (int offset = 0; offset <= 8; offset++) {
            int up = baseY + offset;
            if (safe(world, x, up, z)) {
                return new Location(world, x + 0.5D, up, z + 0.5D);
            }
            int down = baseY - offset;
            if (offset > 0 && safe(world, x, down, z)) {
                return new Location(world, x + 0.5D, down, z + 0.5D);
            }
        }
        return preferred;
    }

    private boolean safe(World world, int x, int y, int z) {
        return world.getBlockAt(x, y - 1, z).getType().isSolid()
                && world.getBlockAt(x, y, z).isPassable()
                && world.getBlockAt(x, y + 1, z).isPassable();
    }

    private StatePosition initialPosition(StrategicState state, int index) {
        double angle = index * Math.PI * 2.0D / 6.0D;
        return new StatePosition(state.core().world(), state.core().x() + 3.0D * Math.cos(angle),
                state.core().y() + 1.0D, state.core().z() + 3.0D * Math.sin(angle));
    }

    private String display(CitizenProfession profession) {
        String raw = profession.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private boolean military(CitizenProfession profession) {
        return profession == CitizenProfession.SOLDIER || profession == CitizenProfession.CROSSBOWMAN
                || profession == CitizenProfession.GUARD || profession == CitizenProfession.COMMANDER;
    }
}
