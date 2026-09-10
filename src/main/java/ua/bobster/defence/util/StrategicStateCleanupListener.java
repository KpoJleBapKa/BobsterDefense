package ua.bobster.defence.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import ua.bobster.defence.BobsterDefence;

public class StrategicStateCleanupListener implements Listener {

    private final NamespacedKey citizenKey;

    public StrategicStateCleanupListener(BobsterDefence plugin) {
        citizenKey = new NamespacedKey(plugin, "state_citizen");
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        event.getEntities().stream()
                .filter(this::isStateCitizen)
                .forEach(Entity::remove);
    }

    private boolean isStateCitizen(Entity entity) {
        return entity.getPersistentDataContainer().has(citizenKey);
    }
}
