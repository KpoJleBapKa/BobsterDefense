package ua.bobster.defence.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.drone.DroneType;
import ua.bobster.defence.strike.StrikeLauncherItem;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /drone — усе про FPV-дрони.
 * <p>
 * Раніше це була підкоманда /ppo, хоч дрони до ППО стосунку не мають: ППО їх збиває,
 * а не запускає. Тепер у дронів своя команда.
 */
public class DroneCommand implements CommandExecutor, TabCompleter {

    private final BobsterDefence plugin;
    private final StrikeLauncherCommand launcherCommand;

    public DroneCommand(BobsterDefence plugin) {
        this.plugin = plugin;
        this.launcherCommand = new StrikeLauncherCommand(plugin, plugin.strike());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "list", "info" -> handleList(sender);
            case "give" -> handleGive(sender, args);
            case "launcher" -> launcherCommand.execute(sender, StrikeLauncherItem.Kind.DRONE, java.util.Arrays.copyOfRange(args, 1, args.length));
            default -> send(sender, "drone-usage", Map.of());
        }
        return true;
    }

    /** /drone list — які моделі є та їхні характеристики. */
    private void handleList(CommandSender sender) {
        String header = plugin.message("drone-list-header");
        if (!header.isEmpty()) {
            sender.sendMessage(MessageUtil.parse(header));
        }
        for (DroneType type : plugin.drones().types()) {
            sender.sendMessage(MessageUtil.parse(plugin.message("drone-list-line"), Map.of(
                    "id", type.id(),
                    "name", MessageUtil.raw(type.displayName()),
                    "distance", type.maxDistance(),
                    "time", type.flightTime(),
                    "power", type.explosionPower(),
                    "damage", (int) Math.round(type.playerDamage()),
                    "speed", type.speed())));
        }
    }

    /** /drone give &lt;тип&gt; [нік] [кількість] */
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bobsterdefence.drone.give")) {
            send(sender, "no-permission", Map.of());
            return;
        }
        if (args.length < 2) {
            send(sender, "drone-usage", Map.of());
            return;
        }
        DroneType type = plugin.drones().type(args[1]);
        if (type == null) {
            send(sender, "drone-unknown-type", Map.of("type", args[1]));
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
            send(sender, "drone-usage", Map.of());
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.clamp(Integer.parseInt(args[3]), 1, 64);
            } catch (NumberFormatException ex) {
                send(sender, "drone-usage", Map.of());
                return;
            }
        }

        ItemStack item = plugin.droneItem().create(type, amount);
        target.getInventory().addItem(item).values()
                .forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        send(sender, "drone-given", Map.of(
                "name", MessageUtil.raw(type.displayName()),
                "player", target.getName(),
                "amount", amount));
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
            for (String option : List.of("list", "give", "launcher")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("launcher")) {
            return launcherCommand.tab(sender, StrikeLauncherItem.Kind.DRONE, java.util.Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (DroneType type : plugin.drones().types()) {
                if (type.id().startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(type.id());
                }
            }
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    result.add(online.getName());
                }
            }
        }
        return result;
    }
}
