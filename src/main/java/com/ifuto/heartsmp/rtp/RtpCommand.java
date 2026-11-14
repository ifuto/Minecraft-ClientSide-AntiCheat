package com.ifuto.heartsmp.rtp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.ifuto.heartsmp.HeartSMP;

/** /rtp */
public final class RtpCommand implements CommandExecutor {

    private final HeartSMP plugin;

    public RtpCommand(HeartSMP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().error(sender, "player-only");
            return true;
        }
        if (args.length >= 1) {
            String type = args[0].toLowerCase(java.util.Locale.ROOT);
            if (type.equals("cave")) {
                plugin.rtp().attempt(player, RtpManager.Type.CAVE, null);
                return true;
            }
            if (type.equals("normal")) {
                plugin.rtp().attempt(player, RtpManager.Type.NORMAL, null);
                return true;
            }
        }
        plugin.rtpGui().openMenu(player);
        return true;
    }
}
