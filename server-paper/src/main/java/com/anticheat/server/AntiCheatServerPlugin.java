package com.anticheat.server;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side plugin that receives self-ban packets from client anticheat mod.
 *
 * Channels:
 * - anticheat:violation -> client detected cheat, asks server to ban itself
 * - anticheat:heartbeat -> periodic alive
 * - anticheat:handshake -> client mod presence
 *
 * Security considerations:
 * - Packet is signed with HMAC using shared secret to prevent spoofing by other players to ban someone else.
 *   However, since secret is in client config, determined attacker can extract it. So we also verify player UUID matches sender?
 *   In Bukkit, plugin messages are sent per player connection, so we know which player sent the packet via second arg to onPluginMessageReceived.
 *   So we can trust sender's identity (server knows which player connection the packet came from).
 *   But attacker could still forge packet for themselves to avoid ban? No, they would need to not send packet. So HMAC prevents external entity from forging packets pretending to be another player via server.
 *
 * - Self-ban trust issue: If client can disable mod, server won't receive violation. Mitigation: heartbeat timeout detection.
 *   Config option requireAnticheat: if enabled, players without mod will be kicked after timeout.
 *
 * - Ban execution: Uses Bukkit ban list + dispatch command "ban" for compatibility with LiteBans etc.
 *
 * Mathematical:
 * - HMAC collision probability negligible.
 * - HMAC brute force requires 2^256 attempts.
 * - Heartbeat timeout check runs every second, O(n) where n = online players, acceptable.
 */
public class AntiCheatServerPlugin extends JavaPlugin implements PluginMessageListener {

    public static final String VIOLATION_CHANNEL = "anticheat:violation";
    public static final String HEARTBEAT_CHANNEL = "anticheat:heartbeat";
    public static final String HANDSHAKE_CHANNEL = "anticheat:handshake";

    private final Map<UUID, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHandshake = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>();

    private String hmacSecret;
    private long heartbeatTimeoutMs;
    private boolean requireAnticheat;
    private boolean enableBan;
    private String banCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        hmacSecret = getConfig().getString("hmacSecret", "change-me-in-production-256-bit-secret");
        heartbeatTimeoutMs = getConfig().getLong("heartbeatTimeoutMs", 60000);
        requireAnticheat = getConfig().getBoolean("requireAnticheat", false);
        enableBan = getConfig().getBoolean("enableBan", true);
        banCommand = getConfig().getString("banCommand", "ban %player% %reason%");

        // Register channels
        getServer().getMessenger().registerIncomingPluginChannel(this, VIOLATION_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, HEARTBEAT_CHANNEL, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, HANDSHAKE_CHANNEL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, VIOLATION_CHANNEL);

        // Scheduler for heartbeat timeout
        getServer().getScheduler().runTaskTimer(this, this::checkHeartbeats, 20L * 5, 20L * 5); // every 5 seconds

        // Command handler
        try {
            if (getCommand("anticheat") != null) {
                getCommand("anticheat").setExecutor(new CommandHandler(this));
            }
        } catch (Exception e) {
            getLogger().warning("Failed to register command: " + e.getMessage());
        }

        getLogger().info("Anticheat server plugin enabled. HMAC secret set? " + (hmacSecret != null && !hmacSecret.equals("change-me")));
        getLogger().info("RequireAnticheat=" + requireAnticheat + " heartbeatTimeoutMs=" + heartbeatTimeoutMs);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, VIOLATION_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, HEARTBEAT_CHANNEL, this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, HANDSHAKE_CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, VIOLATION_CHANNEL);
        getLogger().info("Anticheat server plugin disabled");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            String payload = new String(message, StandardCharsets.UTF_8);
            // The packet is length-prefixed if using Fabric? Fabric's PacketByteBuf writes string with varint length.
            // But PluginMessageListener receives raw payload including varint? Actually Fabric's ClientPlayNetworking writes PacketByteBuf which is not directly compatible with Bukkit's plugin messages?
            // For compatibility, we try to parse: first try to decode varint-prefixed string, fallback to raw.
            payload = tryDecodeFabricString(message);

            getLogger().info("Received " + channel + " from " + player.getName() + " payload: " + payload);

            // Parse JSON
            com.google.gson.JsonObject json;
            try {
                json = new com.google.gson.Gson().fromJson(payload, com.google.gson.JsonObject.class);
            } catch (Exception e) {
                getLogger().warning("Invalid JSON from " + player.getName() + ": " + e.getMessage());
                return;
            }

            if (json == null) return;

            // Validate HMAC
            String sig = json.has("sig") ? json.get("sig").getAsString() : null;
            if (sig == null) {
                getLogger().warning("Missing signature from " + player.getName());
                return;
            }

            if (!validateHmac(json, sig)) {
                getLogger().warning("Invalid HMAC signature from " + player.getName() + " - potential spoof attempt!");
                return;
            }

            if (channel.equalsIgnoreCase(HANDSHAKE_CHANNEL)) {
                handleHandshake(player, json);
            } else if (channel.equalsIgnoreCase(HEARTBEAT_CHANNEL)) {
                handleHeartbeat(player, json);
            } else if (channel.equalsIgnoreCase(VIOLATION_CHANNEL)) {
                handleViolation(player, json);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String tryDecodeFabricString(byte[] message) {
        // Try to read varint string (Minecraft's PacketByteBuf writeString)
        try {
            // Simple varint decode
            int idx = 0;
            int length = 0;
            int shift = 0;
            while (true) {
                byte b = message[idx];
                length |= (b & 0x7F) << shift;
                idx++;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) throw new IllegalArgumentException("VarInt too big");
                if (idx >= message.length) break;
            }
            // Now length bytes should be string
            if (length > 0 && idx + length <= message.length) {
                return new String(message, idx, length, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        // Fallback raw
        return new String(message, StandardCharsets.UTF_8).trim();
    }

    private boolean validateHmac(com.google.gson.JsonObject json, String receivedSig) {
        try {
            String playerUuid = json.has("playerUuid") ? json.get("playerUuid").getAsString() : "";
            String type = json.has("type") ? json.get("type").getAsString() : json.has("v") ? "HANDSHAKE" : "";
            // Different validation per channel
            // For violation: data = playerUuid|type|subType|detail|ts|nonce
            // For heartbeat: playerUuid|HEARTBEAT|ts|nonce
            // For handshake: playerUuid|HANDSHAKE|ts

            String dataToSign;
            if (json.has("type") && json.has("subType") && json.has("detail")) {
                // violation case
                String subType = json.get("subType").getAsString();
                String detail = json.get("detail").getAsString();
                long ts = json.get("ts").getAsLong();
                String nonce = json.has("nonce") ? json.get("nonce").getAsString() : "";
                dataToSign = playerUuid + "|" + type + "|" + subType + "|" + detail + "|" + ts + "|" + nonce;
            } else if (json.has("nonce") && json.has("ts") && !json.has("detail")) {
                // heartbeat
                long ts = json.get("ts").getAsLong();
                String nonce = json.get("nonce").getAsString();
                dataToSign = playerUuid + "|HEARTBEAT|" + ts + "|" + nonce;
            } else if (json.has("ts")) {
                // handshake
                long ts = json.get("ts").getAsLong();
                dataToSign = playerUuid + "|HANDSHAKE|" + ts;
            } else {
                return false;
            }

            String expectedSig = hmacSha256(hmacSecret, dataToSign);
            return expectedSig.equalsIgnoreCase(receivedSig);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void handleHandshake(Player player, com.google.gson.JsonObject json) {
        UUID uuid = player.getUniqueId();
        lastHandshake.put(uuid, System.currentTimeMillis());
        lastHeartbeat.put(uuid, System.currentTimeMillis());
        playerNames.put(uuid, player.getName());
        getLogger().info("Handshake from " + player.getName() + " modVersion=" + (json.has("modVersion") ? json.get("modVersion").getAsString() : "unknown"));
        // Optionally send welcome?
    }

    private void handleHeartbeat(Player player, com.google.gson.JsonObject json) {
        UUID uuid = player.getUniqueId();
        lastHeartbeat.put(uuid, System.currentTimeMillis());
        playerNames.put(uuid, player.getName());
        // getLogger().fine("Heartbeat from " + player.getName());
    }

    private void handleViolation(Player player, com.google.gson.JsonObject json) {
        String type = json.has("type") ? json.get("type").getAsString() : "UNKNOWN";
        String subType = json.has("subType") ? json.get("subType").getAsString() : "UNKNOWN";
        String detail = json.has("detail") ? json.get("detail").getAsString() : "";
        long ts = json.has("ts") ? json.get("ts").getAsLong() : 0;

        String reason = "Anticheat violation: " + type + ":" + subType + " " + detail;
        getLogger().warning("VIOLATION from " + player.getName() + " type=" + type + " subType=" + subType + " detail=" + detail);

        if (!enableBan) {
            getLogger().info("Ban disabled, not banning " + player.getName() + " but logging violation");
            return;
        }

        // Execute ban via BanHandler
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                BanHandler.banPlayer(player, reason, banCommand);
                getLogger().info("Banned " + player.getName() + " for " + reason);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void checkHeartbeats() {
        if (!requireAnticheat) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            Long lastHb = lastHeartbeat.get(uuid);
            Long lastHs = lastHandshake.get(uuid);
            if (lastHs == null) {
                // No handshake ever, player maybe doesn't have mod
                // Give grace period after join (e.g., 30 seconds)
                // We need to track join time separately, for now check if they've been online > 30 sec without handshake -> kick
                if (p.getTicksLived() > 20 * 30) {
                    getLogger().warning("Player " + p.getName() + " has no anticheat handshake after 30s, kicking (requireAnticheat=true)");
                    p.kick(net.kyori.adventure.text.Component.text("Anticheat mod required. Please install anticheat-client mod."));
                }
                continue;
            }
            if (lastHb != null) {
                long delta = now - lastHb;
                if (delta > heartbeatTimeoutMs) {
                    getLogger().warning("Player " + p.getName() + " heartbeat timeout (" + delta + "ms), possible tampering");
                    if (getConfig().getBoolean("kickOnHeartbeatTimeout", true)) {
                        p.kick(net.kyori.adventure.text.Component.text("Anticheat heartbeat timeout - possible tampering detected."));
                    }
                }
            }
        }
    }

    private static String hmacSha256(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
