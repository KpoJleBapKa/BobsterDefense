package ua.bobster.defence.strategicstates.combat;

import java.util.UUID;

public record StrategicTargetCache(UUID territoryId, String world, double x, double y, double z, int score, long scannedAt) {
}
