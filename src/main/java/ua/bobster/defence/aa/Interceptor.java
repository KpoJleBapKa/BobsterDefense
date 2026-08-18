package ua.bobster.defence.aa;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * Керований перехоплювач — справжня палаюча стріла, якій щотік підправляють вектор
 * у бік розрахункової точки зустрічі з ціллю.
 * <p>
 * Два моменти, без яких воно не працює проти балістики:
 * <ul>
 *   <li>швидкість цілі рахуємо самі, за зміщенням між тіками. Hitbox ракети й дрона
 *       <b>телепортується</b>, а не рухається фізикою, тому {@code getVelocity()} у них
 *       майже нульовий — і випередження не працювало зовсім;</li>
 *   <li>влучання перевіряємо по <b>відрізку</b> шляху за тік, а не по кінцевій точці.
 *       Перехоплювач долає 3–6 блоків за тік і легко «перестрибував» ціль, жодного разу
 *       не опинившись у радіусі влучання.</li>
 * </ul>
 * Разом це й давало картину «стріла летить за ракетою, але ніяк не збиває».
 */
public class Interceptor {

    private final Arrow arrow;
    private final AaTarget target;
    private final UUID launcherOwner;
    private final double speed;
    private final double hitDistance;
    private final int maxTicks;
    private final boolean flameTrail;
    private final boolean smokeTrail;

    private Vector lastTargetPosition;
    private Vector targetVelocity = new Vector();
    private int ticks;

    Interceptor(Arrow arrow, AaTarget target, UUID launcherOwner, double speed, double hitDistance,
                int maxTicks, boolean flameTrail, boolean smokeTrail) {
        this.arrow = arrow;
        this.target = target;
        this.launcherOwner = launcherOwner;
        this.speed = speed;
        this.hitDistance = hitDistance;
        this.maxTicks = maxTicks;
        this.flameTrail = flameTrail;
        this.smokeTrail = smokeTrail;
        this.lastTargetPosition = target.aimPoint().toVector();
    }

    public AaTarget target() {
        return target;
    }

    public UUID launcherOwner() {
        return launcherOwner;
    }

    public Arrow arrow() {
        return arrow;
    }

    /** @return LOST — ціль зникла, HIT — влучили, FLYING — летимо далі */
    Result tick() {
        if (!arrow.isValid() || arrow.isDead()) {
            return Result.LOST;
        }
        if (!target.isValid()) {
            arrow.remove();
            return Result.LOST;
        }
        if (++ticks > maxTicks) {
            arrow.remove();
            return Result.LOST;
        }
        if (arrow.isInBlock()) {
            arrow.remove();
            return Result.LOST;
        }

        Vector aim = target.aimPoint().toVector();
        updateTargetVelocity(aim);

        Vector position = arrow.getLocation().toVector();
        if (position.distance(aim) <= hitDistance) {
            return Result.HIT;
        }

        Vector step = aimVector(position, aim).multiply(speed);
        // Свіп: ціль могла опинитися посеред відрізка, який ми пройдемо цього тіку.
        if (distanceToSegment(aim, position, position.clone().add(step)) <= hitDistance) {
            return Result.HIT;
        }

        arrow.setVelocity(step);
        trail();
        return Result.FLYING;
    }

    /**
     * Ціль телепортується, тому швидкість беремо як зміщення за тік і трохи згладжуємо —
     * інакше один ривок телепорту смикав би все наведення.
     */
    private void updateTargetVelocity(Vector aim) {
        Vector delta = aim.clone().subtract(lastTargetPosition);
        lastTargetPosition = aim.clone();
        if (delta.lengthSquared() > 64 * 64) {
            return; // стрибок через телепорт/зміну світу — не швидкість
        }
        targetVelocity.multiply(0.5D).add(delta.multiply(0.5D));
    }

    /**
     * Точка зустрічі: розв'язуємо, за скільки тіків перехоплювач дістане ціль, що рухається,
     * і летимо туди, а не туди, де ціль зараз. Двох ітерацій вистачає — швидкість цілі
     * майже стала між тіками.
     */
    private Vector aimVector(Vector position, Vector aim) {
        Vector predicted = aim.clone();
        for (int i = 0; i < 2; i++) {
            double time = position.distance(predicted) / speed;
            predicted = aim.clone().add(targetVelocity.clone().multiply(time));
        }
        Vector direction = predicted.subtract(position);
        double length = direction.length();
        if (length < 1.0E-4D) {
            direction = aim.clone().subtract(position);
            length = Math.max(1.0E-4D, direction.length());
        }
        return direction.multiply(1.0D / length);
    }

    /** Найкоротша відстань від точки до відрізка — щоб ловити прольоти «наскрізь». */
    private static double distanceToSegment(Vector point, Vector from, Vector to) {
        Vector segment = to.clone().subtract(from);
        double lengthSq = segment.lengthSquared();
        if (lengthSq < 1.0E-9D) {
            return point.distance(from);
        }
        double t = Math.clamp(point.clone().subtract(from).dot(segment) / lengthSq, 0.0D, 1.0D);
        return point.distance(from.clone().add(segment.multiply(t)));
    }

    private void trail() {
        World world = arrow.getWorld();
        Location location = arrow.getLocation();
        if (flameTrail) {
            world.spawnParticle(Particle.FLAME, location, 3, 0.05D, 0.05D, 0.05D, 0.0D);
        }
        if (smokeTrail) {
            world.spawnParticle(Particle.SMOKE, location, 2, 0.08D, 0.08D, 0.08D, 0.0D);
        }
    }

    void cleanup() {
        if (arrow.isValid()) {
            arrow.remove();
        }
    }

    enum Result {
        FLYING,
        HIT,
        LOST
    }
}
