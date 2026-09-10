package ua.bobster.defence.combat;

import ua.bobster.defence.aa.TeamRegistry;

public class AllegianceService {

    private final TeamRegistry teams;
    private java.util.function.BiPredicate<java.util.UUID, java.util.UUID> strategicAlliance = (first, second) -> false;

    public AllegianceService(TeamRegistry teams) {
        this.teams = teams;
    }

    public boolean friendly(CombatPrincipal first, CombatPrincipal second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.equals(second)) {
            return true;
        }
        if (first.type() == CombatPrincipalType.PLAYER && second.type() == CombatPrincipalType.PLAYER) {
            return teams.friendly(first.id(), second.id());
        }
        if (first.type() == CombatPrincipalType.STRATEGIC_STATE && second.type() == CombatPrincipalType.STRATEGIC_STATE) {
            return strategicAlliance.test(first.id(), second.id()) && strategicAlliance.test(second.id(), first.id());
        }
        return false;
    }

    public void strategicAlliance(java.util.function.BiPredicate<java.util.UUID, java.util.UUID> strategicAlliance) {
        this.strategicAlliance = strategicAlliance == null ? (first, second) -> false : strategicAlliance;
    }

}
