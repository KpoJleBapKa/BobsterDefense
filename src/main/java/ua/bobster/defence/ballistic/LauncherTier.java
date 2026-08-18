package ua.bobster.defence.ballistic;

import java.util.List;

/**
 * Один рівень пускової установки (MK-I … MK-V), повністю описаний у config.yml.
 *
 * @param id              ключ у конфізі, напр. "mk3"
 * @param level           порядковий номер (1..5) — від нього залежить складність перехоплення
 * @param displayName     назва предмета, MiniMessage
 * @param range           максимальна дальність, блоків
 * @param ammo            скільки TNT списується з диспенсера за постріл
 * @param explosionPower  сила одного вибуху (4.0 ≈ звичайний TNT)
 * @param hitsToIntercept скільки влучань ППО треба, щоб збити ракету
 * @param airburstPower   сила вибуху, якщо ракету збили в повітрі
 * @param cooldownSeconds перезарядка між пострілами
 */
public record LauncherTier(String id,
                           int level,
                           String displayName,
                           int range,
                           int ammo,
                           double explosionPower,
                           int hitsToIntercept,
                           double airburstPower,
                           int cooldownSeconds,
                           double speed,
                           boolean losesControlWhenHit,
                           List<String> lore,
                           String rocketName,
                           List<String> rocketLore) {
}
