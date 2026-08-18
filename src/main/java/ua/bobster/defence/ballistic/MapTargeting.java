package ua.bobster.defence.ballistic;

import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.util.Vector;

/**
 * Наведення по карті в рамці.
 * <p>
 * Ключ до всього — {@code PlayerInteractAtEntityEvent#getClickedPosition()}: подія повертає
 * точну точку кліку по сутності, а не просто «клікнув по рамці». Тобто ми знаємо, у який
 * піксель карти влучив гравець, і через {@link MapView} (центр + масштаб) переганяємо це
 * в світові координати. Жодних ресурспаків чи модів — командний пункт зі стіни карт.
 * <pre>
 *   ┌──────────────┐
 *   │   .          │  ← клік сюди
 *   │      ▓▓      │
 *   │              │   →  X: 1250   Z: -830
 *   └──────────────┘
 * </pre>
 * Підтримуються рамки на вертикальних стінах — у них орієнтація зображення однозначна.
 */
public final class MapTargeting {

    /** Результат наведення: світові координати або причина відмови. */
    public record Result(boolean success, int x, int z, String errorKey) {

        static Result fail(String errorKey) {
            return new Result(false, 0, 0, errorKey);
        }

        static Result of(int x, int z) {
            return new Result(true, x, z, null);
        }
    }

    private MapTargeting() {
    }

    /** Вішає на карту рендерер хрестика, щоб ціль було видно прямо на ній. */
    public static void attachRenderer(ItemFrame frame, BallisticManager manager) {
        if (frame.getItem().getItemMeta() instanceof MapMeta meta && meta.getMapView() != null) {
            TargetMapRenderer.attach(meta.getMapView(), manager);
        }
    }

    public static Result resolve(ItemFrame frame, Vector clickedPosition) {
        ItemStack content = frame.getItem();
        if (content.getType() != Material.FILLED_MAP) {
            return Result.fail("ballistic-map-not-a-map");
        }
        if (!(content.getItemMeta() instanceof MapMeta meta) || meta.getMapView() == null) {
            return Result.fail("ballistic-map-not-a-map");
        }
        MapView view = meta.getMapView();

        BlockFace facing = frame.getFacing();
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            return Result.fail("ballistic-map-wall-only");
        }

        // Базис площини рамки: n — назовні зі стіни, v — вгору, u — «вправо» з точки зору того,
        // хто дивиться на карту.
        Vector normal = new Vector(facing.getModX(), facing.getModY(), facing.getModZ());
        Vector up = new Vector(0, 1, 0);
        Vector right = up.getCrossProduct(normal);

        // Рахуємо від центру блока, а не від позиції сутності: у рамок вона зміщена до стіни.
        Block block = frame.getLocation().getBlock();
        Vector center = new Vector(block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
        Vector relative = frame.getLocation().toVector().add(clickedPosition).subtract(center);

        double alongRight = relative.dot(right);
        double alongUp = relative.dot(up);

        int displayX = (int) Math.clamp(Math.floor((alongRight + 0.5D) * 128.0D), 0, 127);
        int displayY = (int) Math.clamp(Math.floor((0.5D - alongUp) * 128.0D), 0, 127);

        int[] pixel = unrotate(displayX, displayY, frame.getRotation());

        int blocksPerPixel = 1 << view.getScale().getValue();
        int worldX = view.getCenterX() + (pixel[0] - 64) * blocksPerPixel;
        int worldZ = view.getCenterZ() + (pixel[1] - 64) * blocksPerPixel;
        return Result.of(worldX, worldZ);
    }

    /**
     * Рамка може бути повернута кроками по 45°. Зображення карти повертається разом із нею,
     * тож координати кліку треба відкрутити назад. Кроки по 45° округлюємо до найближчих 90°.
     */
    private static int[] unrotate(int displayX, int displayY, Rotation rotation) {
        int steps = (rotation.ordinal() / 2) % 4;
        return switch (steps) {
            case 1 -> new int[]{displayY, 127 - displayX};
            case 2 -> new int[]{127 - displayX, 127 - displayY};
            case 3 -> new int[]{127 - displayY, displayX};
            default -> new int[]{displayX, displayY};
        };
    }
}
