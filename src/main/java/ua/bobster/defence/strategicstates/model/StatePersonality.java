package ua.bobster.defence.strategicstates.model;

public record StatePersonality(int aggression, int economyFocus, int technologyFocus, int defenceFocus, int expansionFocus, int populationFocus) {

    public StatePersonality {
        aggression = Math.clamp(aggression, 0, 100);
        economyFocus = Math.clamp(economyFocus, 0, 100);
        technologyFocus = Math.clamp(technologyFocus, 0, 100);
        defenceFocus = Math.clamp(defenceFocus, 0, 100);
        expansionFocus = Math.clamp(expansionFocus, 0, 100);
        populationFocus = Math.clamp(populationFocus, 0, 100);
    }
}
