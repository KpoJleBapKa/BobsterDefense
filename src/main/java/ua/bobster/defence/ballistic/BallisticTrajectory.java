package ua.bobster.defence.ballistic;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * Математика траєкторії — і більше нічого. Решта систем нею користується, не знаючи,
 * як саме вона влаштована.
 * <p>
 * Крива кубічна Безьє з контрольними точками строго над стартом і над ціллю:
 * <pre>
 *        P1                    P2
 *        │                     │
 *        │      ╭────────╮     │
 *        │   ╭──╯        ╰──╮  │
 *        P0                   P3
 *     старт                  ціль
 * </pre>
 * Саме це дає потрібну форму: вертикальний відрив, плавна дуга і круте пікірування —
 * без ручного «доворотання» вектора швидкості. І ракета гарантовано приходить у ціль,
 * бо кінець кривої — сама точка удару.
 */
public class BallisticTrajectory {

    private final World world;
    private final Vector start;
    private final Vector startControl;
    private final Vector targetControl;
    private final Vector end;
    private final double length;

    private BallisticTrajectory(World world, Vector start, Vector startControl,
                                Vector targetControl, Vector end, double length) {
        this.world = world;
        this.start = start;
        this.startControl = startControl;
        this.targetControl = targetControl;
        this.end = end;
        this.length = length;
    }

    /**
     * @param apexMin   мінімальна висота дуги над хордою
     * @param apexPer   приріст висоти на кожен блок дистанції
     * @param apexMax   стеля дуги
     * @param worldMaxY стеля світу, вище якої ракету піднімати не можна
     */
    public static BallisticTrajectory between(Location from, Location to,
                                              double apexMin, double apexPer, double apexMax,
                                              int worldMaxY) {
        Vector start = from.toVector();
        Vector end = to.toVector();
        double distance = start.distance(end);
        double apex = Math.clamp(distance * apexPer, apexMin, apexMax);

        // Контрольні точки підняті над кінцями, а не над серединою: саме тому старт
        // виходить вертикальним, а підліт до цілі — крутим пікіруванням.
        double ceiling = worldMaxY - 4.0D;
        double startTop = Math.min(start.getY() + apex * 1.4D, ceiling);
        double endTop = Math.min(end.getY() + apex * 1.4D, ceiling);

        Vector startControl = new Vector(start.getX(), startTop, start.getZ());
        Vector targetControl = new Vector(end.getX(), endTop, end.getZ());

        double length = approximateLength(start, startControl, targetControl, end);
        return new BallisticTrajectory(from.getWorld(), start, startControl, targetControl, end, length);
    }

    /** @param progress 0.0 — старт, 1.0 — точка удару */
    public Location pointAt(double progress) {
        double t = Math.clamp(progress, 0.0D, 1.0D);
        double inv = 1.0D - t;
        double a = inv * inv * inv;
        double b = 3 * inv * inv * t;
        double c = 3 * inv * t * t;
        double d = t * t * t;
        return new Location(world,
                a * start.getX() + b * startControl.getX() + c * targetControl.getX() + d * end.getX(),
                a * start.getY() + b * startControl.getY() + c * targetControl.getY() + d * end.getY(),
                a * start.getZ() + b * startControl.getZ() + c * targetControl.getZ() + d * end.getZ());
    }

    /**
     * Наскільки просунутися по кривій, щоб пройти вказану кількість блоків.
     * Довжина кривої постійна, тому швидкість у блоках за тік переводиться в приріст progress.
     */
    public double advance(double blocksPerTick) {
        return blocksPerTick / Math.max(1.0D, length);
    }

    public double advanceFrom(double progress, double blocksPerTick) {
        double t = Math.clamp(progress, 0.0D, 1.0D);
        double inv = 1.0D - t;
        Vector derivative = startControl.clone().subtract(start).multiply(3.0D * inv * inv);
        derivative.add(targetControl.clone().subtract(startControl).multiply(6.0D * inv * t));
        derivative.add(end.clone().subtract(targetControl).multiply(3.0D * t * t));
        double delta = Math.min(1.0D - t, blocksPerTick / Math.max(1.0E-6D, derivative.length()));
        if (delta <= 0.0D) {
            return 0.0D;
        }
        double actual = pointAt(t).distance(pointAt(t + delta));
        if (actual > 1.0E-6D) {
            delta = Math.min(1.0D - t, delta * blocksPerTick / actual);
        }
        return delta;
    }

    public double length() {
        return length;
    }

    /** Довжина ламаної по контрольному багатокутнику — для таймінгу цього достатньо. */
    private static double approximateLength(Vector p0, Vector p1, Vector p2, Vector p3) {
        double chord = p0.distance(p3);
        double control = p0.distance(p1) + p1.distance(p2) + p2.distance(p3);
        return (chord + control) / 2.0D;
    }
}
