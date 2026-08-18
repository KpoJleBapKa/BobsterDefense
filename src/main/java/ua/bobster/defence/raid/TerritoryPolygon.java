package ua.bobster.defence.raid;

import java.util.List;

/**
 * Одна частина території: зовнішнє кільце плюс отвори, вирізані іншими територіями.
 * <p>
 * Точки зберігаються у вигляді пласких масивів координат — так дешевше рахувати
 * і геть немає зайвих об'єктів у гарячому циклі перевірки.
 */
public class TerritoryPolygon {

    private final double[] outerX;
    private final double[] outerZ;
    private final List<double[][]> holes;

    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    public TerritoryPolygon(double[] outerX, double[] outerZ, List<double[][]> holes) {
        this.outerX = outerX;
        this.outerZ = outerZ;
        this.holes = holes;

        double lowX = Double.MAX_VALUE;
        double highX = -Double.MAX_VALUE;
        double lowZ = Double.MAX_VALUE;
        double highZ = -Double.MAX_VALUE;
        for (int i = 0; i < outerX.length; i++) {
            lowX = Math.min(lowX, outerX[i]);
            highX = Math.max(highX, outerX[i]);
            lowZ = Math.min(lowZ, outerZ[i]);
            highZ = Math.max(highZ, outerZ[i]);
        }
        this.minX = lowX;
        this.maxX = highX;
        this.minZ = lowZ;
        this.maxZ = highZ;
    }

    public boolean contains(double x, double z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        if (!inRing(outerX, outerZ, x, z)) {
            return false;
        }
        for (double[][] hole : holes) {
            if (inRing(hole[0], hole[1], x, z)) {
                return false; // точка в дірці — це вже не наша територія
            }
        }
        return true;
    }

    /** @return 0, якщо точка всередині; інакше відстань до найближчої межі */
    public double distanceTo(double x, double z) {
        if (contains(x, z)) {
            return 0.0D;
        }
        double best = ringDistance(outerX, outerZ, x, z);
        for (double[][] hole : holes) {
            best = Math.min(best, ringDistance(hole[0], hole[1], x, z));
        }
        return best;
    }

    /**
     * Швидкий відсів по описаному прямокутнику: якщо точка далі за radius навіть від рамки,
     * рахувати відстані до всіх ребер немає сенсу.
     */
    public boolean withinBoundingBox(double x, double z, double radius) {
        return x >= minX - radius && x <= maxX + radius
                && z >= minZ - radius && z <= maxZ + radius;
    }

    private static boolean inRing(double[] xs, double[] zs, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            boolean straddles = (zs[i] > z) != (zs[j] > z);
            if (straddles && x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double ringDistance(double[] xs, double[] zs, double x, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            best = Math.min(best, segmentDistance(x, z, xs[j], zs[j], xs[i], zs[i]));
        }
        return best;
    }

    private static double segmentDistance(double px, double pz,
                                          double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq < 1.0E-9D) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = Math.clamp(((px - ax) * dx + (pz - az) * dz) / lengthSq, 0.0D, 1.0D);
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }
}
