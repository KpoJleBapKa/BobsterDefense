package ua.bobster.defence.strike;

import java.util.List;

public record GuidedMissileType(String id, String displayName, double fuelSeconds, double explosionPower, int hitsToIntercept, double speed, List<String> lore) {
}
