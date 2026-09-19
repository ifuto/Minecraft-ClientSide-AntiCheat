package dev.ifuto.mcsa.server.alert;

import dev.ifuto.mcsa.server.McsaConfig;
import dev.ifuto.mcsa.server.McsaPlugin;
import dev.ifuto.mcsa.server.report.ClientReport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** コンソール / OP / Webhook への通知。 */
public final class AlertService {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final McsaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AlertService(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    public static Component legacy(String message) {
        return LEGACY.deserialize(message == null ? "" : message);
    }

    /** レポート受信時の通知。フラグが無ければ何もしない。 */
    public void onReport(Player player, ClientReport report) {
        if (report.flags().isEmpty()) {
            return;
        }
        String text = "[MCSA] " + player.getName() + " → " + report.flagSummary();
        if (plugin.config().alertConsole) {
            plugin.getLogger().warning(text);
        }
        if (plugin.config().alertBroadcast) {
            broadcast(Component.text("[MCSA] ", NamedTextColor.GOLD)
                    .append(Component.text(player.getName() + " ", NamedTextColor.YELLOW))
                    .append(Component.text("→ " + report.flagSummary(),
                            report.hasCritical() ? NamedTextColor.RED : NamedTextColor.GRAY)));
        }
        webhook(text);
    }

    /** 行動検知の通知。 */
    public void check(Player player, String checkId, String detail, int violationLevel) {
        String text = "[MCSA/" + checkId + "] " + player.getName() + " VL=" + violationLevel + " " + detail;
        if (plugin.config().alertConsole) {
            plugin.getLogger().warning(text);
        }
        if (plugin.config().alertBroadcast) {
            broadcast(Component.text("[MCSA/" + checkId + "] ", NamedTextColor.GOLD)
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                    .append(Component.text(" VL=" + violationLevel + " " + detail, NamedTextColor.RED)));
        }
        webhook(text);
    }

    public void info(Player player, String message) {
        if (plugin.config().alertConsole) {
            plugin.getLogger().info("[MCSA] " + player.getName() + " " + message);
        }
    }

    private void broadcast(Component message) {
        String permission = plugin.config().alertPermission;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (permission == null || permission.isEmpty() || online.hasPermission(permission)) {
                online.sendMessage(message);
            }
        }
    }

    private void webhook(String text) {
        McsaConfig config = plugin.config();
        if (config.webhook.isEmpty()) {
            return;
        }
        String body = "{\"content\":" + quote(text) + "}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(config.webhook))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException | RuntimeException e) {
                plugin.getLogger().warning("Webhook の送信に失敗しました: " + e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
