package ua.bobster.defence.strategicstates;

import ua.bobster.defence.strategicstates.model.StateColor;
import ua.bobster.defence.strategicstates.model.StatePersonality;
import ua.bobster.defence.strategicstates.model.StrategicState;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class StateIdentityFactory {

    public record Identity(String name, String shortName, StateColor color, StatePersonality personality) {
    }

    private final Random random = new Random();

    public Identity create(UUID stateId, List<String> configuredNames, Collection<StrategicState> existing) {
        Set<String> usedNames = new HashSet<>();
        Set<StateColor> usedColors = new HashSet<>();
        for (StrategicState state : existing) {
            usedNames.add(state.name().toLowerCase(java.util.Locale.ROOT));
            usedColors.add(state.color());
        }
        String name = configuredNames.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .filter(candidate -> !usedNames.contains(candidate.toLowerCase(java.util.Locale.ROOT)))
                .findFirst()
                .orElse("State-" + stateId.toString().substring(0, 8));
        StateColor color = java.util.Arrays.stream(StateColor.values())
                .filter(candidate -> !usedColors.contains(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Немає вільного кольору держави"));
        String shortName = name.replaceAll("[^A-Za-z0-9]", "");
        if (shortName.length() > 8) {
            shortName = shortName.substring(0, 8);
        }
        if (shortName.isBlank()) {
            shortName = stateId.toString().substring(0, 8);
        }
        StatePersonality personality = new StatePersonality(value(), value(), value(), value(), value(), value());
        return new Identity(name, shortName.toUpperCase(java.util.Locale.ROOT), color, personality);
    }

    private int value() {
        return 20 + random.nextInt(81);
    }
}
