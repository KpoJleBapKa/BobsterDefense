package ua.bobster.defence.ballistic;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Малює хрестик на карті в тому місці, куди наведена установка гравця.
 * <p>
 * Використовуються курсори карти, а не піксельне малювання: курсор — це накладка,
 * тож сама карта не псується й повертається до звичайного вигляду, щойно ціль знята.
 */
public class TargetMapRenderer extends MapRenderer {

    private static final MapCursor.Type CURSOR = MapCursor.Type.TARGET_X;

    private final BallisticManager manager;

    public TargetMapRenderer(BallisticManager manager) {
        super(true); // контекстний: render() викликається окремо для кожного гравця
        this.manager = manager;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        int[] target = manager.marker(player.getUniqueId());
        MapCursorCollection cursors = new MapCursorCollection();
        if (target == null) {
            canvas.setCursors(cursors);
            return;
        }

        int blocksPerPixel = 1 << view.getScale().getValue();
        int pixelX = 64 + Math.floorDiv(target[0] - view.getCenterX(), blocksPerPixel);
        int pixelY = 64 + Math.floorDiv(target[1] - view.getCenterZ(), blocksPerPixel);
        if (pixelX < 0 || pixelX > 127 || pixelY < 0 || pixelY > 127) {
            canvas.setCursors(cursors); // ціль поза цією картою
            return;
        }

        // Координати курсора — у півпікселях від -128 до 127.
        byte cursorX = (byte) Math.clamp(pixelX * 2 - 128, -128, 127);
        byte cursorY = (byte) Math.clamp(pixelY * 2 - 128, -128, 127);
        cursors.addCursor(new MapCursor(cursorX, cursorY, (byte) 0, CURSOR, true));
        canvas.setCursors(cursors);
    }

    /** Додає рендерер до карти, якщо його там ще немає. */
    public static void attach(MapView view, BallisticManager manager) {
        for (MapRenderer renderer : view.getRenderers()) {
            if (renderer instanceof TargetMapRenderer) {
                return;
            }
        }
        view.addRenderer(new TargetMapRenderer(manager));
    }
}
