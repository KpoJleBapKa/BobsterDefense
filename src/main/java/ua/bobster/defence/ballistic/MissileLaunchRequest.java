package ua.bobster.defence.ballistic;

import org.bukkit.Location;
import ua.bobster.defence.combat.CombatPrincipal;

public record MissileLaunchRequest(CombatPrincipal owner, Location origin, Location target, String tierId) {
}
