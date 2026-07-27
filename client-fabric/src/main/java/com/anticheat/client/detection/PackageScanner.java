package com.anticheat.client.detection;

import com.anticheat.client.util.CriticalPackageHashes;
import com.anticheat.client.util.HashUtil;
import com.anticheat.client.util.SuspiciousPackageHashes;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Package scanner that avoids storing plaintext package names in binary (uses hashes).
 *
 * Detection strategy:
 * 1. Scan Package.getPackages() (runtime loaded packages)
 * 2. Scan all jar files in mods folder for class entries, derive package names
 * 3. For each discovered package, compute hash64 and compare against blacklist hashes
 *
 * False positive handling:
 * - Excludes known legitimate packages (sodium, optifine, lunar, badlion, etc.) from blacklist generation
 * - Distinguishes CRITICAL (ban) vs SUSPICIOUS (log warning)
 *
 * Limitations & contradictions:
 * - Attacker can rename package (obfuscation) to evade hash check (bypass trivial). Need behavioral heuristics as second line.
 * - Package.getPackages() may not contain all packages until classes loaded; jar scanning mitigates but can be bypassed by custom classloader loading from memory.
 * - Hash collision probability negligible but not zero: P_collision ~ n/2^64.
 *
 * Mathematical correctness:
 * - Hash set lookup O(1)
 * - Scanning complexity O(J * E) where J = number of jars, E = entries per jar
 * - For 100 mods * 5000 entries = 500k entries, hash compute O(500k) ~ acceptable during startup with caching.
 */
public final class PackageScanner {

    public enum ViolationLevel {
        CRITICAL,
        SUSPICIOUS
    }

    public static class Violation {
        public final String packageName;
        public final long hash;
        public final ViolationLevel level;

        public Violation(String pkg, long hash, ViolationLevel level) {
            this.packageName = pkg;
            this.hash = hash;
            this.level = level;
        }

        @Override
        public String toString() {
            return level + " package: " + packageName + " hash=" + HashUtil.toHex(hash);
        }
    }

    private final Set<Long> criticalHashes;
    private final Set<Long> suspiciousHashes;

    public PackageScanner() {
        this.criticalHashes = CriticalPackageHashes.getHashes();
        this.suspiciousHashes = SuspiciousPackageHashes.getHashes();
    }

    /**
     * Scan all known packages.
     */
    public List<Violation> scan() {
        List<Violation> violations = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();

        // 1. Runtime packages
        for (Package pkg : Package.getPackages()) {
            String name = pkg.getName();
            if (name == null || name.isEmpty()) continue;
            if (!seenPackages.add(name)) continue;
            checkPackage(name, violations);
        }

        // 2. Jar scanning in mods folder
        try {
            Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
            if (Files.isDirectory(modsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
                    for (Path jarPath : stream) {
                        try {
                            scanJarFile(jarPath, seenPackages, violations);
                        } catch (Exception e) {
                            // ignore corrupt jars
                        }
                        // Early exit if critical found? Continue to collect all
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return violations;
    }

    private void scanJarFile(Path jarPath, Set<String> seenPackages, List<Violation> violations) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !entry.isDirectory()) {
                    // Convert to package: com/example/Foo.class -> com.example
                    int lastSlash = name.lastIndexOf('/');
                    if (lastSlash > 0) {
                        String pkgPath = name.substring(0, lastSlash);
                        String pkgName = pkgPath.replace('/', '.');
                        if (seenPackages.add(pkgName)) {
                            checkPackage(pkgName, violations);
                        }
                        // Also check parent packages progressively for cheat root detection
                        // e.g., if file is net/ccbluex/liquidbounce/features/module/impl/ModuleX.class,
                        // we want to detect net.ccbluex.liquidbounce and subpackages
                        // So check progressively from top to bottom
                        String[] parts = pkgName.split("\\.");
                        StringBuilder cumulative = new StringBuilder();
                        for (int i = 0; i < parts.length; i++) {
                            if (i > 0) cumulative.append('.');
                            cumulative.append(parts[i]);
                            String cumulativePkg = cumulative.toString();
                            if (seenPackages.add(cumulativePkg)) {
                                checkPackage(cumulativePkg, violations);
                            } else {
                                // Already seen, but still we had checked previously; still need to ensure parent detection
                                // To avoid duplicate checks, we already checked via seenPackages
                            }
                        }
                    }
                }
            }
        }
    }

    private void checkPackage(String pkgName, List<Violation> violations) {
        long h = HashUtil.hash64(pkgName);
        if (criticalHashes.contains(h)) {
            violations.add(new Violation(pkgName, h, ViolationLevel.CRITICAL));
        } else if (suspiciousHashes.contains(h)) {
            violations.add(new Violation(pkgName, h, ViolationLevel.SUSPICIOUS));
        } else {
            // For cheat packages, often subpackage of critical root still should be flagged even if not explicitly listed?
            // Example: meteordevelopment.meteorclient.* -> we already have root hash, but subpackage like meteordevelopment.meteorclient.systems.modules.combat
            // Its hash != root hash, so would be missed if only root listed. Therefore we also check if any critical package is prefix of this package.
            // This cannot be done with hashes alone without plaintext. Tradeoff: we have to store root hashes AND check prefix via hash of prefix parts?
            // Our earlier scanning already checks cumulative prefixes via splitting, so that handles hierarchical detection.
            // For safety, we also check if package startsWith critical package string via hash? We don't have plaintext, but we can hash each prefix and check as we already doing via cumulative loop elsewhere.
            // So if this package itself is not in set, but its parent is, the parent would have been checked in cumulative loop when scanning jar entries.
            // For Package.getPackages() case, we might miss subpackages: if Package is meteordevelopment.meteorclient.systems.modules.combat, its parent meteordevelopment.meteorclient was not explicitly in Package list maybe not loaded.
            // So we need to check all parent prefixes for each runtime package as well.
            String[] parts = pkgName.split("\\.");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) { // exclude full already checked
                if (i > 0) sb.append('.');
                sb.append(parts[i]);
                String parent = sb.toString();
                long parentHash = HashUtil.hash64(parent);
                if (criticalHashes.contains(parentHash)) {
                    violations.add(new Violation(pkgName + " (parent=" + parent + ")", parentHash, ViolationLevel.CRITICAL));
                    break;
                }
            }
        }
    }

    /**
     * Quick check if any critical violation exists.
     */
    public boolean hasCritical() {
        return !scan().stream().filter(v -> v.level == ViolationLevel.CRITICAL).toList().isEmpty();
    }
}
