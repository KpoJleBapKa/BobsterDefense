package ua.bobster.defence.chest;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import ua.bobster.defence.BobsterDefence;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Прибирає скрині зі списку блоків, які має знести вибух.
 * Вибух при цьому відбувається нормально — просто скриня лишається цілою.
 * Ламання руками/інструментом не зачіпається взагалі.
 */
public class ChestProtectionListener implements Listener {

    private final BobsterDefence plugin;

    private boolean enabled;
    private Set<Material> protectedMaterials = Collections.emptySet();

    public ChestProtectionListener(BobsterDefence plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("chest-protection.enabled", true);

        Set<Material> materials = EnumSet.noneOf(Material.class);
        if (plugin.getConfig().getBoolean("chest-protection.chest", true)) {
            materials.add(Material.CHEST);
        }
        if (plugin.getConfig().getBoolean("chest-protection.trapped-chest", true)) {
            materials.add(Material.TRAPPED_CHEST);
        }
        for (String raw : plugin.getConfig().getStringList("chest-protection.extra-blocks")) {
            Material material = Material.matchMaterial(raw);
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning("chest-protection.extra-blocks: невідомий блок '" + raw + "'");
                continue;
            }
            materials.add(material);
        }
        this.protectedMaterials = materials;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event.blockList());
    }

    private void protect(List<Block> blocks) {
        if (!enabled || protectedMaterials.isEmpty() || blocks.isEmpty()) {
            return;
        }
        // Обидві половини подвійної скрині — окремі блоки CHEST, тож обробляються самі собою.
        blocks.removeIf(block -> protectedMaterials.contains(block.getType()));
    }

    public boolean isEnabled() {
        return enabled;
    }
}
