package ua.bobster.defence.util;

import org.bukkit.entity.Player;
import ua.bobster.defence.BobsterDefence;

import java.util.Map;
import java.util.UUID;

/**
 * Спільне правило для пускових установок і ППО: руками їх ламає лише власник.
 * <p>
 * Ворог не має розбирати чужу батарею киркою — її треба знищувати ракетою чи дроном,
 * і тоді вона не випадає предметом. Це робить оборону чимось, що справді треба продавлювати.
 */
public final class OwnerProtection {

    private OwnerProtection() {
    }

    /**
     * @param owner   власник установки (null — установка «нічия», ставилася до появи власників)
     * @param message ключ повідомлення про відмову
     * @return true, якщо ламати дозволено
     */
    public static boolean mayBreak(BobsterDefence plugin, Player player, UUID owner, String message) {
        if (owner == null
                || owner.equals(player.getUniqueId())
                || player.hasPermission("bobsterdefence.admin.break")) {
            return true;
        }
        // Союзники теж не ламають: коаліції розпадаються, а установки лишаються.
        String ownerName = String.valueOf(plugin.getServer().getOfflinePlayer(owner).getName());
        String raw = plugin.message(message);
        if (!raw.isEmpty()) {
            player.sendMessage(MessageUtil.parse(plugin.prefix() + raw, Map.of("owner", ownerName)));
        }
        return false;
    }

    public static boolean mayUse(BobsterDefence plugin, Player player, UUID owner, String message) {
        if (owner == null || owner.equals(player.getUniqueId()) || player.hasPermission("bobsterdefence.admin.use")) {
            return true;
        }
        String ownerName = String.valueOf(plugin.getServer().getOfflinePlayer(owner).getName());
        String raw = plugin.message(message);
        if (!raw.isEmpty()) {
            player.sendMessage(MessageUtil.parse(plugin.prefix() + raw, Map.of("owner", ownerName)));
        }
        return false;
    }
}
