package ua.bobster.defence.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.bukkit.inventory.ItemStack;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.aa.AaTier;
import ua.bobster.defence.stats.StatsManager;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PpoCommand implements CommandExecutor, TabCompleter {

    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    private final BobsterDefence plugin;

    public PpoCommand(BobsterDefence plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "stats" -> handleStats(sender, args);
            case "top" -> handleTop(sender);
            case "list" -> handleAaList(sender);
            case "give" -> handleAaGive(sender, args);
            case "ammo", "rocket" -> handleAaAmmo(sender, args);
            case "reload" -> handleReload(sender);
            default -> send(sender, plugin.message("usage"));
        }
        return true;
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.use")) {
            send(sender, plugin.message("no-permission"));
            return;
        }
        StatsManager stats = plugin.stats();

        if (args.length >= 2) {
            if (!sender.hasPermission("bobsterdefence.stats.others")) {
                send(sender, plugin.message("no-permission"));
                return;
            }
            String name = args[1];
            OfflinePlayer target = resolve(name);
            if (target == null) {
                send(sender, plugin.message("unknown-player"), Map.of("player", name));
                return;
            }
            sendHeader(sender);
            sendRaw(sender, plugin.message("stats-other"), Map.of(
                    "player", target.getName() == null ? name : target.getName(),
                    "kills", stats.get(target.getUniqueId())));
            sendFooter(sender);
            return;
        }

        if (!(sender instanceof Player player)) {
            handleTop(sender);
            return;
        }
        sendHeader(sender);
        sendRaw(sender, plugin.message("stats-self"), Map.of(
                "player", player.getName(),
                "kills", stats.get(player.getUniqueId())));
        sendFooter(sender);
    }

    private void handleTop(CommandSender sender) {
        if (!sender.hasPermission("bobsterdefence.top")) {
            send(sender, plugin.message("no-permission"));
            return;
        }
        StatsManager stats = plugin.stats();
        int limit = Math.max(1, plugin.getConfig().getInt("stats.top-size", 10));
        List<StatsManager.Entry> top = stats.top(limit);

        sendHeader(sender);
        if (top.isEmpty()) {
            sendRaw(sender, plugin.message("top-empty"), Map.of());
            sendFooter(sender);
            return;
        }
        for (int i = 0; i < top.size(); i++) {
            StatsManager.Entry entry = top.get(i);
            String medal = i < MEDALS.length ? MEDALS[i] : (i + 1) + ".";
            sendRaw(sender, plugin.message("top-line"), Map.of(
                    "medal", medal,
                    "position", i + 1,
                    "player", entry.name(),
                    "kills", entry.kills()));
        }
        sender.sendMessage(Component.empty());
        sendRaw(sender, plugin.message("top-total"), Map.of("total", stats.total()));
        sendFooter(sender);
    }


    // ───────────────────────── стаціонарна ППО ─────────────────────────

    /** /ppo list — установки ППО з характеристиками й назвою потрібної ракети. */
    private void handleAaList(CommandSender sender) {
        String header = plugin.message("aa-list-header");
        if (!header.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(header));
        }
        for (AaTier tier : plugin.aa().tiers()) {
            sender.sendMessage(MessageUtil.parse(plugin.message("aa-list-line"), Map.of(
                    "id", tier.id(),
                    "name", MessageUtil.raw(tier.displayName()),
                    "zone", tier.range() * 2,
                    "targets", tier.maxTargets(),
                    "cooldown", String.format("%.1f", tier.fireCooldown() / 20.0D),
                    "ammo", MessageUtil.raw(tier.ammoName()))));
        }
    }

    /** /ppo give &lt;тип&gt; [нік] — видати установку ППО. */
    private void handleAaGive(CommandSender sender, String[] args) {
        AaTier tier = requireAaTier(sender, args);
        if (tier == null) {
            return;
        }
        Player target = resolveTarget(sender, args, 2);
        if (target == null) {
            return;
        }
        giveItem(target, plugin.aa().item().createLauncher(tier, 1));
        send(sender, plugin.message("aa-given"), Map.of(
                "name", MessageUtil.raw(tier.displayName()), "player", target.getName()));
    }

    /** /ppo ammo &lt;тип&gt; [кількість] [нік] — видати ракети до ППО. */
    private void handleAaAmmo(CommandSender sender, String[] args) {
        AaTier tier = requireAaTier(sender, args);
        if (tier == null) {
            return;
        }
        int amount = 16;
        if (args.length >= 3) {
            try {
                amount = Math.clamp(Integer.parseInt(args[2]), 1, 64);
            } catch (NumberFormatException ex) {
                send(sender, plugin.message("aa-usage"));
                return;
            }
        }
        Player target = resolveTarget(sender, args, 3);
        if (target == null) {
            return;
        }
        giveItem(target, plugin.aa().item().createAmmo(tier, amount));
        send(sender, plugin.message("aa-ammo-given"), Map.of(
                "name", MessageUtil.raw(tier.ammoName()), "amount", amount, "player", target.getName()));
    }

    private AaTier requireAaTier(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.aa.give")) {
            send(sender, plugin.message("no-permission"));
            return null;
        }
        if (args.length < 2) {
            send(sender, plugin.message("aa-usage"));
            return null;
        }
        AaTier tier = plugin.aa().tier(args[1]);
        if (tier == null) {
            send(sender, plugin.message("aa-unknown-tier"), Map.of("tier", args[1]));
        }
        return tier;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                send(sender, plugin.message("unknown-player"), Map.of("player", args[index]));
            }
            return target;
        }
        if (sender instanceof Player self) {
            return self;
        }
        send(sender, plugin.message("aa-usage"));
        return null;
    }

    private void giveItem(Player target, ItemStack stack) {
        target.getInventory().addItem(stack).values()
                .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("bobsterdefence.reload")) {
            send(sender, plugin.message("no-permission"));
            return;
        }
        plugin.reloadAll();
        send(sender, plugin.message("reloaded"));
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline != null && offline.hasPlayedBefore() ? offline : null;
    }

    private void sendHeader(CommandSender sender) {
        String header = plugin.message("stats-header");
        if (!header.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(header));
        }
    }

    private void sendFooter(CommandSender sender) {
        String footer = plugin.message("stats-footer");
        if (!footer.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(footer));
        }
    }

    private void send(CommandSender sender, String raw) {
        send(sender, raw, Map.of());
    }

    private void send(CommandSender sender, String raw, Map<String, ?> placeholders) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        sender.sendMessage(MessageUtil.parse(plugin.prefix() + raw, placeholders));
    }

    /** Рядки всередині таблиць — без префікса, щоб список читався як список. */
    private void sendRaw(CommandSender sender, String raw, Map<String, ?> placeholders) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        sender.sendMessage(MessageUtil.parse(raw, placeholders));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("stats", "top", "list", "give", "ammo", "reload")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("ammo"))) {
            for (AaTier tier : plugin.aa().tiers()) {
                if (tier.id().startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(tier.id());
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")
                && sender.hasPermission("bobsterdefence.stats.others")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(player.getName());
                }
            }
        }
        return result;
    }
}
