package ua.bobster.defence.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strike.GuidedMissileType;
import ua.bobster.defence.strike.DroneLauncherRegistry;
import ua.bobster.defence.strike.StrikeLauncherItem;
import ua.bobster.defence.strike.StrikeLauncherManager;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StrikeLauncherCommand {

    private final BobsterDefence plugin;
    private final StrikeLauncherManager manager;

    public StrikeLauncherCommand(BobsterDefence plugin, StrikeLauncherManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean execute(CommandSender sender, StrikeLauncherItem.Kind kind, String[] args) {
        if (args.length == 0) {
            usage(sender, kind);
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            give(sender, kind, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message("ballistic-players-only")));
            return true;
        }
        if (kind == StrikeLauncherItem.Kind.DRONE && args[0].equalsIgnoreCase("list")) {
            list(player, args);
            return true;
        }
        if (kind == StrikeLauncherItem.Kind.DRONE && args[0].equalsIgnoreCase("select")) {
            select(player, args);
            return true;
        }
        if (kind == StrikeLauncherItem.Kind.DRONE && args[0].equalsIgnoreCase("clear_list")) {
            int removed = manager.clearDroneLaunchers(player.getUniqueId());
            manager.clearDroneSelection(player);
            send(player, "strike-drone-list-cleared", Map.of("count", removed));
            return true;
        }
        List<org.bukkit.block.Block> blocks = kind == StrikeLauncherItem.Kind.DRONE ? manager.selectedDroneLaunchers(player) : selectedGuided(player);
        if (blocks.isEmpty()) {
            manager.message(player, "strike-not-selected");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "launch" -> {
                int launched = 0;
                for (org.bukkit.block.Block block : blocks) {
                    if (manager.launch(player, block)) {
                        launched++;
                    }
                }
                if (kind == StrikeLauncherItem.Kind.DRONE && blocks.size() > 1) {
                    send(player, "strike-drone-launch-group", Map.of("count", launched, "selected", blocks.size()));
                }
            }
            case "redstone" -> {
                boolean enabled = args.length >= 2 ? args[1].equalsIgnoreCase("on") : !manager.redstone(blocks.getFirst());
                for (org.bukkit.block.Block block : blocks) {
                    manager.redstone(block, enabled);
                }
                send(player, kind == StrikeLauncherItem.Kind.DRONE && blocks.size() > 1 ? "strike-drone-redstone-group" : enabled ? "strike-redstone-on" : "strike-redstone-off", Map.of("count", blocks.size(), "state", enabled ? "ON" : "OFF"));
            }
            case "target" -> target(player, kind, blocks, args);
            default -> usage(player, kind);
        }
        return true;
    }

    public List<String> tab(CommandSender sender, StrikeLauncherItem.Kind kind, String[] args) {
        ArrayList<String> values = new ArrayList<>();
        if (args.length == 1) {
            values.addAll(kind == StrikeLauncherItem.Kind.DRONE ? List.of("list", "clear_list", "select", "target", "launch", "redstone", "give") : List.of("launch", "redstone", "give"));
        } else if (kind == StrikeLauncherItem.Kind.DRONE && args.length >= 2 && args[0].equalsIgnoreCase("select") && sender instanceof Player player) {
            values.addAll(List.of("all", "none"));
            values.addAll(manager.droneRegistry().owned(player.getUniqueId()).stream().map(entry -> String.valueOf(entry.number())).toList());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("redstone")) {
            values.addAll(List.of("on", "off"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("target") && kind == StrikeLauncherItem.Kind.DRONE) {
            values.add("here");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && kind == StrikeLauncherItem.Kind.GUIDED) {
            values.addAll(List.of("launcher", "corsair", "barrier"));
        } else if (isTargetPosition(kind, args)) {
            values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        values.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(prefix));
        return values;
    }

    private boolean isTargetPosition(StrikeLauncherItem.Kind kind, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            return false;
        }
        return kind == StrikeLauncherItem.Kind.DRONE ? args.length == 2 : args.length == 3;
    }

    private void target(Player player, StrikeLauncherItem.Kind kind, List<org.bukkit.block.Block> blocks, String[] args) {
        if (kind != StrikeLauncherItem.Kind.DRONE) {
            manager.message(player, "strike-guided-no-target");
            return;
        }
        int x;
        int z;
        if (args.length >= 2 && args[1].equalsIgnoreCase("here")) {
            x = player.getLocation().getBlockX();
            z = player.getLocation().getBlockZ();
        } else if (args.length >= 3) {
            try {
                x = Integer.parseInt(args[1]);
                z = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                usage(player, kind);
                return;
            }
        } else {
            usage(player, kind);
            return;
        }
        for (org.bukkit.block.Block block : blocks) {
            manager.target(block, x, z);
        }
        send(player, blocks.size() > 1 ? "strike-drone-target-group" : "strike-target-set", Map.of("x", x, "z", z, "count", blocks.size()));
    }

    private List<org.bukkit.block.Block> selectedGuided(Player player) {
        org.bukkit.block.Block block = manager.selected(player, StrikeLauncherItem.Kind.GUIDED);
        return block == null ? List.of() : List.of(block);
    }

    private void list(Player viewer, String[] args) {
        OfflinePlayer owner = viewer;
        if (args.length >= 2 && !args[1].equalsIgnoreCase(viewer.getName())) {
            if (!viewer.hasPermission("bobsterdefence.strike.list.others")) {
                send(viewer, "no-permission", Map.of());
                return;
            }
            owner = Bukkit.getOfflinePlayer(args[1]);
            if (!owner.isOnline() && !owner.hasPlayedBefore()) {
                send(viewer, "unknown-player", Map.of("player", args[1]));
                return;
            }
        }
        List<DroneLauncherRegistry.Entry> entries = manager.droneRegistry().owned(owner.getUniqueId());
        String ownerName = owner.getName() == null ? owner.getUniqueId().toString() : owner.getName();
        send(viewer, "strike-drone-list-header", Map.of("player", ownerName, "count", entries.size()));
        for (DroneLauncherRegistry.Entry entry : entries) {
            send(viewer, "strike-drone-list-entry", Map.of("number", entry.number(), "location", entry.locationText()));
        }
    }

    private void select(Player player, String[] args) {
        if (args.length < 2) {
            send(player, "strike-drone-select-usage", Map.of());
            return;
        }
        if (args[1].equalsIgnoreCase("none")) {
            manager.clearDroneSelection(player);
            send(player, "strike-drone-selected-none", Map.of());
            return;
        }
        List<DroneLauncherRegistry.Entry> entries = new ArrayList<>();
        if (args[1].equalsIgnoreCase("all")) {
            entries.addAll(manager.droneRegistry().owned(player.getUniqueId()));
        } else {
            for (int index = 1; index < args.length; index++) {
                try {
                    DroneLauncherRegistry.Entry entry = manager.droneRegistry().owned(player.getUniqueId(), Integer.parseInt(args[index]));
                    if (entry == null) {
                        send(player, "strike-drone-select-missing", Map.of("number", args[index]));
                        return;
                    }
                    entries.add(entry);
                } catch (NumberFormatException ex) {
                    send(player, "strike-drone-select-usage", Map.of());
                    return;
                }
            }
        }
        if (entries.isEmpty()) {
            send(player, "strike-drone-list-empty", Map.of());
            return;
        }
        manager.selectDroneLaunchers(player, entries);
        send(player, "strike-drone-selected-many", Map.of("count", entries.size(), "numbers", entries.stream().map(entry -> String.valueOf(entry.number())).collect(java.util.stream.Collectors.joining(", "))));
    }

    private void give(CommandSender sender, StrikeLauncherItem.Kind kind, String[] args) {
        if (!sender.hasPermission("bobsterdefence.strike.give")) {
            sender.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message("no-permission")));
            return;
        }
        int targetIndex = kind == StrikeLauncherItem.Kind.DRONE ? 1 : 2;
        ItemStack result;
        if (kind == StrikeLauncherItem.Kind.DRONE) {
            result = manager.item().createLauncher(kind, 1);
        } else {
            if (args.length < 2) {
                usage(sender, kind);
                return;
            }
            if (args[1].equalsIgnoreCase("launcher")) {
                result = manager.item().createLauncher(kind, 1);
            } else {
                GuidedMissileType type = manager.type(args[1]);
                if (type == null) {
                    sender.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message("strike-guided-unknown-type"), java.util.Map.of("type", args[1])));
                    return;
                }
                result = manager.item().createMissile(type, 1);
            }
        }
        boolean explicitTarget = args.length > targetIndex && !numeric(args[targetIndex]);
        Player target = explicitTarget ? Bukkit.getPlayerExact(args[targetIndex]) : sender instanceof Player player ? player : null;
        if (target == null) {
            sender.sendMessage(MessageUtil.parse(plugin.prefix() + (explicitTarget ? plugin.message("unknown-player") : plugin.message("ballistic-players-only")), java.util.Map.of("player", explicitTarget ? args[targetIndex] : "")));
            return;
        }
        int amountIndex = explicitTarget ? targetIndex + 1 : targetIndex;
        int amount = parseAmount(args, amountIndex);
        result.setAmount(amount);
        target.getInventory().addItem(result).values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        sender.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message("strike-item-given"), java.util.Map.of("amount", amount, "player", target.getName())));
    }

    private int parseAmount(String[] args, int index) {
        if (args.length <= index || !numeric(args[index])) {
            return 1;
        }
        return Math.clamp(Integer.parseInt(args[index]), 1, 64);
    }

    private boolean numeric(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void usage(CommandSender sender, StrikeLauncherItem.Kind kind) {
        String value = kind == StrikeLauncherItem.Kind.DRONE ? "<gray>/drone launcher <list [нік]|clear_list|select none|select all|select 1 2|target <x> <z>|target here|launch|redstone <on|off>|give [нік] [кількість]>" : "<gray>/rocket <launch|redstone <on|off>|give <launcher|corsair|barrier> [нік] [кількість]>";
        sender.sendMessage(MessageUtil.parse(plugin.prefix() + value));
    }

    private void send(CommandSender sender, String key, Map<String, ?> placeholders) {
        sender.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message(key), placeholders));
    }
}
