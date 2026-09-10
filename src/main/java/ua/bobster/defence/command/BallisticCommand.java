package ua.bobster.defence.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.ballistic.BallisticLauncher;
import ua.bobster.defence.ballistic.BallisticListener;
import ua.bobster.defence.ballistic.BallisticLauncherRegistry;
import ua.bobster.defence.ballistic.BallisticManager;
import ua.bobster.defence.ballistic.LauncherTier;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /ballistic — наведення, запуск і видача установок.
 * Усі підкоманди працюють з установкою, по якій гравець останній раз клікнув.
 */
public class BallisticCommand implements CommandExecutor, TabCompleter {

    private final BobsterDefence plugin;
    private final BallisticManager manager;
    private final BallisticListener listener;

    public BallisticCommand(BobsterDefence plugin, BallisticManager manager, BallisticListener listener) {
        this.plugin = plugin;
        this.manager = manager;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            send(sender, "ballistic-usage", Map.of());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "target" -> handleTarget(sender, args);
            case "launch" -> handleLaunch(sender);
            case "info" -> handleInfo(sender);
            case "list" -> handleList(sender, args);
            case "clear_list" -> handleClearList(sender);
            case "select" -> handleSelect(sender, args);
            case "give" -> handleGive(sender, args);
            case "rocket" -> handleRocket(sender, args);
            case "mark" -> handleMark(sender);
            default -> send(sender, "ballistic-usage", Map.of());
        }
        return true;
    }

    /** /ballistic target &lt;x&gt; &lt;z&gt; | here */
    private void handleTarget(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "bobsterdefence.ballistic.use");
        if (player == null) {
            return;
        }
        List<BallisticLauncher> launchers = requireLaunchers(player);
        if (launchers.isEmpty()) {
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
                send(sender, "ballistic-usage", Map.of());
                return;
            }
        } else {
            send(sender, "ballistic-usage", Map.of());
            return;
        }
        for (BallisticLauncher launcher : launchers) {
            listener.applyTarget(player, launcher, x, z);
        }
    }

    private void handleLaunch(CommandSender sender) {
        Player player = requirePlayer(sender, "bobsterdefence.ballistic.use");
        if (player == null) {
            return;
        }
        List<BallisticLauncher> launchers = requireLaunchers(player);
        if (launchers.isEmpty()) {
            return;
        }
        int launched = 0;
        for (BallisticLauncher launcher : launchers) {
            LauncherTier tier = manager.tier(launcher.tierId());
            if (tier == null) {
                continue;
            }
            String error = manager.validate(launcher, tier);
            if (error != null) {
                send(sender, error, Map.of("range", tier.range(), "need", tier.ammo(), "cooldown", tier.cooldownSeconds()));
                continue;
            }
            manager.launch(player, launcher, tier);
            launched++;
        }
        send(sender, "ballistic-launch-group", Map.of("count", launched, "selected", launchers.size()));
    }

    private void handleInfo(CommandSender sender) {
        Player player = requirePlayer(sender, "bobsterdefence.ballistic.use");
        if (player == null) {
            return;
        }
        List<BallisticLauncher> launchers = requireLaunchers(player);
        if (launchers.isEmpty()) {
            return;
        }
        for (BallisticLauncher launcher : launchers) {
            LauncherTier tier = manager.tier(launcher.tierId());
            if (tier == null) {
                continue;
            }
            int distance = launcher.hasTarget() ? (int) Math.round(manager.horizontalDistance(launcher.block().getLocation(), launcher.targetX(), launcher.targetZ())) : 0;
            BallisticLauncherRegistry.Entry entry = manager.registry().at(launcher.block());
            send(sender, "ballistic-info-numbered", Map.of(
                    "number", entry == null ? "—" : entry.number(),
                    "name", MessageUtil.raw(tier.displayName()),
                    "x", launcher.hasTarget() ? launcher.targetX() : "—",
                    "z", launcher.hasTarget() ? launcher.targetZ() : "—",
                    "distance", distance,
                    "range", tier.range(),
                    "ammo", launcher.ammoCount(tier),
                    "need", tier.ammo(),
                    "power", tier.explosionPower(),
                    "hits", tier.hitsToIntercept()));
        }
    }

    private void handleList(CommandSender sender, String[] args) {
        Player viewer = requirePlayer(sender, "bobsterdefence.ballistic.use");
        if (viewer == null) {
            return;
        }
        OfflinePlayer owner = viewer;
        if (args.length >= 2 && !args[1].equalsIgnoreCase(viewer.getName())) {
            if (!viewer.hasPermission("bobsterdefence.ballistic.list.others")) {
                send(sender, "no-permission", Map.of());
                return;
            }
            owner = Bukkit.getOfflinePlayer(args[1]);
            if (!owner.isOnline() && !owner.hasPlayedBefore()) {
                send(sender, "unknown-player", Map.of("player", args[1]));
                return;
            }
        }
        List<BallisticLauncherRegistry.Entry> entries = manager.registry().owned(owner.getUniqueId());
        String ownerName = owner.getName();
        if (ownerName == null) {
            ownerName = args.length >= 2 ? args[1] : viewer.getUniqueId().toString();
        }
        send(sender, "ballistic-list-header", Map.of("player", ownerName, "count", entries.size()));
        for (BallisticLauncherRegistry.Entry entry : entries) {
            BallisticLauncher launcher = manager.launcherAt(entry.block(false));
            LauncherTier tier = launcher == null ? null : manager.tier(launcher.tierId());
            send(sender, "ballistic-list-entry", Map.of("number", entry.number(), "location", entry.locationText(), "type", tier == null ? "unloaded" : MessageUtil.raw(tier.displayName())));
        }
    }

    private void handleSelect(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "bobsterdefence.ballistic.use");
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            send(sender, "ballistic-select-usage", Map.of());
            return;
        }
        List<BallisticLauncherRegistry.Entry> selected = new ArrayList<>();
        if (args[1].equalsIgnoreCase("none")) {
            manager.clearSelection(player);
            send(sender, "ballistic-selected-none", Map.of());
            return;
        } else if (args[1].equalsIgnoreCase("all")) {
            selected.addAll(manager.registry().owned(player.getUniqueId()));
        } else {
            for (int index = 1; index < args.length; index++) {
                try {
                    BallisticLauncherRegistry.Entry entry = manager.registry().owned(player.getUniqueId(), Integer.parseInt(args[index]));
                    if (entry == null) {
                        send(sender, "ballistic-select-missing", Map.of("number", args[index]));
                        return;
                    }
                    selected.add(entry);
                } catch (NumberFormatException ex) {
                    send(sender, "ballistic-select-usage", Map.of());
                    return;
                }
            }
        }
        if (selected.isEmpty()) {
            send(sender, "ballistic-list-empty", Map.of());
            return;
        }
        manager.selectLaunchers(player, selected);
        send(sender, "ballistic-selected-many", Map.of("count", selected.size(), "numbers", selected.stream().map(entry -> String.valueOf(entry.number())).collect(java.util.stream.Collectors.joining(", "))));
    }

    private void handleClearList(CommandSender sender) {
        Player player = requirePlayer(sender, "bobsterdefence.ballistic.use");
        if (player == null) {
            return;
        }
        int removed = manager.clearLaunchers(player.getUniqueId());
        manager.forget(player.getUniqueId());
        send(sender, "ballistic-list-cleared", Map.of("count", removed));
    }

    /** /ballistic give &lt;mk1..mk5&gt; [нік] */
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.ballistic.give")) {
            send(sender, "no-permission", Map.of());
            return;
        }
        if (args.length < 2) {
            send(sender, "ballistic-usage", Map.of());
            return;
        }
        LauncherTier tier = manager.tier(args[1]);
        if (tier == null) {
            send(sender, "ballistic-unknown-tier", Map.of("tier", args[1]));
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                send(sender, "unknown-player", Map.of("player", args[2]));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            send(sender, "ballistic-usage", Map.of());
            return;
        }
        target.getInventory().addItem(manager.item().create(tier, 1))
                .values()
                .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        send(sender, "ballistic-given", Map.of("name", MessageUtil.raw(tier.displayName()), "player", target.getName()));
    }

    /** /ballistic rocket &lt;тип&gt; [кількість] [нік] */
    private void handleRocket(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.ballistic.give")) {
            send(sender, "no-permission", Map.of());
            return;
        }
        if (args.length < 2) {
            send(sender, "ballistic-usage", Map.of());
            return;
        }
        LauncherTier tier = manager.tier(args[1]);
        if (tier == null) {
            send(sender, "ballistic-unknown-tier", Map.of("tier", args[1]));
            return;
        }
        int amount = 4;
        if (args.length >= 3) {
            try {
                amount = Math.clamp(Integer.parseInt(args[2]), 1, 64);
            } catch (NumberFormatException ex) {
                send(sender, "ballistic-usage", Map.of());
                return;
            }
        }
        Player target;
        if (args.length >= 4) {
            target = Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                send(sender, "unknown-player", Map.of("player", args[3]));
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            send(sender, "ballistic-usage", Map.of());
            return;
        }
        target.getInventory().addItem(manager.item().createRocket(tier, amount))
                .values()
                .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        send(sender, "ballistic-given", Map.of(
                "name", MessageUtil.raw(tier.rocketName()), "player", target.getName()));
    }

    /**
     * /ballistic mark — призначити ціль по блоку під прицілом розвідника.
     * Гарантований спосіб: у spectator клік по блоку до сервера доходить не завжди.
     */
    private void handleMark(CommandSender sender) {
        Player player = requirePlayer(sender, "bobsterdefence.ballistic.use");
        if (player == null) {
            return;
        }
        if (!manager.recon().markTarget(player)) {
            send(sender, "recon-not-flying", Map.of());
        }
    }

    private Player requirePlayer(CommandSender sender, String permission) {
        if (!(sender instanceof Player player)) {
            send(sender, "ballistic-players-only", Map.of());
            return null;
        }
        if (!player.hasPermission(permission)) {
            send(sender, "no-permission", Map.of());
            return null;
        }
        return player;
    }

    private BallisticLauncher requireLauncher(Player player) {
        Block selected = manager.selected(player);
        BallisticLauncher launcher = selected == null ? null : manager.launcherAt(selected);
        if (launcher == null) {
            send(player, "ballistic-not-selected", Map.of());
            return null;
        }
        return launcher;
    }

    private List<BallisticLauncher> requireLaunchers(Player player) {
        List<BallisticLauncher> launchers = manager.selectedLaunchers(player);
        if (launchers.isEmpty()) {
            send(player, "ballistic-not-selected", Map.of());
        }
        return launchers;
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
            for (String option : List.of("target", "launch", "info", "list", "clear_list", "select", "mark", "give", "rocket")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("select") && sender instanceof Player player) {
            result.add("all");
            result.add("none");
            for (BallisticLauncherRegistry.Entry entry : manager.registry().owned(player.getUniqueId())) {
                result.add(String.valueOf(entry.number()));
            }
            return result;
        }
        if (args.length == 2
                && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("rocket"))) {
            for (LauncherTier tier : manager.tiers()) {
                if (tier.id().startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(tier.id());
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("target")) {
            result.add("here");
            if (sender instanceof Player player) {
                result.add(String.valueOf(player.getLocation().getBlockX()));
            }
        }
        return result;
    }
}
