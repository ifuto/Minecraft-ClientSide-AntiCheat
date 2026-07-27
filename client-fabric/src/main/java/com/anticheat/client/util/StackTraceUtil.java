package com.anticheat.client.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Evolution: Stack walk analysis to detect illegal call origins.
 * Example: If ClientPlayerEntity.setVelocity() is called from unknown mod (not vanilla, not fabric), flag.
 *
 * Performance: Walk is O(depth), depth usually <50, called only on suspicious events (attack, move), not per tick.
 */
public final class StackTraceUtil {
    private static final Set<String> ALLOWED_PREFIXES = new HashSet<>(Arrays.asList(
            "net.minecraft.",
            "com.mojang.",
            "net.fabricmc.",
            "java.",
            "jdk.",
            "sun.",
            "com.anticheat.client." // our own mod
    ));

    private static final Set<Long> CHEAT_PACKAGE_HASHES = com.anticheat.client.util.CriticalPackageHashes.getHashes();

    private StackTraceUtil() {}

    /**
     * Checks current stack trace for presence of blacklisted cheat packages.
     * Returns violation description or null if clean.
     */
    public static String checkCurrentStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : stack) {
            String className = el.getClassName();
            // Extract package
            int lastDot = className.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String pkg = className.substring(0, lastDot);
            // Quick allow check
            boolean allowed = false;
            for (String prefix : ALLOWED_PREFIXES) {
                if (className.startsWith(prefix)) { allowed = true; break; }
            }
            if (allowed) continue;

            // Check if package hash matches cheat
            long h = HashUtil.hash64(pkg);
            if (CHEAT_PACKAGE_HASHES.contains(h)) {
                return "Illegal call origin: " + className + "#" + el.getMethodName() + " packageHash=" + HashUtil.toHex(h);
            }

            // Also check cumulative parent packages
            String[] parts = pkg.split("\\.");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) sb.append('.');
                sb.append(parts[i]);
                long ph = HashUtil.hash64(sb.toString());
                if (CHEAT_PACKAGE_HASHES.contains(ph)) {
                    return "Illegal parent call origin: " + className + " parent=" + sb + " hash=" + HashUtil.toHex(ph);
                }
            }
        }
        return null;
    }

    /**
     * Returns true if stack contains only allowed prefixes (fast path)
     */
    public static boolean isStackClean() {
        return checkCurrentStack() == null;
    }
}
