package ua.bobster.defence.drone;

import org.bukkit.Material;

import java.util.List;

/**
 * Модель дрона. Кожен тип має власний крафт, ліміти й бойову частину.
 *
 * @param speed              множник швидкості (1.0 ≈ звичайний spectator)
 * @param maxDistance        дальність зв'язку, блоків
 * @param flightTime         час польоту, секунд
 * @param explosionPower     сила вибуху по блоках (4.0 ≈ ванільний TNT)
 * @param playerDamage       додаткова шкода гравцям у епіцентрі, 0 = без бонусу
 * @param playerDamageRadius радіус тієї додаткової шкоди
 */
public record DroneType(String id,
                        String displayName,
                        Material material,
                        double speed,
                        double boostSpeed,
                        int maxDistance,
                        int flightTime,
                        double explosionPower,
                        double playerDamage,
                        double playerDamageRadius,
                        List<String> lore) {
}
