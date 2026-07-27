package com.anticheat.client.detection;

import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.util.CriticalFileHashes;
import com.anticheat.client.util.HashUtil;
import com.anticheat.client.util.SuspiciousFileHashes;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mods folder monitoring.
 *
 * Two layers:
 * 1. Java WatchService (platform independent, works on all OS)
 * 2. Native DLL monitoring via ReadDirectoryChangesW (Windows) / inotify (Linux) for background monitoring per requirement
 *
 * Requirement: ".jarと同じ階層にある.dllでバックグラウンドを起動し、Modsフォルダを監視"
 * Implementation: Native DLL starts a std::thread that monitors the mods folder continuously.
 *
 * Detection: Hash-based filename blacklist, as described: store hashes of known cheat substrings, check if filename contains such substring via hash comparison of substrings.
 *
 * Bypass considerations:
 * - Attacker can place cheat jar outside mods folder and load via custom classloader or -javaagent -> file monitor misses.
 * - Attacker can rename file to random string that doesn't contain blacklisted substring -> evades keyword detection. Mitigation: also check file content? Compute hash of jar's contained packages (already done by PackageScanner) so even renamed file would be caught at package level.
 * - Attacker can disable WatchService by modifying client mod: need integrity check.
 * - Native DLL can be replaced: mitigated by verifying hash before load.
 */
public final class FileMonitor {

    public enum FileViolationLevel {
        CRITICAL,
        SUSPICIOUS
    }

    public static class FileViolation {
        public final Path file;
        public final String reason;
        public final FileViolationLevel level;

        public FileViolation(Path file, String reason, FileViolationLevel level) {
            this.file = file;
            this.reason = reason;
            this.level = level;
        }

        @Override
        public String toString() {
            return level + " file: " + file.getFileName() + " reason: " + reason;
        }
    }

    private final Path modsDir;
    private final Set<Long> criticalFileHashes;
    private final Set<Long> suspiciousFileHashes;
    private WatchService watchService;
    private ExecutorService executor;
    private volatile boolean running = false;

    private final List<FileViolation> pendingViolations = new ArrayList<>();

    public FileMonitor() {
        this.modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        this.criticalFileHashes = CriticalFileHashes.getHashes();
        this.suspiciousFileHashes = SuspiciousFileHashes.getHashes();
    }

    /**
     * Initial scan of existing files in mods folder.
     */
    public List<FileViolation> initialScan() {
        List<FileViolation> violations = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return violations;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    FileViolation v = checkFile(p);
                    if (v != null) violations.add(v);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return violations;
    }

    private FileViolation checkFile(Path file) {
        String fileName = file.getFileName().toString();
        // Only care about jar files, but also dlls might be injected?
        // Check all files for blacklist keywords, but focus on .jar
        boolean containsCritical = HashUtil.containsBlacklistedSubstringHash(fileName, criticalFileHashes);
        if (containsCritical) {
            long h = HashUtil.hash64(fileName);
            return new FileViolation(file, "filename hash matches critical blacklist (hash=" + HashUtil.toHex(h) + ", name=" + fileName + ")", FileViolationLevel.CRITICAL);
        }
        boolean containsSuspicious = HashUtil.containsBlacklistedSubstringHash(fileName, suspiciousFileHashes);
        if (containsSuspicious) {
            return new FileViolation(file, "filename matches suspicious list: " + fileName, FileViolationLevel.SUSPICIOUS);
        }
        return null;
    }

    /**
     * Start WatchService monitoring in background thread.
     */
    public void start() {
        if (running) return;
        running = true;

        // Start native monitor if available
        if (NativeBridge.isLoaded()) {
            try {
                NativeBridge.startFileMonitor();
                System.out.println("[Anticheat] Native file monitor started");
            } catch (Throwable t) {
                System.err.println("[Anticheat] Failed to start native file monitor: " + t.getMessage());
            }
        }

        // Java WatchService
        try {
            watchService = FileSystems.getDefault().newWatchService();
            modsDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "anticheat-file-monitor");
            t.setDaemon(true);
            return t;
        });

        executor.submit(() -> {
            while (running) {
                try {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path fileName = ev.context();
                        Path fullPath = modsDir.resolve(fileName);
                        // Delay a bit to ensure file is fully written
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        FileViolation v = checkFile(fullPath);
                        if (v != null) {
                            synchronized (pendingViolations) {
                                pendingViolations.add(v);
                            }
                            System.out.println("[Anticheat] File violation detected: " + v);
                        }

                        // Also check native violations if any
                        if (NativeBridge.isLoaded()) {
                            String[] nativeViolations = NativeBridge.safeGetFileViolations();
                            for (String nv : nativeViolations) {
                                synchronized (pendingViolations) {
                                    pendingViolations.add(new FileViolation(Paths.get(nv), "native monitor flagged", FileViolationLevel.CRITICAL));
                                }
                            }
                        }
                    }
                    boolean valid = key.reset();
                    if (!valid) break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        System.out.println("[Anticheat] FileMonitor WatchService started for " + modsDir);
    }

    public void stop() {
        running = false;
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (NativeBridge.isLoaded()) {
            try { NativeBridge.stopFileMonitor(); } catch (Throwable ignored) {}
        }
    }

    public List<FileViolation> pollViolations() {
        synchronized (pendingViolations) {
            List<FileViolation> copy = new ArrayList<>(pendingViolations);
            pendingViolations.clear();
            return copy;
        }
    }

    /**
     * Polls both Java and native violations.
     */
    public List<FileViolation> pollAllViolations() {
        List<FileViolation> all = new ArrayList<>(pollViolations());
        if (NativeBridge.isLoaded()) {
            String[] nativeVals = NativeBridge.safeGetFileViolations();
            for (String s : nativeVals) {
                all.add(new FileViolation(Paths.get(s), "native flag", FileViolationLevel.CRITICAL));
            }
        }
        return all;
    }
}
