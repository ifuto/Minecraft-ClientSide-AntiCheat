package com.anticheat.client.detection;

import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.util.Config;

public final class InputAuthenticity {

    private final Config config;
    private volatile long lastJavaMouseClick = 0;
    private volatile long lastJavaKeyPress = 0;
    private int syntheticClickStreak = 0;
    private int injectedStreak = 0;
    private static final int SYNTHETIC_THRESHOLD = 15;
    private static final int INJECTED_THRESHOLD = 10;

    public InputAuthenticity(Config config) { this.config = config; }

    public void onAttack() {
        long now = System.currentTimeMillis();
        long lastHwMouse = NativeBridge.isLoaded() ? NativeBridge.safeGetLastHardwareMouse() : lastJavaMouseClick;
        boolean injected = NativeBridge.isLoaded() ? NativeBridge.wasLastInputInjected() : false;
        if (injected) {
            injectedStreak++;
            System.out.println("[Anticheat][InputAuth][Safe] Injected detected streak=" + injectedStreak);
            if (injectedStreak >= INJECTED_THRESHOLD) {
                flag("MOUSE_INJECTED", "injected SendInput");
                injectedStreak = 0;
            }
            return;
        } else {
            injectedStreak = Math.max(0, injectedStreak-1);
        }
        long delta = now - lastHwMouse;
        if (lastHwMouse == 0 || delta > config.inputAuthWindowMs) {
            syntheticClickStreak++;
            System.out.println("[Anticheat][Safe] Synthetic log only delta=" + delta + " streak=" + syntheticClickStreak);
            if (com.anticheat.client.AntiCheatClientMod.getInstance()!=null && com.anticheat.client.AntiCheatClientMod.getInstance().getEvidenceCollector()!=null) {
                com.anticheat.client.AntiCheatClientMod.getInstance().getEvidenceCollector().addEvidence("INPUT_SYNTHETIC_LOG", "delta=" + delta);
            }
            if (syntheticClickStreak >= SYNTHETIC_THRESHOLD) {
                System.out.println("[Anticheat][Safe] Synthetic threshold reached but NOT banning");
                syntheticClickStreak = 0;
            }
        } else {
            syntheticClickStreak = Math.max(0, syntheticClickStreak-1);
        }
    }

    public void onJavaMouseClick() { lastJavaMouseClick = System.currentTimeMillis(); }
    public void onJavaKeyPress() { lastJavaKeyPress = System.currentTimeMillis(); }

    public void onUseItem() {
        long now = System.currentTimeMillis();
        long lastHwMouse = NativeBridge.isLoaded() ? NativeBridge.safeGetLastHardwareMouse() : lastJavaMouseClick;
        long delta = now - lastHwMouse;
        if (lastHwMouse == 0 || delta > config.inputAuthWindowMs) {
            syntheticClickStreak++;
            System.out.println("[Anticheat][Safe] Synthetic use log only delta=" + delta);
        } else {
            syntheticClickStreak = Math.max(0, syntheticClickStreak-1);
        }
    }

    private void flag(String type, String detail) {
        com.anticheat.client.network.ViolationReporter.reportInputViolation(type, detail);
    }

    public static class MouseMovementAnalyzer {
        private static final int HISTORY = 20;
        private final float[] deltaYawHistory = new float[HISTORY];
        private final float[] deltaPitchHistory = new float[HISTORY];
        private int index = 0;
        private int count = 0;
        public void addDelta(float yawDelta, float pitchDelta) {
            deltaYawHistory[index]=yawDelta; deltaPitchHistory[index]=pitchDelta;
            index=(index+1)%HISTORY; if(count<HISTORY) count++;
        }
        public boolean isSuspiciousLinear() { return false; }
    }
}
