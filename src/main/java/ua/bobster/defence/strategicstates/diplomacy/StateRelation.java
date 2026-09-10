package ua.bobster.defence.strategicstates.diplomacy;

import ua.bobster.defence.combat.CombatPrincipal;

import java.util.UUID;

public class StateRelation {

    private final UUID stateId;
    private final CombatPrincipal target;
    private double score;
    private DiplomaticStatus status;
    private long updatedAt;

    public StateRelation(UUID stateId, CombatPrincipal target, double score, DiplomaticStatus status, long updatedAt) {
        this.stateId = stateId;
        this.target = target;
        this.score = Math.clamp(score, -100.0D, 100.0D);
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public UUID stateId() {
        return stateId;
    }

    public CombatPrincipal target() {
        return target;
    }

    public double score() {
        return score;
    }

    public DiplomaticStatus status() {
        return status;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void update(double score, DiplomaticStatus status, long updatedAt) {
        this.score = Math.clamp(score, -100.0D, 100.0D);
        this.status = status;
        this.updatedAt = updatedAt;
    }
}
