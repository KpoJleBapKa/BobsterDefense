package ua.bobster.defence.combat;

import java.util.UUID;

public record CombatPrincipal(UUID id, CombatPrincipalType type) {

    public CombatPrincipal {
        if (id == null || type == null) {
            throw new IllegalArgumentException("Combat principal requires id and type");
        }
    }

    public static CombatPrincipal player(UUID id) {
        return new CombatPrincipal(id, CombatPrincipalType.PLAYER);
    }

    public static CombatPrincipal state(UUID id) {
        return new CombatPrincipal(id, CombatPrincipalType.STRATEGIC_STATE);
    }
}
