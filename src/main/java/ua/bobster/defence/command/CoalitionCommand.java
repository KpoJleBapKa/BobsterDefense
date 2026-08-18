package ua.bobster.defence.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.aa.TeamRegistry;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * /coalition — свої й чужі. Гравці збирають коаліції самі, без адміна й правок конфігу.
 * <p>
 * По снарядах союзників не стріляє ППО, і над їхніми територіями не вмикається тривога.
 */
public class CoalitionCommand implements CommandExecutor, TabCompleter {

    private final BobsterDefence plugin;
    private final TeamRegistry teams;

    public CoalitionCommand(BobsterDefence plugin, TeamRegistry teams) {
        this.plugin = plugin;
        this.teams = teams;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "coalition-players-only", Map.of());
            return true;
        }
        if (!player.hasPermission("bobsterdefence.coalition.use")) {
            send(sender, "no-permission", Map.of());
            return true;
        }
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "create" -> create(player, args);
            case "add", "invite" -> add(player, args);
            case "kick", "remove" -> kick(player, args);
            case "leave" -> leave(player);
            case "disband", "delete" -> disband(player);
            case "info", "list" -> info(player);
            default -> send(player, "coalition-usage", Map.of());
        }
        return true;
    }

    private void create(Player player, String[] args) {
        if (args.length < 2) {
            send(player, "coalition-usage", Map.of());
            return;
        }
        String name = args[1];
        if (name.length() > 24) {
            send(player, "coalition-name-too-long", Map.of());
            return;
        }
        if (teams.byName(name) != null) {
            send(player, "coalition-exists", Map.of("name", name));
            return;
        }
        if (teams.of(player.getUniqueId()) != null) {
            send(player, "coalition-already-member", Map.of());
            return;
        }
        TeamRegistry.Coalition coalition = teams.create(name, player.getUniqueId());
        send(player, "coalition-created", Map.of("name", coalition.name()));
    }

    private void add(Player player, String[] args) {
        TeamRegistry.Coalition coalition = requireOwned(player);
        if (coalition == null) {
            return;
        }
        if (args.length < 2) {
            send(player, "coalition-usage", Map.of());
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            send(player, "unknown-player", Map.of("player", args[1]));
            return;
        }
        if (teams.of(target.getUniqueId()) != null) {
            send(player, "coalition-target-busy", Map.of("player", args[1]));
            return;
        }
        teams.add(coalition, target.getUniqueId());
        send(player, "coalition-added", Map.of("player", args[1], "name", coalition.name()));

        Player online = target.getPlayer();
        if (online != null) {
            send(online, "coalition-you-joined", Map.of("name", coalition.name()));
        }
    }

    private void kick(Player player, String[] args) {
        TeamRegistry.Coalition coalition = requireOwned(player);
        if (coalition == null) {
            return;
        }
        if (args.length < 2) {
            send(player, "coalition-usage", Map.of());
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null || !teams.remove(coalition, target.getUniqueId())) {
            send(player, "coalition-not-member", Map.of("player", args[1]));
            return;
        }
        send(player, "coalition-removed", Map.of("player", args[1], "name", coalition.name()));
    }

    private void leave(Player player) {
        TeamRegistry.Coalition coalition = teams.of(player.getUniqueId());
        if (coalition == null) {
            send(player, "coalition-none", Map.of());
            return;
        }
        if (coalition.isOwner(player.getUniqueId())) {
            send(player, "coalition-owner-cannot-leave", Map.of());
            return;
        }
        teams.remove(coalition, player.getUniqueId());
        send(player, "coalition-left", Map.of("name", coalition.name()));
    }

    private void disband(Player player) {
        TeamRegistry.Coalition coalition = requireOwned(player);
        if (coalition == null) {
            return;
        }
        teams.disband(coalition);
        send(player, "coalition-disbanded", Map.of("name", coalition.name()));
    }

    private void info(Player player) {
        TeamRegistry.Coalition coalition = teams.of(player.getUniqueId());
        if (coalition == null) {
            send(player, "coalition-none", Map.of());
            return;
        }
        StringJoiner members = new StringJoiner(", ");
        for (UUID member : coalition.members()) {
            String name = plugin.getServer().getOfflinePlayer(member).getName();
            members.add(name == null ? member.toString().substring(0, 8) : name);
        }
        String ownerName = coalition.owner() == null ? "—"
                : String.valueOf(plugin.getServer().getOfflinePlayer(coalition.owner()).getName());
        send(player, "coalition-info", Map.of(
                "name", coalition.name(),
                "owner", ownerName,
                "count", coalition.members().size(),
                "members", members.toString()));
    }

    /** @return коаліція гравця, якщо він її власник; інакше надсилає причину і повертає null */
    private TeamRegistry.Coalition requireOwned(Player player) {
        TeamRegistry.Coalition coalition = teams.of(player.getUniqueId());
        if (coalition == null) {
            send(player, "coalition-none", Map.of());
            return null;
        }
        if (!coalition.isOwner(player.getUniqueId())) {
            send(player, "coalition-not-owner", Map.of("name", coalition.name()));
            return null;
        }
        return coalition;
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline != null && offline.hasPlayedBefore() ? offline : null;
    }

    private void send(CommandSender sender, String key, Map<String, ?> placeholders) {
        String raw = plugin.message(key);
        if (raw.isEmpty()) {
            return;
        }
        sender.sendMessage(MessageUtil.parse(plugin.prefix() + raw, placeholders));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("create", "add", "kick", "leave", "disband", "info")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && List.of("add", "kick", "remove", "invite")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
        }
        return result;
    }
}
