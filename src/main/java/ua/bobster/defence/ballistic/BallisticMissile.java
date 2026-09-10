package ua.bobster.defence.ballistic;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Trident;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import ua.bobster.defence.combat.CombatPrincipal;
import ua.bobster.defence.missile.MissilePayload;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Балістична ракета в польоті.
 * <p>
 * Поруч летить невидима стійка-hitbox. Вона потрібна рівно для одного: стріли з лука
 * <b>не стикаються зі снарядами</b>, тож у корпус влучити неможливо, і ручне ППО проти
 * балістики без неї просто перестало б працювати. Автоматична ППО в ній не має потреби —
 * її перехоплювачі рахують відстань самі.
 */
public class BallisticMissile {

    private final BallisticManager manager;
    private final LauncherTier tier;
    private final BallisticTrajectory trajectory;
    private final UUID missileId;
    private final CombatPrincipal owner;
    private final MissilePayload payload;
    private final Location impact;
    private final Trident trident;
    private final ArmorStand hitbox;

    private Location previous;
    private Vector heading;
    private MissileState state = MissileState.LAUNCH;
    private double progress;
    private double travelled;
    private int ticks;
    private int hits;
    private boolean ending;

    private boolean damaged;
    private boolean damagedNotified;
    private Vector fallVelocity;

    /** Чанки, які ракета тримає завантаженими. Ключ — {@code (x << 32) | z}. */
    private final Set<Long> tickets = new HashSet<>();

    BallisticMissile(BallisticManager manager, LauncherTier tier, BallisticTrajectory trajectory,
                     CombatPrincipal owner, MissilePayload payload, Location start, Location impact) {
        this.manager = manager;
        this.tier = tier;
        this.trajectory = trajectory;
        this.missileId = UUID.randomUUID();
        this.owner = owner;
        this.payload = payload;
        this.impact = impact;
        this.previous = start.clone();
        this.heading = new Vector(0, 1, 0);

        World world = start.getWorld();
        this.trident = world.spawn(start, Trident.class, missile -> {
            missile.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            missile.setPersistent(false);
            missile.setCritical(false);
            missile.setSilent(true);
            missile.setGravity(false);
            missile.setInvulnerable(true);
            missile.setDamage(0.0D);
            missile.setVelocity(new Vector(0, 0.1D, 0));
            manager.tagMissile(missile.getPersistentDataContainer(), missileId, owner, tier, impact);
        });
        this.hitbox = world.spawn(start, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setPersistent(false);
            stand.setSilent(true);
            stand.getPersistentDataContainer().set(
                    manager.hitboxKey(), PersistentDataType.BYTE, (byte) 1);
            manager.plugin().combatIdentity().write(stand.getPersistentDataContainer(), owner);
        });
        holdChunks(start);
    }

    // ─────────────────────────────── доступ ───────────────────────────────

    public LauncherTier tier() {
        return tier;
    }

    public UUID missileId() {
        return missileId;
    }

    public UUID shooter() {
        return owner.id();
    }

    public CombatPrincipal owner() {
        return owner;
    }

    public MissilePayload payload() {
        return payload;
    }

    public Vector heading() {
        return heading.clone();
    }

    /** Сутність, по якій наводиться ППО. */
    public Trident entity() {
        return trident;
    }

    public ArmorStand hitbox() {
        return hitbox;
    }

    public Location impact() {
        return impact;
    }

    public MissileState state() {
        return state;
    }

    public boolean isEnding() {
        return ending;
    }

    public boolean interceptable() {
        return ticks >= manager.interceptionGraceTicks();
    }

    public Location currentLocation() {
        return trident.isValid() ? trident.getLocation() : previous.clone();
    }

    public boolean isDamaged() {
        return damaged;
    }

    /** Пошкоджена ракета вибухає вдвічі слабше. */
    public double powerMultiplier() {
        return damaged ? 0.5D : 1.0D;
    }

    public int hitsLeft() {
        return Math.max(0, tier.hitsToIntercept() - hits);
    }

    /** @return true лише при першому виклику — щоб не спамити стрільцю однаковим повідомленням */
    public boolean markDamagedNotified() {
        if (damagedNotified) {
            return false;
        }
        damagedNotified = true;
        return true;
    }

    boolean beginEnding() {
        if (ending) {
            return false;
        }
        ending = true;
        return true;
    }

    // ────────────────────────────── влучання ──────────────────────────────

    /**
     * Влучання ППО.
     * <p>
     * Важкі ракети з одного влучання не збиваються, але й неушкодженими не лишаються:
     * після першого влучання вони втрачають керування, гальмують і починають падати,
     * а бойова частина спрацьовує лише наполовину.
     *
     * @return true, якщо ракету збито саме цим влучанням
     */
    public boolean registerHit() {
        hits++;
        if (hits >= tier.hitsToIntercept()) {
            return true;
        }
        if (tier.losesControlWhenHit() && !damaged) {
            damaged = true;
            Vector remains = heading.clone().normalize().multiply(tier.speed() * 0.25D);
            remains.setY(0);
            fallVelocity = remains;
        }
        return false;
    }

    // ─────────────────────────────── політ ───────────────────────────────

    /** @return false, якщо ракета завершила політ і її треба прибрати з реєстру */
    boolean tick() {
        if (ending) {
            return false;
        }
        if (!trident.isValid() || trident.isDead()) {
            manager.detonate(this, currentLocation(), false);
            return false;
        }
        ticks++;
        if (ticks > manager.maxFlightTicks()) {
            manager.detonate(this, currentLocation(), false);
            return false;
        }

        Location next = damaged ? fall() : advance();
        travelled += previous.distance(next);
        state = damaged ? MissileState.DESCENT : manager.stateFor(progress, travelled);

        if (ticks > 3) {
            Location obstacle = terrainHit(previous, next);
            if (obstacle != null && (damaged || manager.detonateOnTerrain())) {
                manager.detonate(this, obstacle, false);
                return false;
            }
        }
        if (next.getY() < next.getWorld().getMinHeight()) {
            manager.detonate(this, next, false);
            return false;
        }
        // Удар зараховуємо і за близькістю до цілі: у пікіруванні один тік — це кілька блоків,
        // і без цієї перевірки ракета могла б проскочити точку удару.
        if (!damaged && (progress >= 1.0D
                || next.distanceSquared(impact) <= manager.impactRadius() * manager.impactRadius())) {
            manager.detonate(this, impact, false);
            return false;
        }

        move(next);
        holdChunks(next);
        manager.effects().exhaust(trident.getLocation(), heading, state, ticks);
        manager.effects().engine(trident.getLocation(), ticks);
        previous = next;
        return true;
    }

    /** Просування по кривій зі швидкістю поточної фази. */
    private Location advance() {
        double blocksPerTick = manager.speedFor(state, tier);
        progress += state == MissileState.LAUNCH ? trajectory.advanceFrom(progress, blocksPerTick) : trajectory.advance(blocksPerTick);
        return trajectory.pointAt(progress);
    }

    /** Некерований політ після влучання: гальмування по горизонталі й вільне падіння. */
    private Location fall() {
        fallVelocity.multiply(0.94D);
        fallVelocity.setY(Math.max(-3.5D, fallVelocity.getY() - 0.08D));
        return previous.clone().add(fallVelocity);
    }

    private void move(Location next) {
        Location actual = trident.getLocation();
        Vector delta = next.toVector().subtract(actual.toVector());
        if (delta.lengthSquared() > 1.0E-6D) {
            heading = delta.clone().normalize();
        }
        trident.setVelocity(delta);
        hitbox.teleport(next.clone().subtract(0, 0.4D, 0));
    }

    /**
     * Тримає завантаженими чанк під ракетою і кілька наступних по курсу.
     * <p>
     * Без цього ракета, пущена за пару кілометрів, летить у ніким не завантажений світ:
     * сутність там просто перестає існувати, і замість удару виходить тиша. Тримаємо саме
     * <b>по курсу</b>, а не колом навколо — колом на швидкості 3 бл/тік довелося б вантажити
     * удесятеро більше чанків заради того самого результату.
     * <p>
     * Квиток видається плагіном, тому сервер сам звільнить чанки, якщо плагін вимкнуть.
     */
    private void holdChunks(Location at) {
        if (!manager.chunkLoading()) {
            return;
        }
        World world = at.getWorld();
        Location ahead = at.clone().add(heading.clone().multiply(manager.chunkLookAhead()));
        Set<Long> wanted = new HashSet<>();
        collect(wanted, at);
        collect(wanted, ahead);

        for (Long key : wanted) {
            if (tickets.add(key)) {
                manager.retainChunk(world, key);
            }
        }
        // Пройдені чанки відпускаємо одразу: інакше далекий постріл лишав би за собою
        // смугу завантаженого світу на всю дистанцію польоту.
        tickets.removeIf(key -> {
            if (wanted.contains(key)) {
                return false;
            }
            manager.releaseChunk(world, key);
            return true;
        });
    }

    private void collect(Set<Long> into, Location at) {
        int radius = manager.chunkRadius();
        int centerX = at.getBlockX() >> 4;
        int centerZ = at.getBlockZ() >> 4;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                into.add(((long) x << 32) | (z & 0xFFFFFFFFL));
            }
        }
    }

    private void releaseChunks() {
        if (tickets.isEmpty()) {
            return;
        }
        World world = previous.getWorld();
        for (Long key : tickets) {
            manager.releaseChunk(world, key);
        }
        tickets.clear();
    }

    private Location terrainHit(Location from, Location to) {
        World world = from.getWorld();
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length < 1.0E-4D) {
            return null;
        }
        RayTraceResult result = world.rayTraceBlocks(from, delta.multiply(1.0D / length), length, FluidCollisionMode.NEVER, true);
        return result == null ? null : result.getHitPosition().toLocation(world);
    }

    void cleanup() {
        manager.camera().onMissileEnd(this);
        releaseChunks();
        trident.remove();
        hitbox.remove();
    }

    Trident detachSpent() {
        manager.camera().onMissileEnd(this);
        releaseChunks();
        hitbox.remove();
        return trident;
    }
}
