package ua.bobster.defence.strategicstates.model;

import java.util.UUID;

public class StrategicState {

    private final UUID id;
    private final String name;
    private final String shortName;
    private final StateColor color;
    private final StatePosition capital;
    private final StatePosition core;
    private final long createdAt;
    private final StatePersonality personality;
    private final UUID territoryId;

    private StateStatus status;
    private int developmentLevel;
    private long lastSimulation;

    public StrategicState(UUID id, String name, String shortName, StateColor color, StatePosition capital, StatePosition core, long createdAt, StatePersonality personality, UUID territoryId, StateStatus status, int developmentLevel, long lastSimulation) {
        this.id = id;
        this.name = name;
        this.shortName = shortName;
        this.color = color;
        this.capital = capital;
        this.core = core;
        this.createdAt = createdAt;
        this.personality = personality;
        this.territoryId = territoryId;
        this.status = status;
        this.developmentLevel = developmentLevel;
        this.lastSimulation = lastSimulation;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String shortName() {
        return shortName;
    }

    public StateColor color() {
        return color;
    }

    public StatePosition capital() {
        return capital;
    }

    public StatePosition core() {
        return core;
    }

    public long createdAt() {
        return createdAt;
    }

    public StatePersonality personality() {
        return personality;
    }

    public UUID territoryId() {
        return territoryId;
    }

    public StateStatus status() {
        return status;
    }

    public void status(StateStatus status) {
        this.status = status;
    }

    public int developmentLevel() {
        return developmentLevel;
    }

    public void developmentLevel(int developmentLevel) {
        this.developmentLevel = Math.clamp(developmentLevel, 1, 5);
    }

    public long lastSimulation() {
        return lastSimulation;
    }

    public void lastSimulation(long lastSimulation) {
        this.lastSimulation = lastSimulation;
    }
}
