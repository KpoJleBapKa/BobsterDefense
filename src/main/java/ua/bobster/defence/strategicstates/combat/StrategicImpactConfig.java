package ua.bobster.defence.strategicstates.combat;

import org.bukkit.configuration.file.FileConfiguration;

public record StrategicImpactConfig(double maximumGroundDistance, double ballisticStabilityPerPower, double droneStabilityPerPower, double ballisticResourceLossPerPower, double droneResourceLossPerPower, double maximumResourceLoss, double retaliationThreat, double threatHalfLifeHours) {

    public static StrategicImpactConfig load(FileConfiguration config) {
        return new StrategicImpactConfig(
                Math.max(0.0D, config.getDouble("strategic-states.incoming-strikes.maximum-ground-distance", 16.0D)),
                Math.max(0.0D, config.getDouble("strategic-states.incoming-strikes.ballistic-stability-loss-per-power", 2.0D)),
                Math.max(0.0D, config.getDouble("strategic-states.incoming-strikes.drone-stability-loss-per-power", 1.0D)),
                Math.max(0.0D, config.getDouble("strategic-states.incoming-strikes.ballistic-resource-loss-per-power", 0.015D)),
                Math.max(0.0D, config.getDouble("strategic-states.incoming-strikes.drone-resource-loss-per-power", 0.0075D)),
                Math.clamp(config.getDouble("strategic-states.incoming-strikes.maximum-resource-loss", 0.25D), 0.0D, 1.0D),
                Math.max(0.0D, config.getDouble("strategic-states.incoming-strikes.retaliation-threat", 20.0D)),
                Math.max(0.01D, config.getDouble("strategic-states.incoming-strikes.threat-half-life-hours", 24.0D)));
    }
}
