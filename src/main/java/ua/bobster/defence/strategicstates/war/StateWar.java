package ua.bobster.defence.strategicstates.war;

import ua.bobster.defence.combat.CombatPrincipal;

import java.util.UUID;

public class StateWar {

    private final UUID id;
    private final UUID attackerStateId;
    private final CombatPrincipal target;
    private final UUID territoryId;
    private final long startedAt;

    private WarStatus status;
    private long endedAt;
    private String endReason;
    private long phaseStartedAt;

    public StateWar(UUID id, UUID attackerStateId, CombatPrincipal target, UUID territoryId, long startedAt, WarStatus status, long endedAt, String endReason) {
        this.id = id;
        this.attackerStateId = attackerStateId;
        this.target = target;
        this.territoryId = territoryId;
        this.startedAt = startedAt;
        this.status = status;
        this.endedAt = endedAt;
        this.endReason = endReason;
        this.phaseStartedAt = startedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID attackerStateId() {
        return attackerStateId;
    }

    public CombatPrincipal target() {
        return target;
    }

    public UUID territoryId() {
        return territoryId;
    }

    public long startedAt() {
        return startedAt;
    }

    public WarStatus status() {
        return status;
    }

    public long endedAt() {
        return endedAt;
    }

    public String endReason() {
        return endReason;
    }

    public long phaseStartedAt() {
        return phaseStartedAt;
    }

    public void status(WarStatus status, long now) {
        this.status = status;
        phaseStartedAt = now;
    }

    public void finish(WarStatus status, String reason, long now) {
        this.status = status;
        endReason = reason;
        endedAt = now;
    }
}
