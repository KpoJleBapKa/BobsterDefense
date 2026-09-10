package ua.bobster.defence.aa;

import java.util.List;

/**
 * Рівень стаціонарної установки ППО.
 *
 * @param range         радіус зони по горизонталі (32 → зона ~64×64)
 * @param verticalRange висота зони контролю над/під установкою
 * @param maxTargets    скільки цілей установка веде одночасно — звідси береться «насичення ППО»
 * @param scanInterval  раз на скільки тіків сканувати зону
 * @param fireCooldown  пауза між пусками, тіків
 * @param speed             швидкість перехоплювача, блоків за тік
 * @param missileHitChance  імовірність, що ця система впорається з балістичною ракетою.
 *                          Кидається один раз на ракету — серії збиттів ні на що не впливають.
 *                          Дронів і TNT не стосується взагалі.
 */
public record AaTier(String id,
                     int level,
                     String displayName,
                     int zoneSize,
                     int range,
                     int verticalRange,
                     int maxTargets,
                     int scanInterval,
                     int fireCooldown,
                     double speed,
                     double missileHitChance,
                     List<String> lore,
                     String ammoName,
                     List<String> ammoLore) {
}
