package com.armourrestricted;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RestrictedCommand implements CommandExecutor, TabCompleter {

    private final ArmourRestricted plugin;

    public RestrictedCommand(ArmourRestricted plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> {
                if (sender instanceof Player p) p.openBook(plugin.buildBook(p));
                else sender.sendMessage("Players only.");
            }
            case "book" -> {
                if (sender instanceof Player p) plugin.giveBook(p);
                else sender.sendMessage("Players only.");
            }
            case "progress" -> {
                if (sender instanceof Player p) {
                    for (String line : plugin.progressLines(p)) p.sendMessage(ArmourRestricted.MM.deserialize(line));
                } else sender.sendMessage("Players only.");
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reloadAll();
                sender.sendMessage(ArmourRestricted.MM.deserialize("<green>Armour Restricted reloaded."));
            }
            case "setlevel" -> {
                if (!admin(sender)) return true;
                if (args.length < 3) {
                    sender.sendMessage(ArmourRestricted.MM.deserialize("<red>/armourrestricted setlevel <player> <0-4>"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    sender.sendMessage(ArmourRestricted.MM.deserialize("<red>Player not online."));
                    return true;
                }
                try {
                    plugin.setLevel(t, Integer.parseInt(args[2]));
                    sender.sendMessage(ArmourRestricted.MM.deserialize("<green>Level set."));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ArmourRestricted.MM.deserialize("<red>Level must be a number."));
                }
            }
            case "reset" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(ArmourRestricted.MM.deserialize("<red>/armourrestricted reset <player>"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    sender.sendMessage(ArmourRestricted.MM.deserialize("<red>Player not online."));
                    return true;
                }
                plugin.setLevel(t, 0);
                sender.sendMessage(ArmourRestricted.MM.deserialize("<green>Player reset."));
            }
            default -> sender.sendMessage(ArmourRestricted.MM.deserialize("<yellow>/armourrestricted [book|progress|reload|setlevel|reset]"));
        }
        return true;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("armourrestricted.admin")) return true;
        s.sendMessage(ArmourRestricted.MM.deserialize("<red>No permission."));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("book", "progress"));
            if (sender.hasPermission("armourrestricted.admin")) out.addAll(List.of("reload", "setlevel", "reset"));
        } else if (args.length == 2 && sender.hasPermission("armourrestricted.admin")
                && (args[0].equalsIgnoreCase("setlevel") || args[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
