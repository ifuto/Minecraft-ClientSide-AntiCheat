package com.anticheat.client.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.UUID;

/**
 * Evolution: Cryptographically secure challenge-response attestation.
 *
 * Purpose: Server sends random challenge (nonce + code snippet). Client must execute and return result + HMAC.
 * This makes it harder for attacker to precompute response without actually running code.
 *
 * Also includes secure random, SHA-256 chain, and jar hash verification.
 */
public final class CryptoUtil {
    private CryptoUtil() {}

    public static String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length*2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String generateNonce() {
        return UUID.randomUUID().toString().replace("-", "") + "-" + System.nanoTime();
    }

    public static byte[] secureRandomBytes(int len) {
        byte[] b = new byte[len];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /**
     * Challenge: server sends "a:b:c" where a,b are ints, c is op.
     * Client must compute result: e.g., op=ADD => a+b, op=XOR => a^b, etc.
     * This is trivial but shows execution attestation; in production server could send Lua/WASM snippet to execute in sandbox.
     */
    public static long solveChallenge(String challenge) {
        // format: "123:456:OP"
        try {
            String[] parts = challenge.split(":");
            if (parts.length != 3) return -1;
            long a = Long.parseLong(parts[0]);
            long b = Long.parseLong(parts[1]);
            String op = parts[2];
            return switch (op) {
                case "ADD" -> a + b;
                case "XOR" -> a ^ b;
                case "MUL" -> a * b;
                case "SUB" -> a - b;
                case "ROT" -> Long.rotateLeft(a, (int)(b % 64));
                default -> a ^ b;
            };
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Verifiable hash chain (blockchain-like) for evidence integrity.
     * Each evidence entry hash = SHA256(prevHash + entryData)
     */
    public static String chainHash(String prevHash, String entryData) {
        return sha256Hex(prevHash + "|" + entryData);
    }
}
