package com.ifuto.heartsmp.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.player.PlayerData;
import com.ifuto.heartsmp.player.Rank;

/** /rank &lt;player&gt; &lt;default|VIP|VIP+&gt; */
public final class RankCommand implements CommandExecutor, TabCompleter {

    private final HeartSMP plugin;

    public RankCommand(HeartSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("heartsmp.admin")) {
            plugin.messages().error(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("/rank <player> <default|VIP|VIP+>"));
            return true;
        }
        Rank rank = Rank.parse(args[1]);
        if (rank == null) {
            plugin.messages().error(sender, "rank-invalid");
            return true;
        }
        OfflinePlayer target = findPlayer(args[0]);
        if (target == null) {
            plugin.messages().error(sender, "player-not-found", "player", args[0]);
            return true;
        }
        PlayerData data = plugin.playerData().get(target);
        data.rank(rank);
        plugin.playerData().markDirty(target.getUniqueId());
        plugin.playerData().saveSync();
        plugin.messages().send(sender, "rank-set",
                "player", target.getName() == null ? args[0] : target.getName(),
                "rank", rank.display(plugin));
        return true;
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) {
                return offline;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> ranks = new ArrayList<>();
            for (String option : new String[]{"default", "VIP", "VIP+"}) {
                if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    ranks.add(option);
                }
            }
            return ranks;
        }
        return List.of();
    }
}
