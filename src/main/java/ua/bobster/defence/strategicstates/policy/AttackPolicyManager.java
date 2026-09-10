package ua.bobster.defence.strategicstates.policy;

import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.combat.CombatPrincipalType;
import ua.bobster.defence.strategicstates.StrategicStateManager;

public class AttackPolicyManager {

    private final StrategicStateManager states;

    public AttackPolicyManager(StrategicStateManager states) {
        this.states = states;
    }

    public boolean canAct(CombatPrincipal attacker, CombatPrincipal target, ActionContext context, StrategicAction action) {
        if (!states.available() || !states.config().enabled() || attacker == null || target == null || action == null) {
            return false;
        }
        if (context == ActionContext.OFFENSIVE && !states.attacksEnabled()) {
            return false;
        }
        if (context == ActionContext.OFFENSIVE && target.type() == CombatPrincipalType.PLAYER
                && states.isProtected(target.id())) {
            return false;
        }
        return context != ActionContext.DEFENSIVE || action == StrategicAction.LOCAL_SELF_DEFENCE;
    }
}
