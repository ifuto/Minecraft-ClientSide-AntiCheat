package com.anticheat.client;

import com.anticheat.client.detection.*;
import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.network.SelfBanPacketSender;
import com.anticheat.client.network.ViolationReporter;
import com.anticheat.client.util.Config;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for client-side anticheat.
 *
 * Lifecycle:
 * - onInitializeClient: setup native, config, detectors
 * - On client tick: run lightweight checks (rotation, input authenticity polling)
 * - Periodic background tasks: package scan, memory guard, file monitor, heartbeat
 *
 * Design considerations:
 * - All detectors run in separate threads where possible to avoid blocking render thread.
 * - ViolationReporter is central and triggers self-ban packet.
 *
 * Additional detection idea (future):
 * - Render hook: detect external overlay (e.g., cheat GUI) via OpenGL context check
 * - Movement check: Timer, Speed, Fly detection (server-side authoritative but client can pre-check)
 *
 * Threat model documentation lives in docs/THREAT_MODEL.md
 */
public class AntiCheatClientMod implements ClientModInitializer {

    public static final String MOD_ID = "anticheat-client";

    private Config config;
    private PackageScanner packageScanner;
    private FileMonitor fileMonitor;
    private MemoryGuard memoryGuard;
    private InputAuthenticity inputAuthenticity;
    private RotationAnalyzer rotationAnalyzer;
    private CPSAnalyzer cpsAnalyzer;
    private SelfBanPacketSender packetSender;

    private ScheduledExecutorService scheduler;

    // Exposed for mixins
    private static AntiCheatClientMod INSTANCE;

    public static AntiCheatClientMod getInstance() {
        return INSTANCE;
    }

    public InputAuthenticity getInputAuthenticity() {
        return inputAuthenticity;
    }

    public CPSAnalyzer getCpsAnalyzer() {
        return cpsAnalyzer;
    }

    public RotationAnalyzer getRotationAnalyzer() {
        return rotationAnalyzer;
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        System.out.println("[Anticheat] Initializing Client-side Anticheat");

        config = new Config();

        // Native bridge
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        boolean nativeLoaded = false;
        try {
            nativeLoaded = NativeBridge.initialize(modsDir);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("[Anticheat] Native loaded: " + nativeLoaded + (nativeLoaded ? "" : " error: " + NativeBridge.getLastError()));

        if (!nativeLoaded && config.requireNative) {
            System.err.println("[Anticheat] Native required but failed to load, reporting tamper");
            // If requireNative, treat missing native as tamper
        }

        // Install input hooks if native available
        if (nativeLoaded) {
            try {
                NativeBridge.installInputHooks();
                System.out.println("[Anticheat] Input hooks installed");
            } catch (Throwable t) {
                System.err.println("[Anticheat] Failed to install input hooks: " + t.getMessage());
            }
        }

        packetSender = new SelfBanPacketSender(config);
        ViolationReporter.init(config, packetSender);

        packageScanner = new PackageScanner();
        fileMonitor = new FileMonitor();
        memoryGuard = new MemoryGuard();
        inputAuthenticity = new InputAuthenticity(config);
        rotationAnalyzer = new RotationAnalyzer(config);
        cpsAnalyzer = new CPSAnalyzer(config.cpsWindowSize, config.cpsVarianceThreshold);

        // Initial scans
        if (config.enablePackageScan) {
            System.out.println("[Anticheat] Running initial package scan...");
            List<PackageScanner.Violation> pkgs = packageScanner.scan();
            for (PackageScanner.Violation v : pkgs) {
                System.out.println("[Anticheat] Package violation: " + v);
                if (v.level == PackageScanner.ViolationLevel.CRITICAL) {
                    ViolationReporter.reportPackageViolation(v.packageName, v.level.name());
                }
            }
        }

        if (config.enableFileMonitor) {
            System.out.println("[Anticheat] Running initial file scan...");
            List<FileMonitor.FileViolation> files = fileMonitor.initialScan();
            for (FileMonitor.FileViolation fv : files) {
                System.out.println("[Anticheat] File violation (initial): " + fv);
                if (fv.level == FileMonitor.FileViolationLevel.CRITICAL) {
                    ViolationReporter.reportFileViolation(fv.file.toString(), fv.level.name());
                }
            }
            fileMonitor.start();
        }

        // Scheduler for periodic checks
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "anticheat-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Package scan every 30 seconds
        if (config.enablePackageScan) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    List<PackageScanner.Violation> v = packageScanner.scan();
                    for (PackageScanner.Violation vio : v) {
                        if (vio.level == PackageScanner.ViolationLevel.CRITICAL) {
                            System.out.println("[Anticheat] Periodic scan found critical: " + vio);
                            ViolationReporter.reportPackageViolation(vio.packageName, vio.level.name());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 30, 30, TimeUnit.SECONDS);
        }

        // Memory guard every 20 seconds
        if (config.enableMemoryGuard) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    List<MemoryGuard.MemoryViolation> mv = memoryGuard.scan();
                    for (MemoryGuard.MemoryViolation m : mv) {
                        System.out.println("[Anticheat] Memory violation: " + m);
                        ViolationReporter.reportMemoryViolation(m.moduleOrReason, m.detail);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 10, 20, TimeUnit.SECONDS);
        }

        // File monitor polling (also event-driven, but periodic check for native queue)
        if (config.enableFileMonitor) {
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    List<FileMonitor.FileViolation> fvs = fileMonitor.pollAllViolations();
                    for (FileMonitor.FileViolation fv : fvs) {
                        System.out.println("[Anticheat] File violation (polled): " + fv);
                        if (fv.level == FileMonitor.FileViolationLevel.CRITICAL) {
                            ViolationReporter.reportFileViolation(fv.file.toString(), fv.level.name());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, 5, 5, TimeUnit.SECONDS);
        }

        // Heartbeat
        scheduler.scheduleAtFixedRate(() -> {
            try {
                packetSender.sendHeartbeat();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 5, config.heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        // Client tick events for rotation tracking
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (client.player != null) {
                    // Rotation analyzer
                    rotationAnalyzer.onRotationTick(client.player.getYaw(), client.player.getPitch(), System.currentTimeMillis());

                    // Also try to set player info for packet sender if not set
                    if (client.player.getGameProfile() != null) {
                        String uuid = client.player.getUuidAsString();
                        String name = client.player.getGameProfile().getName();
                        packetSender.setPlayerInfo(uuid, name);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // On join, send handshake
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            System.out.println("[Anticheat] Joined server, sending handshake");
            try {
                if (client.player != null && client.player.getGameProfile() != null) {
                    packetSender.setPlayerInfo(client.player.getUuidAsString(), client.player.getGameProfile().getName());
                }
                packetSender.sendHandshake();
                packetSender.sendHeartbeat();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        System.out.println("[Anticheat] Client anticheat initialized. Threat model: docs/THREAT_MODEL.md");
    }

    // Called from mixin when attack happens
    public void onPlayerAttack() {
        try {
            if (inputAuthenticity != null) inputAuthenticity.onAttack();
            if (cpsAnalyzer != null) cpsAnalyzer.onClick(System.currentTimeMillis());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onPlayerUseItem() {
        try {
            if (inputAuthenticity != null) inputAuthenticity.onUseItem();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onMouseClick() {
        if (inputAuthenticity != null) inputAuthenticity.onJavaMouseClick();
    }

    public void onKeyPress() {
        if (inputAuthenticity != null) inputAuthenticity.onJavaKeyPress();
    }
}
