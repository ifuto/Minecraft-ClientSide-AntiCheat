package dev.ifuto.mcsa.server.net;

import dev.ifuto.mcsa.server.McsaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.UUID;

/**
 * プラグインメッセージの入口。
 *
 * <p>{@code onPluginMessageReceived} はネットワークスレッドから呼ばれるため、
 * ここで Bukkit API を触ってはいけない。生バイト列をコピーしてメインスレッドに渡す。
 */
public final class ReportListener implements PluginMessageListener {

    private final McsaPlugin plugin;

    public ReportListener(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        byte[] copy = message == null ? new byte[0] : message.clone();
        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player online = plugin.getServer().getPlayer(playerId);
            if (online == null) {
                return;
            }
            switch (channel) {
                case Wire.CH_HELLO -> plugin.sessions().onHello(online, copy);
                case Wire.CH_REPORT -> plugin.sessions().onChunk(online, copy);
                case Wire.CH_SEAL -> plugin.sessions().onSeal(online, copy);
                default -> plugin.getLogger().warning("不明なチャンネル: " + channel);
            }
        });
    }
}
