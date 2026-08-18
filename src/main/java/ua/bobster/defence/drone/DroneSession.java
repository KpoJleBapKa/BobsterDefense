package ua.bobster.defence.drone;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;

import java.util.UUID;

/**
 * Стан одного польоту. Тут лежить усе, що треба повернути оператору після детонації.
 */
public class DroneSession {

    private final UUID operator;
    private final DroneType type;
    private final Location origin;
    private final GameMode originalGameMode;
    private final float originalFlySpeed;
    private final boolean originalAllowFlight;

    private final ArmorStand hitbox;
    private final BlockDisplay display;
    private final ArmorStand body;

    private Location lastLocation;
    private int ticks;
    private boolean ending;

    public DroneSession(UUID operator, DroneType type, Location origin, GameMode originalGameMode,
                        float originalFlySpeed, boolean originalAllowFlight,
                        ArmorStand hitbox, BlockDisplay display, ArmorStand body) {
        this.operator = operator;
        this.type = type;
        this.origin = origin;
        this.originalGameMode = originalGameMode;
        this.originalFlySpeed = originalFlySpeed;
        this.originalAllowFlight = originalAllowFlight;
        this.hitbox = hitbox;
        this.display = display;
        this.body = body;
        this.lastLocation = origin.clone();
    }

    public UUID operator() {
        return operator;
    }

    public DroneType type() {
        return type;
    }

    public Location origin() {
        return origin;
    }

    public GameMode originalGameMode() {
        return originalGameMode;
    }

    public float originalFlySpeed() {
        return originalFlySpeed;
    }

    public boolean originalAllowFlight() {
        return originalAllowFlight;
    }

    public ArmorStand hitbox() {
        return hitbox;
    }

    public BlockDisplay display() {
        return display;
    }

    public ArmorStand body() {
        return body;
    }

    public Location lastLocation() {
        return lastLocation;
    }

    public void lastLocation(Location location) {
        this.lastLocation = location;
    }

    public int ticks() {
        return ticks;
    }

    public int tick() {
        return ++ticks;
    }

    /** Захист від повторного завершення (напр. вибух + вихід з гри в один тік). */
    public boolean beginEnding() {
        if (ending) {
            return false;
        }
        ending = true;
        return true;
    }

    public boolean isEnding() {
        return ending;
    }

    /** Причина завершення польоту — від неї залежить повідомлення оператору. */
    public enum EndReason {
        COLLISION,
        INTERCEPTED,
        RAMMED,
        SIGNAL_LOST,
        BATTERY_EMPTY,
        OPERATOR_DIED,
        SHUTDOWN
    }
}
