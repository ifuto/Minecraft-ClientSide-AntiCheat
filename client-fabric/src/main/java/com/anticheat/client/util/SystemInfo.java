package com.anticheat.client.util;

import java.lang.management.ManagementFactory;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.Collections;

/**
 * Evolution: Hardware fingerprinting and system attestation.
 * Collects anonymized system info for HWID ban and anomaly detection.
 * Privacy: hashes sensitive info, no raw MAC etc stored.
 */
public final class SystemInfo {
    private SystemInfo() {}

    public static String getOSInfo() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch");
    }

    public static String getJVMArgs() {
        try {
            return String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static String getHashedMacAddresses() {
        try {
            StringBuilder sb = new StringBuilder();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null) {
                    for (byte b : mac) sb.append(String.format("%02X", b));
                    sb.append(";");
                }
            }
            if (sb.length() == 0) return "no-mac";
            // Hash it for privacy
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Exception e) {
            return "error-mac";
        }
    }

    public static String getCpuInfo() {
        return System.getenv("PROCESSOR_IDENTIFIER") != null ? System.getenv("PROCESSOR_IDENTIFIER") : "unknown-cpu";
    }

    public static String generateHWID() {
        try {
            String raw = getOSInfo() + "|" + getCpuInfo() + "|" + getHashedMacAddresses() + "|" + System.getProperty("user.name");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Truncate to 16 char hex for HWID
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unknown-hwid";
        }
    }
}
