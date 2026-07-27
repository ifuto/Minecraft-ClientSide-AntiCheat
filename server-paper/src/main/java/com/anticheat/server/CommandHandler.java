package com.anticheat.server;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CommandHandler implements CommandExecutor {

    private final AntiCheatServerPlugin plugin;

    public CommandHandler(AntiCheatServerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("anticheat.admin")) {
            sender.sendMessage("§cNo permission");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§aAnticheatServer v" + plugin.getDescription().getVersion());
            sender.sendMessage("§7/anticheat status - show online players with anticheat status");
            sender.sendMessage("§7/anticheat reload - reload config");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            sender.sendMessage("§aConfig reloaded");
            return true;
        }
        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§a=== Anticheat Status ===");
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                sender.sendMessage("§7" + p.getName() + " ticks=" + p.getTicksLived());
            }
            return true;
        }
        return false;
    }
}
