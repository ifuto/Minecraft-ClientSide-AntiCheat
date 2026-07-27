package com.anticheat.server;

import org.bukkit.Bukkit;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Evolution: Discord webhook for instant violation alerts.
 */
public final class DiscordWebhook {

    private final String webhookUrl;

    public DiscordWebhook(String url) {
        this.webhookUrl = url;
    }

    public void send(String title, String description, int color) {
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("change-me")) return;

        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("AnticheatServer"), () -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String json = String.format("{\"embeds\":[{\"title\":\"%s\",\"description\":\"%s\",\"color\":%d,\"timestamp\":\"%s\"}]}",
                        escape(title), escape(description), color, java.time.Instant.now().toString());

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                // System.out.println("Webhook response " + code);
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private String escape(String s) {
        return s.replace("\"", "\\\"").replace("\n", "\\n");
    }
}
