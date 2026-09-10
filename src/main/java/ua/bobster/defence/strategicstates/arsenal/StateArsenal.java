package ua.bobster.defence.strategicstates.arsenal;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class StateArsenal {

    private final UUID stateId;
    private final Map<ArsenalItem, Integer> amounts = new EnumMap<>(ArsenalItem.class);
    private final Map<ArsenalItem, Double> progress = new EnumMap<>(ArsenalItem.class);
    private final Map<ArsenalItem, Long> lastTicks = new EnumMap<>(ArsenalItem.class);

    public StateArsenal(UUID stateId) {
        this.stateId = stateId;
        for (ArsenalItem item : ArsenalItem.values()) {
            amounts.put(item, 0);
            progress.put(item, 0.0D);
            lastTicks.put(item, 0L);
        }
    }

    public UUID stateId() {
        return stateId;
    }

    public int amount(ArsenalItem item) {
        return amounts.getOrDefault(item, 0);
    }

    public void amount(ArsenalItem item, int amount) {
        amounts.put(item, Math.max(0, amount));
    }

    public double progress(ArsenalItem item) {
        return progress.getOrDefault(item, 0.0D);
    }

    public void progress(ArsenalItem item, double progress) {
        this.progress.put(item, Math.clamp(progress, 0.0D, 1.0D));
    }

    public long lastTick(ArsenalItem item) {
        return lastTicks.getOrDefault(item, 0L);
    }

    public void lastTick(ArsenalItem item, long lastTick) {
        lastTicks.put(item, lastTick);
    }

    public Map<ArsenalItem, Integer> amounts() {
        return Map.copyOf(amounts);
    }
}
