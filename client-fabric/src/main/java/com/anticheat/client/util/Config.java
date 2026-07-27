package com.anticheat.client.util;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/**
 * Simple config for secret and thresholds.
 * Stored in config/anticheat-client.properties
 *
 * Contradiction note: If client controls config, attacker can change secret to avoid server verification.
 * Mitigation: Server generates secret per player session and sends challenge; client must respond.
 * But current simple implementation uses shared secret in config for HMAC.
 */
public final class Config {
    private static final Path CONFIG_PATH = Paths.get("config", "anticheat-client.properties");
    private Properties props = new Properties();

    public String hmacSecret = "change-me-in-production-256-bit-secret";
    public boolean requireNative = false;
    public long heartbeatIntervalMs = 30000;
    public long inputAuthWindowMs = 150; // max time between hardware event and action to be considered authentic
    public double maxYawPerTick = 60.0; // degrees, beyond this flagged as snap
    public double maxPitchPerTick = 45.0;
    public int cpsWindowSize = 20;
    public double cpsVarianceThreshold = 0.05; // CV below this = autoclicker
    public boolean enableSelfBan = true;
    public boolean enablePackageScan = true;
    public boolean enableFileMonitor = true;
    public boolean enableMemoryGuard = true;
    public boolean enableInputAuth = true;
    // Evolution toggles
    public boolean enableBytecodeScan = true;
    public boolean enableMovementCheck = true;
    public boolean enableWorldInteractionCheck = true;
    public boolean enableIntegrityCheck = true;
    public boolean enableAntiDebug = true;
    public boolean enableRenderGuard = true;
    public boolean enablePacketAnalysis = true;
    public boolean enableScoring = true;
    public long bytecodeScanIntervalMs = 120000;
    public long movementCheckIntervalMs = 50; // per tick but config
    public double maxHorizontalSpeed = 0.35;
    public int evidenceMaxSize = 2000;
    public double banThreshold = 80.0;

    public Config() {
        load();
    }

    private void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                props.load(Files.newInputStream(CONFIG_PATH));
                hmacSecret = props.getProperty("hmacSecret", hmacSecret);
                requireNative = Boolean.parseBoolean(props.getProperty("requireNative", String.valueOf(requireNative)));
                heartbeatIntervalMs = Long.parseLong(props.getProperty("heartbeatIntervalMs", String.valueOf(heartbeatIntervalMs)));
                inputAuthWindowMs = Long.parseLong(props.getProperty("inputAuthWindowMs", String.valueOf(inputAuthWindowMs)));
                maxYawPerTick = Double.parseDouble(props.getProperty("maxYawPerTick", String.valueOf(maxYawPerTick)));
                maxPitchPerTick = Double.parseDouble(props.getProperty("maxPitchPerTick", String.valueOf(maxPitchPerTick)));
                cpsWindowSize = Integer.parseInt(props.getProperty("cpsWindowSize", String.valueOf(cpsWindowSize)));
                cpsVarianceThreshold = Double.parseDouble(props.getProperty("cpsVarianceThreshold", String.valueOf(cpsVarianceThreshold)));
                enableSelfBan = Boolean.parseBoolean(props.getProperty("enableSelfBan", String.valueOf(enableSelfBan)));
                enablePackageScan = Boolean.parseBoolean(props.getProperty("enablePackageScan", String.valueOf(enablePackageScan)));
                enableFileMonitor = Boolean.parseBoolean(props.getProperty("enableFileMonitor", String.valueOf(enableFileMonitor)));
                enableMemoryGuard = Boolean.parseBoolean(props.getProperty("enableMemoryGuard", String.valueOf(enableMemoryGuard)));
                enableInputAuth = Boolean.parseBoolean(props.getProperty("enableInputAuth", String.valueOf(enableInputAuth)));
                enableBytecodeScan = Boolean.parseBoolean(props.getProperty("enableBytecodeScan", String.valueOf(enableBytecodeScan)));
                enableMovementCheck = Boolean.parseBoolean(props.getProperty("enableMovementCheck", String.valueOf(enableMovementCheck)));
                enableWorldInteractionCheck = Boolean.parseBoolean(props.getProperty("enableWorldInteractionCheck", String.valueOf(enableWorldInteractionCheck)));
                enableIntegrityCheck = Boolean.parseBoolean(props.getProperty("enableIntegrityCheck", String.valueOf(enableIntegrityCheck)));
                enableAntiDebug = Boolean.parseBoolean(props.getProperty("enableAntiDebug", String.valueOf(enableAntiDebug)));
                enableRenderGuard = Boolean.parseBoolean(props.getProperty("enableRenderGuard", String.valueOf(enableRenderGuard)));
                enablePacketAnalysis = Boolean.parseBoolean(props.getProperty("enablePacketAnalysis", String.valueOf(enablePacketAnalysis)));
                enableScoring = Boolean.parseBoolean(props.getProperty("enableScoring", String.valueOf(enableScoring)));
                bytecodeScanIntervalMs = Long.parseLong(props.getProperty("bytecodeScanIntervalMs", String.valueOf(bytecodeScanIntervalMs)));
                maxHorizontalSpeed = Double.parseDouble(props.getProperty("maxHorizontalSpeed", String.valueOf(maxHorizontalSpeed)));
                evidenceMaxSize = Integer.parseInt(props.getProperty("evidenceMaxSize", String.valueOf(evidenceMaxSize)));
                banThreshold = Double.parseDouble(props.getProperty("banThreshold", String.valueOf(banThreshold)));
            } else {
                save();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            props.setProperty("hmacSecret", hmacSecret);
            props.setProperty("requireNative", String.valueOf(requireNative));
            props.setProperty("heartbeatIntervalMs", String.valueOf(heartbeatIntervalMs));
            props.setProperty("inputAuthWindowMs", String.valueOf(inputAuthWindowMs));
            props.setProperty("maxYawPerTick", String.valueOf(maxYawPerTick));
            props.setProperty("maxPitchPerTick", String.valueOf(maxPitchPerTick));
            props.setProperty("cpsWindowSize", String.valueOf(cpsWindowSize));
            props.setProperty("cpsVarianceThreshold", String.valueOf(cpsVarianceThreshold));
            props.setProperty("enableSelfBan", String.valueOf(enableSelfBan));
            props.setProperty("enablePackageScan", String.valueOf(enablePackageScan));
            props.setProperty("enableFileMonitor", String.valueOf(enableFileMonitor));
            props.setProperty("enableMemoryGuard", String.valueOf(enableMemoryGuard));
            props.setProperty("enableInputAuth", String.valueOf(enableInputAuth));
            props.setProperty("enableBytecodeScan", String.valueOf(enableBytecodeScan));
            props.setProperty("enableMovementCheck", String.valueOf(enableMovementCheck));
            props.setProperty("enableWorldInteractionCheck", String.valueOf(enableWorldInteractionCheck));
            props.setProperty("enableIntegrityCheck", String.valueOf(enableIntegrityCheck));
            props.setProperty("enableAntiDebug", String.valueOf(enableAntiDebug));
            props.setProperty("enableRenderGuard", String.valueOf(enableRenderGuard));
            props.setProperty("enablePacketAnalysis", String.valueOf(enablePacketAnalysis));
            props.setProperty("enableScoring", String.valueOf(enableScoring));
            props.setProperty("bytecodeScanIntervalMs", String.valueOf(bytecodeScanIntervalMs));
            props.setProperty("maxHorizontalSpeed", String.valueOf(maxHorizontalSpeed));
            props.setProperty("evidenceMaxSize", String.valueOf(evidenceMaxSize));
            props.setProperty("banThreshold", String.valueOf(banThreshold));
            props.store(Files.newOutputStream(CONFIG_PATH), "Anticheat client config v2 evolved");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
