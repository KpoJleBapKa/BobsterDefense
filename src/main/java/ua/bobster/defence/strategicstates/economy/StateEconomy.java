package ua.bobster.defence.strategicstates.economy;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class StateEconomy {

    private final UUID stateId;
    private final Map<ResourceType, Double> resources = new EnumMap<>(ResourceType.class);

    private int housingCapacity;
    private double stability;
    private double populationProgress;
    private long lastEconomyTick;
    private long lastPopulationTick;

    public StateEconomy(UUID stateId, Map<ResourceType, Double> resources, int housingCapacity, double stability, double populationProgress, long lastEconomyTick, long lastPopulationTick) {
        this.stateId = stateId;
        for (ResourceType type : ResourceType.values()) {
            this.resources.put(type, Math.max(0.0D, resources.getOrDefault(type, 0.0D)));
        }
        this.housingCapacity = Math.max(0, housingCapacity);
        this.stability = Math.clamp(stability, 0.0D, 100.0D);
        this.populationProgress = Math.max(0.0D, populationProgress);
        this.lastEconomyTick = lastEconomyTick;
        this.lastPopulationTick = lastPopulationTick;
    }

    public static StateEconomy initial(UUID stateId, int housingCapacity, long now) {
        Map<ResourceType, Double> resources = new EnumMap<>(ResourceType.class);
        resources.put(ResourceType.FOOD, 400.0D);
        resources.put(ResourceType.WOOD, 300.0D);
        resources.put(ResourceType.STONE, 300.0D);
        resources.put(ResourceType.IRON, 40.0D);
        resources.put(ResourceType.COAL, 32.0D);
        return new StateEconomy(stateId, resources, housingCapacity, 80.0D, 0.0D, now, now);
    }

    public UUID stateId() {
        return stateId;
    }

    public double amount(ResourceType type) {
        return resources.getOrDefault(type, 0.0D);
    }

    public void add(ResourceType type, double amount) {
        resources.put(type, Math.max(0.0D, amount(type) + amount));
    }

    public void amount(ResourceType type, double amount) {
        resources.put(type, Math.max(0.0D, amount));
    }

    public boolean consume(ResourceType type, double amount) {
        if (amount < 0.0D || amount(type) < amount) {
            return false;
        }
        resources.put(type, amount(type) - amount);
        return true;
    }

    public Map<ResourceType, Double> resources() {
        return Map.copyOf(resources);
    }

    public int housingCapacity() {
        return housingCapacity;
    }

    public void housingCapacity(int housingCapacity) {
        this.housingCapacity = Math.max(0, housingCapacity);
    }

    public double stability() {
        return stability;
    }

    public void stability(double stability) {
        this.stability = Math.clamp(stability, 0.0D, 100.0D);
    }

    public double populationProgress() {
        return populationProgress;
    }

    public void populationProgress(double populationProgress) {
        this.populationProgress = Math.max(0.0D, populationProgress);
    }

    public long lastEconomyTick() {
        return lastEconomyTick;
    }

    public void lastEconomyTick(long lastEconomyTick) {
        this.lastEconomyTick = lastEconomyTick;
    }

    public long lastPopulationTick() {
        return lastPopulationTick;
    }

    public void lastPopulationTick(long lastPopulationTick) {
        this.lastPopulationTick = lastPopulationTick;
    }
}
