package ua.bobster.defence.strategicstates.construction;

import ua.bobster.defence.strategicstates.model.StatePosition;

import java.util.UUID;

public class StateBuilding {

    private final UUID id;
    private final UUID stateId;
    private final BuildingType type;
    private final String template;
    private StatePosition origin;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final long createdAt;

    private BuildingStatus status;
    private double progress;
    private int materializedBlocks;
    private long lastProgress;
    private boolean effectsApplied;
    private double integrity;
    private String blockedReason;

    public StateBuilding(UUID id, UUID stateId, BuildingType type, String template, StatePosition origin, int sizeX, int sizeY, int sizeZ, long createdAt, BuildingStatus status, double progress, int materializedBlocks, long lastProgress, boolean effectsApplied, double integrity, String blockedReason) {
        this.id = id;
        this.stateId = stateId;
        this.type = type;
        this.template = template;
        this.origin = origin;
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        this.createdAt = createdAt;
        this.status = status;
        this.progress = Math.clamp(progress, 0.0D, 1.0D);
        this.materializedBlocks = Math.max(0, materializedBlocks);
        this.lastProgress = lastProgress;
        this.effectsApplied = effectsApplied;
        this.integrity = Math.clamp(integrity, 0.0D, 1.0D);
        this.blockedReason = blockedReason;
    }

    public UUID id() {
        return id;
    }

    public UUID stateId() {
        return stateId;
    }

    public BuildingType type() {
        return type;
    }

    public String template() {
        return template;
    }

    public StatePosition origin() {
        return origin;
    }

    public void origin(StatePosition origin) {
        this.origin = origin;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public long createdAt() {
        return createdAt;
    }

    public BuildingStatus status() {
        return status;
    }

    public void status(BuildingStatus status) {
        this.status = status;
    }

    public double progress() {
        return progress;
    }

    public void progress(double progress) {
        this.progress = Math.clamp(progress, 0.0D, 1.0D);
    }

    public int materializedBlocks() {
        return materializedBlocks;
    }

    public void materializedBlocks(int materializedBlocks) {
        this.materializedBlocks = Math.max(0, materializedBlocks);
    }

    public long lastProgress() {
        return lastProgress;
    }

    public void lastProgress(long lastProgress) {
        this.lastProgress = lastProgress;
    }

    public boolean effectsApplied() {
        return effectsApplied;
    }

    public void effectsApplied(boolean effectsApplied) {
        this.effectsApplied = effectsApplied;
    }

    public double integrity() {
        return integrity;
    }

    public void integrity(double integrity) {
        this.integrity = Math.clamp(integrity, 0.0D, 1.0D);
    }

    public String blockedReason() {
        return blockedReason;
    }

    public void blockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public boolean contains(String world, double x, double y, double z) {
        return origin.world().equals(world) && x >= origin.x() && x < origin.x() + sizeX
                && y >= origin.y() && y < origin.y() + sizeY && z >= origin.z() && z < origin.z() + sizeZ;
    }

    public boolean containsHorizontal(String world, double x, double z) {
        return origin.world().equals(world) && x >= origin.x() && x < origin.x() + sizeX
                && z >= origin.z() && z < origin.z() + sizeZ;
    }

    public boolean intersects(StateBuilding other) {
        if (!origin.world().equals(other.origin.world())) {
            return false;
        }
        return origin.x() < other.origin.x() + other.sizeX && origin.x() + sizeX > other.origin.x()
                && origin.y() < other.origin.y() + other.sizeY && origin.y() + sizeY > other.origin.y()
                && origin.z() < other.origin.z() + other.sizeZ && origin.z() + sizeZ > other.origin.z();
    }
}
