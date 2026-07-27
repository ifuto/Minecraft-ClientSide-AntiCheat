package com.anticheat.client.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Hash utilities.
 * Uses SHA-256 truncated to 64-bit (first 8 bytes big-endian) to avoid storing plaintext strings.
 *
 * Mathematical guarantees:
 * - Preimage resistance: Given hash, finding original string requires 2^256 work for full SHA-256, 2^64 for truncated brute force.
 * - Collision probability for n entries: approx n^2 / 2 * 2^-64. For n=100, ~ 2.7e-16.
 * - Lower collision reduces false positives, but truncation increases risk vs full SHA-256; still negligible for blacklist sizes.
 *
 * Note: Package renaming bypass: attacker can rename package to avoid hash match. Therefore hash blacklist is not complete,
 *       behavioral detection (input authenticity, rotation, CPS) must complement it.
 */
public final class HashUtil {
    private HashUtil() {}

    public static long hash64(String input) {
        try {
            String normalized = input.toLowerCase();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFFL);
            }
            return value;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static long hash64Unsigned(String input) {
        return hash64(input);
    }

    /**
     * Check if filename contains any blacklisted substring via hash comparison.
     * Instead of storing plaintext substrings, we compute hashes of all substrings of the input
     * and compare against a set of precomputed hashes.
     *
     * Complexity: O(L^2 * H) where L = filename length (~ 30-80), H = hash cost.
     * L^2 = ~6400 worst case, acceptable for file monitor events (rare).
     *
     * Potential false negative: If attacker inserts separators to break substring detection,
     * e.g., "m_e_t_e_o_r" would not be detected. Countermeasure: also normalize by removing non-alphanumeric.
     */
    public static boolean containsBlacklistedSubstringHash(String filename, java.util.Set<Long> blacklistHashes) {
        if (filename == null || filename.isEmpty()) return false;
        String lower = filename.toLowerCase();
        // Also test normalized version stripping separators
        String normalized = lower.replaceAll("[^a-z0-9]", "");
        // Check substrings of both original lower and normalized
        if (checkSubstrings(lower, blacklistHashes)) return true;
        if (!normalized.equals(lower)) {
            return checkSubstrings(normalized, blacklistHashes);
        }
        return false;
    }

    private static boolean checkSubstrings(String str, java.util.Set<Long> blacklistHashes) {
        int len = str.length();
        // we consider substrings length >=3 to avoid trivial collisions
        for (int i = 0; i < len; i++) {
            for (int j = i + 3; j <= len && j - i <= 30; j++) { // cap substring length 30
                String sub = str.substring(i, j);
                long h = hash64(sub);
                if (blacklistHashes.contains(h)) {
                    return true;
                }
            }
        }
        // Also check full string hash (in case blacklist contains full filename)
        return blacklistHashes.contains(hash64(str));
    }

    public static String toHex(long value) {
        return String.format("%016x", value);
    }
}
