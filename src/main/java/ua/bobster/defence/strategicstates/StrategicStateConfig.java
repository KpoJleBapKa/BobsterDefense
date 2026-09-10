package ua.bobster.defence.strategicstates;

import org.bukkit.configuration.file.FileConfiguration;
import ua.bobster.defence.BobsterDefence;

import java.util.List;

public record StrategicStateConfig(boolean enabled, int maxStates, List<String> allowedWorlds, double startingTerritoryRadius, double minimumCoreDistance, List<String> names, int startingPopulationMin, int startingPopulationMax, int maxPopulation, int stateTickSeconds, int economyTickSeconds, int populationTickSeconds, boolean attacksEnabledOnFirstStart) {

    public StrategicStateConfig {
        allowedWorlds = List.copyOf(allowedWorlds);
        names = List.copyOf(names);
    }

    public static StrategicStateConfig load(BobsterDefence plugin) {
        FileConfiguration config = plugin.getConfig();
        int minimumPopulation = Math.max(1, config.getInt("strategic-states.population.starting-min", 4));
        int maximumPopulation = Math.max(minimumPopulation, config.getInt("strategic-states.population.starting-max", 6));
        return new StrategicStateConfig(
                config.getBoolean("strategic-states.enabled", true),
                Math.clamp(config.getInt("strategic-states.max-states", 5), 1, 5),
                config.getStringList("strategic-states.creation.allowed-worlds"),
                Math.max(16.0D, config.getDouble("strategic-states.creation.starting-territory-radius", 64.0D)),
                Math.max(32.0D, config.getDouble("strategic-states.creation.minimum-core-distance", 256.0D)),
                config.getStringList("strategic-states.creation.names"),
                minimumPopulation,
                maximumPopulation,
                Math.max(maximumPopulation, config.getInt("strategic-states.population.max-per-state", 50)),
                Math.max(1, config.getInt("strategic-states.simulation.state-tick-seconds", 10)),
                Math.max(1, config.getInt("strategic-states.simulation.economy-tick-seconds", 30)),
                Math.max(1, config.getInt("strategic-states.simulation.population-tick-seconds", 60)),
                config.getBoolean("strategic-states.attacks.enabled-on-first-start", false));
    }

    public boolean worldAllowed(String world) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(world);
    }
}
