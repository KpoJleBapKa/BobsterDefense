package ua.bobster.defence.aa;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import ua.bobster.defence.combat.CombatPrincipal;

import java.util.UUID;

/**
 * Ціль для ППО, зведена до спільного вигляду.
 * <p>
 * Установці байдуже, що саме летить: TNT, FPV-дрон чи балістична ракета — їй потрібні
 * лише позиція, власник і тип. Завдяки цьому нові типи снарядів додаються в одному місці
 * ({@link AaTargetScanner}), а логіка наведення не змінюється взагалі.
 */
public class AaTarget {

    public enum Type {
        VANILLA_TNT,
        FPV_DRONE,
        BALLISTIC_MISSILE,
        GUIDED_MISSILE,
        ELYTRA_PLAYER
    }

    private final Entity entity;
    private final Type type;
    private final CombatPrincipal principal;

    public AaTarget(Entity entity, Type type, UUID owner) {
        this(entity, type, owner == null ? null : CombatPrincipal.player(owner));
    }

    public AaTarget(Entity entity, Type type, CombatPrincipal principal) {
        this.entity = entity;
        this.type = type;
        this.principal = principal;
    }

    public Entity entity() {
        return entity;
    }

    public Type type() {
        return type;
    }

    public UUID owner() {
        return principal == null ? null : principal.id();
    }

    public CombatPrincipal principal() {
        return principal;
    }

    public UUID id() {
        return entity.getUniqueId();
    }

    public Location location() {
        return entity.getLocation();
    }

    /** Швидкість цілі за тік — потрібна перехоплювачу для стрільби на випередження. */
    public org.bukkit.util.Vector velocity() {
        return entity.getVelocity();
    }

    /** Точка прицілювання — трохи вище за низ hitbox'а, щоб перехоплювач не пірнав під ціль. */
    public Location aimPoint() {
        return entity.getLocation().add(0, 0.4D, 0);
    }

    public boolean isValid() {
        return entity.isValid() && !entity.isDead();
    }
}
