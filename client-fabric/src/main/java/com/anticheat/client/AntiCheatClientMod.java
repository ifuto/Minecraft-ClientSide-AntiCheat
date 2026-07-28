package com.anticheat.client;

import com.anticheat.client.detection.*;
import com.anticheat.client.nativebridge.EnhancedNativeBridge;
import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.network.SelfBanPacketSender;
import com.anticheat.client.network.ViolationReporter;
import com.anticheat.client.util.Config;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Safe Evolved Client-side Anticheat v3 - False-ban free
 * Removes false-ban prone: Movement, WorldInteraction, Packet, Timing, Render, AntiDebug, Bytecode, Mixin, ClassLoader, Stack, Rotation, CPS
 * Keeps: Package, File, Memory (critical only), Input (lenient), ModList, BProfile via DLL 10s after join
 */
public class AntiCheatClientMod implements ClientModInitializer {

    public static final String MOD_ID = "anticheat-client";

    private Config config;
    private PackageScanner packageScanner;
    private FileMonitor fileMonitor;
    private MemoryGuard memoryGuard;
    private InputAuthenticity inputAuthenticity;
    private SelfBanPacketSender packetSender;
    private ModListCollector modListCollector;
    private BackgroundAppCollector backgroundAppCollector;
    private HardwareFingerprint hardwareFingerprint;
    private IntegrityGuard integrityGuard;
    private EvidenceCollector evidenceCollector;
    private ScoreAggregator scoreAggregator;

    private ScheduledExecutorService scheduler;
    private static AntiCheatClientMod INSTANCE;

    public static AntiCheatClientMod getInstance() { return INSTANCE; }
    public InputAuthenticity getInputAuthenticity() { return inputAuthenticity; }
    public EvidenceCollector getEvidenceCollector() { return evidenceCollector; }
    public ScoreAggregator getScoreAggregator() { return scoreAggregator; }
    public com.anticheat.client.detection.MovementAnalyzer getMovementAnalyzer() { return null; }
    public com.anticheat.client.detection.TimingAnalyzer getTimingAnalyzer() { return null; }
    public com.anticheat.client.detection.PacketAnalyzer getPacketAnalyzer() { return null; }
    public com.anticheat.client.detection.WorldInteractionAnalyzer getWorldInteractionAnalyzer() { return null; }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        System.out.println("[Anticheat] Initializing SAFE Client-side Anticheat v3.0 - false-ban free");

        config = new Config();
        config.enableMovementCheck = false;
        config.enableWorldInteractionCheck = false;
        config.enablePacketAnalysis = false;
        config.enableAntiDebug = false;
        config.enableRenderGuard = false;
        config.enableBytecodeScan = false;
        config.inputAuthWindowMs = 300;
        config.maxYawPerTick = 90.0;
        config.maxPitchPerTick = 60.0;
        config.banThreshold = 150.0;

        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        boolean nativeLoaded = false;
        try { nativeLoaded = NativeBridge.initialize(modsDir); } catch (Exception e) { e.printStackTrace(); }
        System.out.println("[Anticheat] Native loaded: " + nativeLoaded);

        if (nativeLoaded) {
            try { NativeBridge.installInputHooks(); } catch (Throwable t) { System.err.println("[Anticheat] input hooks fail: " + t.getMessage()); }
        }

        packetSender = new SelfBanPacketSender(config);
        ViolationReporter.init(config, packetSender);

        packageScanner = new PackageScanner();
        fileMonitor = new FileMonitor();
        memoryGuard = new MemoryGuard();
        inputAuthenticity = new InputAuthenticity(config);
        modListCollector = new ModListCollector();
        backgroundAppCollector = new BackgroundAppCollector();
        hardwareFingerprint = new HardwareFingerprint();
        integrityGuard = new IntegrityGuard();
        evidenceCollector = new EvidenceCollector(2000);
        scoreAggregator = new ScoreAggregator();

        System.out.println("[Anticheat] HWID: " + hardwareFingerprint.getHwid());

        if (config.enablePackageScan) {
            List<PackageScanner.Violation> pkgs = packageScanner.scan();
            for (var v : pkgs) {
                evidenceCollector.addEvidence("PACKAGE", v.toString());
                scoreAggregator.addViolation(ScoreAggregator.ViolationType.PACKAGE_CRITICAL, v.packageName);
                if (v.level == PackageScanner.ViolationLevel.CRITICAL) ViolationReporter.reportPackageViolation(v.packageName, v.level.name());
            }
        }

        if (config.enableFileMonitor) {
            var files = fileMonitor.initialScan();
            for (var fv : files) {
                evidenceCollector.addEvidence("FILE", fv.toString());
                scoreAggregator.addViolation(ScoreAggregator.ViolationType.FILE_CRITICAL, fv.file.toString());
                if (fv.level == FileMonitor.FileViolationLevel.CRITICAL) ViolationReporter.reportFileViolation(fv.file.toString(), fv.level.name());
            }
            fileMonitor.start();
        }

        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "anticheat-safe-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<PackageScanner.Violation> v = packageScanner.scan();
                for (var vio : v) if (vio.level == PackageScanner.ViolationLevel.CRITICAL) {
                    evidenceCollector.addEvidence("PACKAGE_PERIODIC", vio.toString());
                    scoreAggregator.addViolation(ScoreAggregator.ViolationType.PACKAGE_CRITICAL, vio.packageName);
                    ViolationReporter.reportPackageViolation(vio.packageName, vio.level.name());
                }
            } catch (Exception e) { e.printStackTrace(); }
        }, 60, 60, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                var mv = memoryGuard.scan();
                for (var m : mv) {
                    String lower = (m.moduleOrReason + " " + m.detail).toLowerCase();
                    if (lower.contains("cheatengine") || lower.contains("x64dbg") || lower.contains("processhacker") || lower.contains("scylla") || lower.contains("artmoney")) {
                        evidenceCollector.addEvidence("MEMORY_CRITICAL", m.toString());
                        scoreAggregator.addViolation(ScoreAggregator.ViolationType.MEMORY_CRITICAL, m.moduleOrReason);
                        ViolationReporter.reportMemoryViolation(m.moduleOrReason, m.detail);
                    } else {
                        evidenceCollector.addEvidence("MEMORY_SUS", m.toString());
                        System.out.println("[Anticheat][Safe] Memory sus log only: " + m);
                    }
                }
                if (EnhancedNativeBridge.isAvailable()) {
                    for (String s : EnhancedNativeBridge.safeScanManualMapped()) {
                        evidenceCollector.addEvidence("MANUAL_MAP", s);
                        scoreAggregator.addViolation(ScoreAggregator.ViolationType.MEMORY_CRITICAL, s);
                        ViolationReporter.reportMemoryViolation("MANUAL_MAP", s);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }, 15, 30, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                var fvs = fileMonitor.pollAllViolations();
                for (var fv : fvs) if (fv.level == FileMonitor.FileViolationLevel.CRITICAL) {
                    evidenceCollector.addEvidence("FILE_POLL", fv.toString());
                    scoreAggregator.addViolation(ScoreAggregator.ViolationType.FILE_CRITICAL, fv.file.toString());
                    ViolationReporter.reportFileViolation(fv.file.toString(), fv.level.name());
                }
            } catch (Exception e) { e.printStackTrace(); }
        }, 5, 10, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try { packetSender.sendHeartbeat(); } catch (Exception e) { e.printStackTrace(); }
        }, 5, config.heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                var vios = integrityGuard.verify();
                for (var v : vios) {
                    evidenceCollector.addEvidence("INTEGRITY", v.reason + ":" + v.detail);
                    System.out.println("[Anticheat][Safe] Integrity log only: " + v.detail);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }, 120, 600, TimeUnit.SECONDS);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (client.player != null && client.player.getGameProfile() != null) {
                    packetSender.setPlayerInfo(client.player.getUuidAsString(), client.player.getGameProfile().getName());
                }
            } catch (Exception e) { e.printStackTrace(); }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            System.out.println("[Anticheat] Joined server, sending mod list and handshake");
            try {
                if (client.player != null && client.player.getGameProfile() != null) {
                    packetSender.setPlayerInfo(client.player.getUuidAsString(), client.player.getGameProfile().getName());
                }
                packetSender.sendHandshake();
                packetSender.sendHeartbeat();
                sendModList();
                scheduler.schedule(() -> {
                    try {
                        System.out.println("[Anticheat] 10s after join - collecting background apps via DLL");
                        sendAppList();
                    } catch (Exception e) { e.printStackTrace(); }
                }, 10, TimeUnit.SECONDS);
            } catch (Exception e) { e.printStackTrace(); }
        });

        System.out.println("[Anticheat] Safe anticheat initialized. Removed false-ban: movement/world/packet/timing/render/antidebug/bytecode/mixin/classloader/stack/rotation/cps. Kept safe + mod-list + bprofile");
    }

    private void sendModList() {
        try {
            var json = modListCollector.toJson();
            json.addProperty("playerUuid", packetSender.getPlayerUuid());
            json.addProperty("playerName", packetSender.getPlayerName());
            long ts = System.currentTimeMillis()/1000;
            json.addProperty("ts", ts);
            String sig = com.anticheat.client.network.ViolationReporter.HmacUtil.hmacSha256(config.hmacSecret, packetSender.getPlayerUuid() + "|MODLIST|" + ts);
            json.addProperty("sig", sig);
            packetSender.sendModList(json);
            evidenceCollector.addEvidence("MODLIST", "sent " + json.get("count").getAsInt());
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendAppList() {
        try {
            var json = backgroundAppCollector.toJson();
            json.addProperty("playerUuid", packetSender.getPlayerUuid());
            json.addProperty("playerName", packetSender.getPlayerName());
            long ts = System.currentTimeMillis()/1000;
            json.addProperty("ts", ts);
            String sig = com.anticheat.client.network.ViolationReporter.HmacUtil.hmacSha256(config.hmacSecret, packetSender.getPlayerUuid() + "|APPLIST|" + ts);
            json.addProperty("sig", sig);
            packetSender.sendAppList(json);
            evidenceCollector.addEvidence("APPLIST", "sent " + json.get("count").getAsInt());
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void onPlayerAttack() {
        try {
            if (inputAuthenticity != null) inputAuthenticity.onAttack();
            evidenceCollector.addEvidence("ATTACK", "attack at " + System.currentTimeMillis());
        } catch (Exception e) { e.printStackTrace(); }
    }
    public void onPlayerUseItem() { try { if (inputAuthenticity != null) inputAuthenticity.onUseItem(); } catch (Exception e) { e.printStackTrace(); } }
    public void onMouseClick() { if (inputAuthenticity != null) inputAuthenticity.onJavaMouseClick(); }
    public void onKeyPress() { if (inputAuthenticity != null) inputAuthenticity.onJavaKeyPress(); }
}
