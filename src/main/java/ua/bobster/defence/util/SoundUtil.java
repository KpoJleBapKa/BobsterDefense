package ua.bobster.defence.util;

import org.bukkit.Sound;
import org.bukkit.plugin.Plugin;

/**
 * Розбір назв звуків із config.yml.
 */
public final class SoundUtil {

    private SoundUtil() {
    }

    /**
     * @param raw      назва з конфігу, у формі ENTITY_BEE_LOOP або entity.bee.loop
     * @param fallback що взяти, якщо назва невідома
     */
    public static Sound resolve(Plugin plugin, String raw, Sound fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Sound.valueOf(raw.trim().toUpperCase().replace('.', '_'));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Невідомий звук '" + raw + "', використано " + fallback.name());
            return fallback;
        }
    }
}
