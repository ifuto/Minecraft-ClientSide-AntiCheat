package com.anticheat.client.detection;

import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.network.ViolationReporter;
import com.anticheat.client.util.CryptoUtil;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Evolution: Self-integrity verification.
 * Ensures our own jar and classes have not been tampered.
 *
 * Methods:
 * - Compute SHA-256 of our own jar file, compare to expected hash embedded at build time (hardcoded).
 *   If mismatch, report TAMPER (attacker modified our anticheat to disable it).
 * - Verify critical class bytecode hashes (e.g., AntiCheatClientMod, PackageScanner) have not changed at runtime.
 * - Native can also verify jar hash via JNI (harder to bypass because native code is harder to patch than Java).
 * - Challenge-response: server sends random challenge, client must hash its own jar + challenge and return.
 *
 * Performance: jar hash once at startup, then periodically every 5 min.
 *
 * Limitations: If attacker can modify this class itself to skip check, bypass possible. Mitigation: native also checks, and obfuscation.
 */
public final class IntegrityGuard {

    // Expected hash of our jar - in production this would be injected at build time via gradle property
    // For now placeholder, will be overwritten at runtime if file contains hash? We'll compute and store dynamically for demo.
    private static final String EXPECTED_HASH_PLACEHOLDER = "CHECKSUM_NOT_SET_IN_DEMO";

    private String lastComputedHash = null;

    public static class IntegrityViolation {
        public final String reason;
        public final String detail;

        public IntegrityViolation(String reason, String detail) {
            this.reason = reason;
            this.detail = detail;
        }
    }

    /**
     * Computes SHA-256 of our own mod jar.
     */
    public String computeOwnJarHash() {
        try {
            // Find our jar via FabricLoader
            Path jarPath = FabricLoader.getInstance().getModContainer("anticheat-client")
                    .orElseThrow()
                    .getOrigin().getPaths().get(0); // first path = jar

            if (Files.isDirectory(jarPath)) {
                // In dev environment, it's a folder, not jar - skip
                return "DEV_ENV_NO_JAR";
            }

            byte[] data = Files.readAllBytes(jarPath);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            lastComputedHash = sb.toString();
            return lastComputedHash;
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR_" + e.getMessage();
        }
    }

    /**
     * Verify against expected hash if set, else just log.
     */
    public List<IntegrityViolation> verify() {
        java.util.ArrayList<IntegrityViolation> violations = new java.util.ArrayList<>();
        String currentHash = computeOwnJarHash();

        if (currentHash.startsWith("DEV_ENV") || currentHash.startsWith("ERROR")) {
            // Skip in dev
            return violations;
        }

        // If expected hash is placeholder, we set it as current (first run) - in production should be hardcoded
        if (EXPECTED_HASH_PLACEHOLDER.equals("CHECKSUM_NOT_SET_IN_DEMO")) {
            System.out.println("[IntegrityGuard] Jar hash (demo, not enforced): " + currentHash);
            return violations;
        }

        if (!currentHash.equalsIgnoreCase(EXPECTED_HASH_PLACEHOLDER)) {
            violations.add(new IntegrityViolation("JAR_TAMPER", "Expected " + EXPECTED_HASH_PLACEHOLDER + " got " + currentHash));
            ViolationReporter.reportTamper("JAR_TAMPER", "Jar hash mismatch");
        }

        // Native verification
        if (NativeBridge.isLoaded()) {
            try {
                // Use computeJarHash native method
                Path jarPath = FabricLoader.getInstance().getModContainer("anticheat-client").orElseThrow().getOrigin().getPaths().get(0);
                if (!Files.isDirectory(jarPath)) {
                    String nativeHash = NativeBridge.computeJarHash(jarPath.toAbsolutePath().toString());
                    if (!nativeHash.equalsIgnoreCase(currentHash) && !nativeHash.startsWith("FILE_NOT_FOUND")) {
                        violations.add(new IntegrityViolation("NATIVE_JAR_MISMATCH", "Java hash " + currentHash + " vs native " + nativeHash));
                    }
                }
            } catch (Throwable t) {
                // ignore
            }
        }

        return violations;
    }

    /**
     * Respond to server challenge: hash(jarHash + challenge)
     */
    public String respondToChallenge(String challenge) {
        String jarHash = lastComputedHash != null ? lastComputedHash : computeOwnJarHash();
        return CryptoUtil.sha256Hex(jarHash + "|" + challenge);
    }
}
