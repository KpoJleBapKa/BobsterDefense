package ua.bobster.defence.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import ua.bobster.defence.BobsterDefence;

import java.util.UUID;

public class CombatIdentity {

    private final NamespacedKey ownerKey;
    private final NamespacedKey ownerTypeKey;

    public CombatIdentity(BobsterDefence plugin) {
        this.ownerKey = plugin.ownerKey();
        this.ownerTypeKey = new NamespacedKey(plugin, "owner_type");
    }

    public void write(PersistentDataContainer container, CombatPrincipal principal) {
        container.set(ownerKey, PersistentDataType.STRING, principal.id().toString());
        container.set(ownerTypeKey, PersistentDataType.STRING, principal.type().name());
    }

    public CombatPrincipal read(PersistentDataContainer container) {
        String rawId = container.get(ownerKey, PersistentDataType.STRING);
        if (rawId == null) {
            return null;
        }
        try {
            UUID id = UUID.fromString(rawId);
            String rawType = container.get(ownerTypeKey, PersistentDataType.STRING);
            CombatPrincipalType type = rawType == null ? CombatPrincipalType.PLAYER : CombatPrincipalType.valueOf(rawType);
            return new CombatPrincipal(id, type);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public NamespacedKey ownerTypeKey() {
        return ownerTypeKey;
    }
}
