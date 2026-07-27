package com.anticheat.client.network;

import com.anticheat.client.util.Config;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Sends custom payload packet to server.
 *
 * Channels:
 * - anticheat:violation (client -> server)
 * - anticheat:heartbeat (client -> server periodic)
 * - anticheat:handshake (client -> server on join)
 *
 * For Fabric 1.20.1, uses ClientPlayNetworking.
 * For 1.21.1, payload API changed to CustomPayload, but we keep compatibility shim.
 *
 * Self-ban packet logic:
 * - When violation detected, send JSON with HMAC to server.
 * - Server validates and executes ban.
 *
 * Threat model note:
 * - If attacker disables this sender, server will not receive packet and won't ban.
 * - Therefore server must also require heartbeat and may kick players who don't send heartbeat within timeout.
 * - Also client locally disconnects with message "You have been banned by Anticheat: reason" to at least stop cheater locally if packet fails.
 */
public class SelfBanPacketSender {

    public static final Identifier VIOLATION_CHANNEL = new Identifier("anticheat", "violation");
    public static final Identifier HEARTBEAT_CHANNEL = new Identifier("anticheat", "heartbeat");
    public static final Identifier HANDSHAKE_CHANNEL = new Identifier("anticheat", "handshake");

    private final Config config;
    private String playerUuid = "unknown";
    private String playerName = "unknown";

    public SelfBanPacketSender(Config config) {
        this.config = config;
    }

    public void setPlayerInfo(String uuid, String name) {
        this.playerUuid = uuid;
        this.playerName = name;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void sendViolationPacket(JsonObject json) {
        try {
            String payload = json.toString();
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(payload);

            if (ClientPlayNetworking.canSend(VIOLATION_CHANNEL)) {
                ClientPlayNetworking.send(VIOLATION_CHANNEL, buf);
                System.out.println("[Anticheat] Sent violation packet: " + payload);
            } else {
                System.out.println("[Anticheat] Cannot send violation packet, channel not registered (server may not have plugin). Payload: " + payload);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendHeartbeat() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("v", 1);
            json.addProperty("playerUuid", playerUuid);
            json.addProperty("playerName", playerName);
            long ts = System.currentTimeMillis() / 1000;
            json.addProperty("ts", ts);
            String nonce = java.util.UUID.randomUUID().toString().substring(0, 8);
            json.addProperty("nonce", nonce);
            String dataToSign = playerUuid + "|HEARTBEAT|" + ts + "|" + nonce;
            String sig = ViolationReporter.HmacUtil.hmacSha256(config.hmacSecret, dataToSign);
            json.addProperty("sig", sig);

            String payload = json.toString();
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(payload);

            if (ClientPlayNetworking.canSend(HEARTBEAT_CHANNEL)) {
                ClientPlayNetworking.send(HEARTBEAT_CHANNEL, buf);
                // System.out.println("[Anticheat] Heartbeat sent");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendHandshake() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("v", 1);
            json.addProperty("playerUuid", playerUuid);
            json.addProperty("playerName", playerName);
            json.addProperty("modVersion", "1.0.0");
            json.addProperty("os", System.getProperty("os.name"));
            json.addProperty("javaVersion", System.getProperty("java.version"));
            long ts = System.currentTimeMillis() / 1000;
            json.addProperty("ts", ts);
            // Compute jar hash if native available?
            // For attestation, include hash of our own jar
            String dataToSign = playerUuid + "|HANDSHAKE|" + ts;
            String sig = ViolationReporter.HmacUtil.hmacSha256(config.hmacSecret, dataToSign);
            json.addProperty("sig", sig);

            String payload = json.toString();
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(payload);

            if (ClientPlayNetworking.canSend(HANDSHAKE_CHANNEL)) {
                ClientPlayNetworking.send(HANDSHAKE_CHANNEL, buf);
                System.out.println("[Anticheat] Handshake sent");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void handleLocalBan(String reason) {
        // Schedule local disconnect on client thread
        try {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> {
                    if (client.player != null) {
                        // For safety, disconnect with message
                        // Use client disconnect if in world
                        try {
                            net.minecraft.text.Text text = net.minecraft.text.Text.of("You have been banned by Anticheat: " + reason);
                            if (client.getNetworkHandler() != null) {
                                client.getNetworkHandler().getConnection().disconnect(text);
                            }
                            System.out.println("[Anticheat] Local disconnect triggered: " + reason);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
