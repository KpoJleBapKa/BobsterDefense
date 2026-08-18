package ua.bobster.defence.util;

/**
 * Дрібниці, спільні для всіх display-сутностей плагіна.
 */
public final class DisplayUtil {

    /** Одиниця view range у Minecraft означає 64 блоки. */
    private static final double BLOCKS_PER_UNIT = 64.0D;

    private DisplayUtil() {
    }

    /**
     * Переводить бажану дальність видимості в блоках у значення для
     * {@code Display#setViewRange}.
     * <p>
     * Клієнт додатково множить це на свій повзунок «Entity Distance», тож на 50%
     * плашка зникне вдвічі ближче. Це особливість display-сутностей, а не помилка:
     * задати жорстку дальність у блоках з боку сервера неможливо.
     */
    public static float viewRange(double blocks) {
        return (float) Math.max(0.01D, blocks / BLOCKS_PER_UNIT);
    }
}
