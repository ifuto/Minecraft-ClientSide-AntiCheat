package com.anticheat.server;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Ban handler abstraction to support both vanilla bans and external ban plugins (LiteBans, AdvancedBan, etc.)
 */
public final class BanHandler {

    private BanHandler() {}

    public static void banPlayer(Player player, String reason, String banCommandTemplate) {
        String playerName = player.getName();
        String uuid = player.getUniqueId().toString();

        // 1. Vanilla ban list
        try {
            BanList banList = Bukkit.getBanList(BanList.Type.NAME);
            banList.addBan(playerName, reason, null, "Anticheat");
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Anticheat] Failed to add to vanilla ban list: " + e.getMessage());
        }

        // 2. Custom command (configurable, supports LiteBans etc)
        if (banCommandTemplate != null && !banCommandTemplate.isEmpty()) {
            String cmd = banCommandTemplate
                    .replace("%player%", playerName)
                    .replace("%uuid%", uuid)
                    .replace("%reason%", reason);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[Anticheat] Failed to dispatch ban command: " + cmd + " error: " + e.getMessage());
            }
        }

        // 3. Kick
        try {
            player.kick(net.kyori.adventure.text.Component.text(reason));
        } catch (Exception e) {
            // fallback legacy kick
            try {
                player.kickPlayer(reason);
            } catch (Exception ex) {
                Bukkit.getLogger().warning("[Anticheat] Failed to kick player " + playerName);
            }
        }
    }
}
