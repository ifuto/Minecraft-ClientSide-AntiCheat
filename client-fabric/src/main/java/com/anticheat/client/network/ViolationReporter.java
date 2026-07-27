package com.anticheat.client.network;

import com.anticheat.client.util.Config;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Central violation reporter.
 *
 * When any detection module flags a violation, it calls here, which then:
 * - Logs locally
 * - If enabled, sends self-ban packet to server with reason and HMAC signature
 *
 * Packet format: anticheat:violation channel, JSON payload:
 * {
 *   "v":1,
 *   "playerUuid":"...",
 *   "playerName":"...",
 *   "type":"PACKAGE|FILE|MEMORY|INPUT|ROTATION|CPS|TAMPER",
 *   "subType":"...",
 *   "detail":"...",
 *   "ts":1712345678,
 *   "nonce":"...",
 *   "sig":"HMAC-SHA256 hex"
 * }
 *
 * HMAC: HMAC-SHA256(secret, playerUuid|type|subType|detail|ts|nonce)
 * Server validates with shared secret.
 *
 * Contradiction handling:
 * - Self-ban packet trusts client to report itself. Attacker can simply not send packet.
 * - Mitigation: Server also expects periodic heartbeat. If client detects violation but fails to send packet (e.g., network), server will still have logs? No.
 * - Better: ViolationReporter marks local state as "compromised" and disconnects client locally with message, even if server packet fails. That at least prevents cheater from continuing on client side?
 * - But determined attacker can modify this class to NOP.
 * - So server-side should also have timeout for missing heartbeat to detect tampering.
 */
public final class ViolationReporter {

    private static Config config;
    private static SelfBanPacketSender sender;
    private static boolean initialized = false;

    // For debouncing: avoid spam banning
    private static long lastViolationTime = 0;
    private static final long COOLDOWN_MS = 5000;

    private ViolationReporter() {}

    public static void init(Config cfg, SelfBanPacketSender packetSender) {
        config = cfg;
        sender = packetSender;
        initialized = true;
    }

    public static void reportPackageViolation(String pkgName, String level) {
        report("PACKAGE", level, pkgName);
    }

    public static void reportFileViolation(String fileName, String level) {
        report("FILE", level, fileName);
    }

    public static void reportMemoryViolation(String module, String detail) {
        report("MEMORY", module, detail);
    }

    public static void reportInputViolation(String subType, String detail) {
        report("INPUT", subType, detail);
    }

    public static void reportRotationViolation(String subType, String detail) {
        report("ROTATION", subType, detail);
    }

    public static void reportCPSViolation(String subType, String detail) {
        report("CPS", subType, detail);
    }

    public static void reportTamper(String subType, String detail) {
        report("TAMPER", subType, detail);
    }

    private static synchronized void report(String type, String subType, String detail) {
        if (!initialized) {
            System.err.println("[Anticheat] ViolationReporter not initialized, violation: " + type + " " + subType + " " + detail);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastViolationTime < COOLDOWN_MS) {
            // Still log, but don't spam packet immediately?
            // We'll still send if critical type?
            // For now, allow burst but debounce slightly
        }
        lastViolationTime = now;

        System.out.println("[Anticheat][VIOLATION] Type=" + type + " SubType=" + subType + " Detail=" + detail);

        if (!config.enableSelfBan) {
            System.out.println("[Anticheat] Self-ban disabled in config, not sending packet");
            return;
        }

        try {
            // Build JSON
            JsonObject json = new JsonObject();
            json.addProperty("v", 1);
            // Attempt to get player info if available; otherwise placeholder
            String playerUuid = "unknown";
            String playerName = "unknown";
            try {
                // We'll try to fetch from MinecraftClient later via reflection; for now placeholder
                // The actual sender will fill player info if it has access to client
                if (sender != null) {
                    playerUuid = sender.getPlayerUuid();
                    playerName = sender.getPlayerName();
                }
            } catch (Exception ignored) {}

            json.addProperty("playerUuid", playerUuid);
            json.addProperty("playerName", playerName);
            json.addProperty("type", type);
            json.addProperty("subType", subType);
            json.addProperty("detail", detail);
            long ts = Instant.now().getEpochSecond();
            json.addProperty("ts", ts);
            String nonce = UUID.randomUUID().toString().substring(0, 8);
            json.addProperty("nonce", nonce);

            // Compute HMAC
            String dataToSign = playerUuid + "|" + type + "|" + subType + "|" + detail + "|" + ts + "|" + nonce;
            String sig = HmacUtil.hmacSha256(config.hmacSecret, dataToSign);
            json.addProperty("sig", sig);

            // Send
            if (sender != null) {
                sender.sendViolationPacket(json);
            }

            // Also trigger local disconnect if critical?
            if (type.equals("PACKAGE") || type.equals("FILE") || type.equals("MEMORY") || type.equals("TAMPER")) {
                // These are critical; we can disconnect client locally with message
                // The sender may handle local disconnect
                if (sender != null) {
                    sender.handleLocalBan(type + ":" + subType + " " + detail);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Nested util for HMAC
    public static class HmacUtil {
        public static String hmacSha256(String secret, String data) {
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                mac.init(keySpec);
                byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : raw) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
