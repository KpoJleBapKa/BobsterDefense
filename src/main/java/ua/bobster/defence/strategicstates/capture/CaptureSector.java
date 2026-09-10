package ua.bobster.defence.strategicstates.capture;

import ua.bobster.defence.strategicstates.territory.TerritoryOwnerKind;

import java.util.UUID;

public class CaptureSector {

    private final UUID id;
    private final UUID territoryId;
    private final UUID originalOwnerId;
    private final TerritoryOwnerKind originalOwnerKind;
    private final String world;
    private final int gridX;
    private final int gridZ;
    private final double controlX;
    private final double controlZ;

    private UUID controllerId;
    private UUID attackerStateId;
    private double progress;
    private SectorStatus status;
    private long lastPresence;

    public CaptureSector(UUID id, UUID territoryId, UUID originalOwnerId, TerritoryOwnerKind originalOwnerKind, String world, int gridX, int gridZ, double controlX, double controlZ, UUID controllerId, UUID attackerStateId, double progress, SectorStatus status, long lastPresence) {
        this.id = id;
        this.territoryId = territoryId;
        this.originalOwnerId = originalOwnerId;
        this.originalOwnerKind = originalOwnerKind;
        this.world = world;
        this.gridX = gridX;
        this.gridZ = gridZ;
        this.controlX = controlX;
        this.controlZ = controlZ;
        this.controllerId = controllerId;
        this.attackerStateId = attackerStateId;
        this.progress = Math.clamp(progress, 0.0D, 1.0D);
        this.status = status;
        this.lastPresence = lastPresence;
    }

    public UUID id() {
        return id;
    }

    public UUID territoryId() {
        return territoryId;
    }

    public UUID originalOwnerId() {
        return originalOwnerId;
    }

    public TerritoryOwnerKind originalOwnerKind() {
        return originalOwnerKind;
    }

    public String world() {
        return world;
    }

    public int gridX() {
        return gridX;
    }

    public int gridZ() {
        return gridZ;
    }

    public double controlX() {
        return controlX;
    }

    public double controlZ() {
        return controlZ;
    }

    public UUID controllerId() {
        return controllerId;
    }

    public UUID attackerStateId() {
        return attackerStateId;
    }

    public double progress() {
        return progress;
    }

    public SectorStatus status() {
        return status;
    }

    public long lastPresence() {
        return lastPresence;
    }

    public void begin(UUID attackerStateId, long now) {
        if (this.attackerStateId != null && !this.attackerStateId.equals(attackerStateId)) {
            progress = 0.0D;
        }
        this.attackerStateId = attackerStateId;
        status = SectorStatus.CAPTURING;
        lastPresence = now;
    }

    public void contested(long now) {
        status = SectorStatus.CONTESTED;
        lastPresence = now;
    }

    public void advance(double amount, long now) {
        progress = Math.clamp(progress + amount, 0.0D, 1.0D);
        status = SectorStatus.CAPTURING;
        lastPresence = now;
    }

    public void decay(double amount) {
        progress = Math.clamp(progress - amount, 0.0D, 1.0D);
        if (progress <= 0.0D) {
            attackerStateId = null;
            status = SectorStatus.CONTROLLED;
        } else {
            status = SectorStatus.CAPTURING;
        }
    }

    public void capture(UUID controllerId) {
        this.controllerId = controllerId;
        attackerStateId = null;
        progress = 0.0D;
        status = SectorStatus.CONTROLLED;
        lastPresence = 0L;
    }
}
