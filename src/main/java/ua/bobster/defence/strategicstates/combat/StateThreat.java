package ua.bobster.defence.strategicstates.combat;

import ua.bobster.defence.combat.CombatPrincipal;

import java.util.UUID;

public class StateThreat {

    private final UUID stateId;
    private final CombatPrincipal attacker;

    private double threat;
    private long lastAttack;
    private int ballisticHits;
    private int droneHits;

    public StateThreat(UUID stateId, CombatPrincipal attacker, double threat, long lastAttack, int ballisticHits, int droneHits) {
        this.stateId = stateId;
        this.attacker = attacker;
        this.threat = Math.max(0.0D, threat);
        this.lastAttack = lastAttack;
        this.ballisticHits = Math.max(0, ballisticHits);
        this.droneHits = Math.max(0, droneHits);
    }

    public void record(StrategicWeaponType weapon, double power, long now, double halfLifeHours) {
        if (lastAttack > 0L && halfLifeHours > 0.0D) {
            double elapsedHours = Math.max(0L, now - lastAttack) / 3_600_000.0D;
            threat *= Math.pow(0.5D, elapsedHours / halfLifeHours);
        }
        double multiplier = weapon == StrategicWeaponType.BALLISTIC ? 2.0D : 1.0D;
        threat = Math.min(1000.0D, threat + Math.max(0.0D, power) * multiplier);
        lastAttack = now;
        if (weapon == StrategicWeaponType.BALLISTIC) {
            ballisticHits++;
        } else {
            droneHits++;
        }
    }

    public UUID stateId() {
        return stateId;
    }

    public CombatPrincipal attacker() {
        return attacker;
    }

    public double threat() {
        return threat;
    }

    public long lastAttack() {
        return lastAttack;
    }

    public int ballisticHits() {
        return ballisticHits;
    }

    public int droneHits() {
        return droneHits;
    }
}
