package com.ifuto.heartsmp.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.util.Msg;

/** /send &lt;player&gt; と /bagages */
public final class DeliveryCommand implements CommandExecutor, TabCompleter {

    private final HeartSMP plugin;

    public DeliveryCommand(HeartSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().error(sender, "player-only");
            return true;
        }
        if (command.getName().equalsIgnoreCase("bagages")) {
            plugin.deliveryGui().openBagages(player);
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(Msg.parse("<#eb4034>/send <player>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.messages().error(player, "unknown-player-online", "player", args[0]);
            return true;
        }
        plugin.deliveryGui().openSend(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || command.getName().equalsIgnoreCase("bagages")) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(online.getName());
            }
        }
        return result;
    }
}
