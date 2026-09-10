package ua.bobster.defence.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ua.bobster.defence.BobsterDefence;
import ua.bobster.defence.strike.StrikeLauncherItem;
import ua.bobster.defence.util.MessageUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RocketCommand implements CommandExecutor, TabCompleter {

    private final BobsterDefence plugin;
    private final StrikeLauncherCommand launcherCommand;

    public RocketCommand(BobsterDefence plugin) {
        this.plugin = plugin;
        this.launcherCommand = new StrikeLauncherCommand(plugin, plugin.strike());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("camera") || args[0].equalsIgnoreCase("view")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(MessageUtil.parse(plugin.prefix() + plugin.message("ballistic-players-only")));
                return true;
            }
            plugin.ballistic().camera().toggle(player);
            return true;
        }
        return launcherCommand.execute(sender, StrikeLauncherItem.Kind.GUIDED, args);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            ArrayList<String> result = new ArrayList<>(launcherCommand.tab(sender, StrikeLauncherItem.Kind.GUIDED, args));
            if ("camera".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                result.add("camera");
            }
            return result;
        }
        return launcherCommand.tab(sender, StrikeLauncherItem.Kind.GUIDED, args);
    }
}
