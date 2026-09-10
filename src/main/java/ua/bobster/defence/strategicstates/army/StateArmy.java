package ua.bobster.defence.strategicstates.army;

import ua.bobster.defence.strategicstates.model.StatePosition;

import java.util.UUID;

public class StateArmy {

    private final UUID stateId;
    private ArmyMode mode;
    private UUID warId;
    private StatePosition position;
    private StatePosition destination;
    private int strength;
    private long lastUpdate;

    public StateArmy(UUID stateId, ArmyMode mode, UUID warId, StatePosition position, StatePosition destination, int strength, long lastUpdate) {
        this.stateId = stateId;
        this.mode = mode;
        this.warId = warId;
        this.position = position;
        this.destination = destination;
        this.strength = Math.max(0, strength);
        this.lastUpdate = lastUpdate;
    }

    public UUID stateId() {
        return stateId;
    }

    public ArmyMode mode() {
        return mode;
    }

    public UUID warId() {
        return warId;
    }

    public StatePosition position() {
        return position;
    }

    public StatePosition destination() {
        return destination;
    }

    public int strength() {
        return strength;
    }

    public long lastUpdate() {
        return lastUpdate;
    }

    public void update(ArmyMode mode, UUID warId, StatePosition position, StatePosition destination, int strength, long lastUpdate) {
        this.mode = mode;
        this.warId = warId;
        this.position = position;
        this.destination = destination;
        this.strength = Math.max(0, strength);
        this.lastUpdate = lastUpdate;
    }
}
