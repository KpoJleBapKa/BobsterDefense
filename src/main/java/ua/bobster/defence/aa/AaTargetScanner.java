package ua.bobster.defence.aa;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import ua.bobster.defence.BobsterDefence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Пошук цілей у зоні установки: що літає, чиє воно і чи видно його з установки.
 */
public class AaTargetScanner {

    private final BobsterDefence plugin;
    private final TeamRegistry teams;

    private boolean targetTnt;
    private boolean targetDrones;
    private boolean targetMissiles;
    private boolean friendlyFire;
    private boolean requireLineOfSight;

    public AaTargetScanner(BobsterDefence plugin, TeamRegistry teams) {
        this.plugin = plugin;
        this.teams = teams;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        this.targetTnt = config.getBoolean("air-defence-system.targeting.tnt", true);
        this.targetDrones = config.getBoolean("air-defence-system.targeting.fpv-drone", true);
        this.targetMissiles = config.getBoolean("air-defence-system.targeting.ballistic-missile", true);
        this.friendlyFire = config.getBoolean("air-defence-system.targeting.friendly-fire", false);
        this.requireLineOfSight = config.getBoolean("air-defence-system.targeting.require-line-of-sight", true);
    }

    /**
     * @param center   центр зони (сама установка)
     * @param accept   чи можна ще брати цю ціль — вирішує менеджер за кількістю перехоплювачів,
     *                 що вже летять до неї
     * @param limit    скільки цілей потрібно
     * @return цілі, відсортовані від найближчої
     */
    public List<AaTarget> scan(Location center, AaTier tier, UUID launcherOwner,
                               Predicate<AaTarget> accept, int limit) {
        World world = center.getWorld();
        if (world == null) {
            return List.of();
        }
        List<AaTarget> found = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(center, tier.range(), tier.verticalRange(), tier.range())) {
            AaTarget target = classify(entity);
            if (target == null || !target.isValid() || !accept.test(target)) {
                continue;
            }
            if (!friendlyFire && teams.friendly(launcherOwner, target.owner())) {
                continue;
            }
            if (!inZone(center, target.location(), tier)) {
                continue;
            }
            if (requireLineOfSight && !hasLineOfSight(center, target)) {
                continue;
            }
            found.add(target);
        }
        // v1: пріоритет — найближча ціль. Час до удару можна врахувати пізніше.
        found.sort(Comparator.comparingDouble(target -> center.distanceSquared(target.location())));
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    /**
     * Зона контролю — <b>циліндр</b>, а не куля: радіус {@code range} по горизонталі
     * і {@code vertical-range} угору та вниз.
     * <p>
     * Раніше тут стояла тривимірна відстань, тобто зона була кулею радіусом {@code range},
     * а {@code vertical-range} не робив нічого. Для ППО це найгірша форма: балістика йде
     * високо, і саме вгорі зони бракувало найбільше.
     */
    private boolean inZone(Location center, Location target, AaTier tier) {
        double dx = target.getX() - center.getX();
        double dz = target.getZ() - center.getZ();
        if (dx * dx + dz * dz > (double) tier.range() * tier.range()) {
            return false;
        }
        return Math.abs(target.getY() - center.getY()) <= tier.verticalRange();
    }

    /** @return ціль потрібного типу або null, якщо ця сутність ППО не цікавить */
    public AaTarget classify(Entity entity) {
        if (targetTnt && entity instanceof TNTPrimed tnt) {
            return new AaTarget(entity, AaTarget.Type.VANILLA_TNT, ownerOfTnt(tnt));
        }
        UUID tagged = taggedOwner(entity);
        if (targetDrones && plugin.drones() != null && plugin.drones().sessionByDrone(entity) != null) {
            return new AaTarget(entity, AaTarget.Type.FPV_DRONE, tagged);
        }
        if (targetMissiles && plugin.ballistic() != null && plugin.ballistic().projectileOf(entity) != null) {
            return new AaTarget(entity, AaTarget.Type.BALLISTIC_MISSILE, tagged);
        }
        return null;
    }

    /** Наші снаряди носять UUID власника прямо в PDC — саме на цьому тримається «свій/чужий». */
    private UUID taggedOwner(Entity entity) {
        String raw = entity.getPersistentDataContainer()
                .get(plugin.ownerKey(), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private UUID ownerOfTnt(TNTPrimed tnt) {
        UUID tagged = taggedOwner(tnt);
        if (tagged != null) {
            return tagged;
        }
        return tnt.getSource() instanceof Player player ? player.getUniqueId() : null;
    }

    /**
     * Установка не має стріляти крізь гору. Через це ППО доводиться ставити на дахах і баштах,
     * а не ховати в підвалі — і це навмисне.
     */
    public boolean hasLineOfSight(Location from, AaTarget target) {
        World world = from.getWorld();
        Vector delta = target.aimPoint().toVector().subtract(from.toVector());
        double distance = delta.length();
        if (distance < 1.0E-3D) {
            return true;
        }
        return world.rayTraceBlocks(from, delta.multiply(1.0D / distance), distance,
                FluidCollisionMode.NEVER, true) == null;
    }
}
