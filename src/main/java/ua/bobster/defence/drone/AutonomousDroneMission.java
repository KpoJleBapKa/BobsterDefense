package ua.bobster.defence.drone;

import org.bukkit.Location;
import ua.bobster.defence.combat.CombatPrincipal;

public record AutonomousDroneMission(CombatPrincipal owner, Location origin, Location target, String typeId, double laneOffset, double heightAboveTarget) {

    public AutonomousDroneMission(CombatPrincipal owner, Location origin, Location target, String typeId) {
        this(owner, origin, target, typeId, 0.0D, 40.0D);
    }

    public AutonomousDroneMission(CombatPrincipal owner, Location origin, Location target, String typeId, double laneOffset) {
        this(owner, origin, target, typeId, laneOffset, 40.0D);
    }
}
