package com.anticheat.client.network;

import com.anticheat.client.util.Config;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public class SelfBanPacketSender {

    public static final Identifier VIOLATION_CHANNEL = new Identifier("anticheat", "violation");
    public static final Identifier HEARTBEAT_CHANNEL = new Identifier("anticheat", "heartbeat");
    public static final Identifier HANDSHAKE_CHANNEL = new Identifier("anticheat", "handshake");
    public static final Identifier HWID_CHANNEL = new Identifier("anticheat", "hwid");
    public static final Identifier CHALLENGE_CHANNEL = new Identifier("anticheat", "challenge");
    public static final Identifier MODLIST_CHANNEL = new Identifier("anticheat", "modlist");
    public static final Identifier APPLIST_CHANNEL = new Identifier("anticheat", "applist");

    private final Config config;
    private String playerUuid = "unknown";
    private String playerName = "unknown";

    public SelfBanPacketSender(Config config) { this.config = config; }
    public void setPlayerInfo(String uuid, String name) { this.playerUuid = uuid; this.playerName = name; }
    public String getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }

    public void sendViolationPacket(JsonObject json) {
        try {
            String payload = json.toString();
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(payload);
            if (ClientPlayNetworking.canSend(VIOLATION_CHANNEL)) {
                ClientPlayNetworking.send(VIOLATION_CHANNEL, buf);
                System.out.println("[Anticheat] Sent violation: " + payload);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void sendHeartbeat() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("v", 3);
            json.addProperty("playerUuid", playerUuid);
            json.addProperty("playerName", playerName);
            long ts = System.currentTimeMillis() / 1000;
            json.addProperty("ts", ts);
            String nonce = java.util.UUID.randomUUID().toString().substring(0,8);
            json.addProperty("nonce", nonce);
            String sig = ViolationReporter.HmacUtil.hmacSha256(config.hmacSecret, playerUuid + "|HEARTBEAT|" + ts + "|" + nonce);
            json.addProperty("sig", sig);
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(json.toString());
            if (ClientPlayNetworking.canSend(HEARTBEAT_CHANNEL)) ClientPlayNetworking.send(HEARTBEAT_CHANNEL, buf);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void sendHandshake() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("v", 3);
            json.addProperty("playerUuid", playerUuid);
            json.addProperty("playerName", playerName);
            json.addProperty("modVersion", "3.0.0-safe");
            json.addProperty("os", System.getProperty("os.name"));
            json.addProperty("javaVersion", System.getProperty("java.version"));
            String hwid = "unknown";
            try { hwid = new com.anticheat.client.detection.HardwareFingerprint().getHwid(); json.addProperty("hwid", hwid); } catch (Exception ignored) {}
            long ts = System.currentTimeMillis()/1000;
            json.addProperty("ts", ts);
            json.addProperty("sig", ViolationReporter.HmacUtil.hmacSha256(config.hmacSecret, playerUuid + "|HANDSHAKE|" + ts));
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(json.toString());
            if (ClientPlayNetworking.canSend(HANDSHAKE_CHANNEL)) {
                ClientPlayNetworking.send(HANDSHAKE_CHANNEL, buf);
                System.out.println("[Anticheat] Handshake sent HWID " + hwid);
            }
            sendHwid(hwid);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void sendHwid(String hwid) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("playerUuid", playerUuid);
            json.addProperty("hwid", hwid);
            long ts = System.currentTimeMillis()/1000;
            json.addProperty("ts", ts);
            json.addProperty("sig", ViolationReporter.HmacUtil.hmacSha256(config.hmacSecret, playerUuid + "|HWID|" + hwid + "|" + ts));
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(json.toString());
            if (ClientPlayNetworking.canSend(HWID_CHANNEL)) ClientPlayNetworking.send(HWID_CHANNEL, buf);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void sendModList(JsonObject modListJson) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(modListJson.toString());
            if (ClientPlayNetworking.canSend(MODLIST_CHANNEL)) {
                ClientPlayNetworking.send(MODLIST_CHANNEL, buf);
                System.out.println("[Anticheat] Mod list sent " + modListJson.get("count").getAsInt() + " mods");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void sendAppList(JsonObject appListJson) {
        try {
            PacketByteBuf buf = PacketByteBufs.create();
            buf.writeString(appListJson.toString());
            if (ClientPlayNetworking.canSend(APPLIST_CHANNEL)) {
                ClientPlayNetworking.send(APPLIST_CHANNEL, buf);
                System.out.println("[Anticheat] App list sent " + appListJson.get("count").getAsInt() + " apps via DLL 10s after join");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void handleLocalBan(String reason) {
        try {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> {
                    try {
                        net.minecraft.text.Text text = net.minecraft.text.Text.of("Banned by Anticheat: " + reason);
                        if (client.getNetworkHandler() != null) client.getNetworkHandler().getConnection().disconnect(text);
                    } catch (Exception e) { e.printStackTrace(); }
                });
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
