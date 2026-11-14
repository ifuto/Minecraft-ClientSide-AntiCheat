package com.ifuto.heartsmp.tpa;

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

/** /tpa /tpahere /tpaccept /tpadeny /tpacancel */
public final class TpaCommand implements CommandExecutor, TabCompleter {

    private final HeartSMP plugin;

    public TpaCommand(HeartSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().error(sender, "player-only");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "tpa", "tpahere" -> {
                if (args.length < 1) {
                    player.sendMessage(com.ifuto.heartsmp.util.Msg.parse(
                            "<#eb4034>/" + name + " <player>"));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    plugin.messages().error(player, "unknown-player-online", "player", args[0]);
                    return true;
                }
                plugin.tpa().request(player, target,
                        name.equals("tpa") ? TpaManager.Type.TPA : TpaManager.Type.TPA_HERE);
            }
            case "tpaccept" -> plugin.tpa().accept(player, args.length > 0 ? args[0] : null);
            case "tpadeny" -> plugin.tpa().deny(player, args.length > 0 ? args[0] : null);
            case "tpacancel" -> plugin.tpa().cancel(player, args.length > 0 ? args[0] : null);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(sender) && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(online.getName());
            }
        }
        return result;
    }
}
