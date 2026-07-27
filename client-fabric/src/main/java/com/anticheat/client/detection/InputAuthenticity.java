package com.anticheat.client.detection;

import com.anticheat.client.nativebridge.NativeBridge;
import com.anticheat.client.util.Config;

/**
 * Detects whether input is truly from hardware device vs mod-generated synthetic input.
 *
 * Requirement #4: "その操作がしっかりデバイスによって行われたものか(Modにより生成されたものなのか判別)"
 *
 * Techniques:
 *
 * 1. Low-level hook correlation (Windows):
 *    - DLL installs WH_KEYBOARD_LL and WH_MOUSE_LL via SetWindowsHookEx.
 *    - These hooks receive real hardware input and can distinguish injected events via flags LLMHF_INJECTED / LLKHF_INJECTED (set when SendInput or keybd_event used).
 *    - Mods that simulate input via Minecraft's setKeyPressed or via Robot / SendInput will be flagged as injected (LLMHF_INJECTED) or as lacking recent hardware event.
 *    - We store last hardware input timestamp in native. In Java, when an action occurs (attack, use), we check delta to last hardware event. If > threshold (e.g., 150ms), we flag as synthetic.
 *    - Additionally, if last event was marked as injected, we flag.
 *
 * 2. Limitation: Driver-level spoof (e.g., Arduino as HID, or kernel driver that crafts input not marked as injected) bypasses LL hook injected flag. Mitigation in (3).
 *
 * 3. Statistical analysis:
 *    - Human mouse movement is not perfectly linear, has noise, follows Fitts' law, has variable acceleration.
 *    - We can capture mouse delta history and compute linearity via R², entropy, jaggedness.
 *    - For now, simpler: track rotation deltas and detect perfect linearity or zero variance.
 *
 * 4. CPS & interaction timing:
 *    - Handled in CPSAnalyzer and RotationAnalyzer, but InputAuthenticity provides recent hardware timestamps.
 *
 * Mathematical model:
 *    Let t_hw be time of last hardware input (mouse click, key press, mouse move).
 *    Let t_action be time of game action (attack).
 *    For authentic input, |t_action - t_hw| <= epsilon (epsilon = inputAuthWindowMs, e.g., 150ms).
 *    For synthetic input, t_hw is old or zero -> delta large -> flagged.
 *
 *    False positive risk: If player uses touchscreen or accessibility tools, hardware event timing may vary.
 *    Mitigation: Allow slightly larger window (200ms) and require multiple consecutive violations before ban.
 *
 *    For injected flag:
 *    - Windows defines flag: if (flags & LLMHF_INJECTED) -> injected.
 *    - Probability of false positive for real hardware: ~0 (hardware never marked injected).
 *    - Attacker using driver bypass: false negative, not flagged as injected. Then we rely on timing correlation.
 */
public final class InputAuthenticity {

    private final Config config;

    // For fallback Java-only mode (without native hooks), we can still attempt to track via GLFW?
    // GLFW polling doesn't distinguish synthetic, but we can at least note lack of correlation.

    private volatile long lastJavaMouseClick = 0;
    private volatile long lastJavaKeyPress = 0;

    // Violation counters for debouncing
    private int syntheticClickStreak = 0;
    private static final int SYNTHETIC_THRESHOLD = 5; // need 5 consecutive synthetic actions to flag

    public InputAuthenticity(Config config) {
        this.config = config;
    }

    /**
     * Called from mixin when player attacks (left click).
     */
    public void onAttack() {
        long now = System.currentTimeMillis();
        long lastHwMouse = NativeBridge.isLoaded() ? NativeBridge.safeGetLastHardwareMouse() : lastJavaMouseClick;
        boolean injected = NativeBridge.isLoaded() ? NativeBridge.wasLastInputInjected() : false;

        if (injected) {
            syntheticClickStreak++;
            System.out.println("[Anticheat][InputAuth] Injected input detected! streak=" + syntheticClickStreak);
            if (syntheticClickStreak >= SYNTHETIC_THRESHOLD) {
                flag("MOUSE_INJECTED", "last input flagged as injected (SendInput)");
                syntheticClickStreak = 0;
            }
            return;
        }

        long delta = now - lastHwMouse;
        // If no hardware event ever, or delta too large, it's synthetic
        if (lastHwMouse == 0 || delta > config.inputAuthWindowMs) {
            syntheticClickStreak++;
            System.out.println("[Anticheat][InputAuth] Synthetic click? now=" + now + " lastHw=" + lastHwMouse + " delta=" + delta + "ms streak=" + syntheticClickStreak);
            if (syntheticClickStreak >= SYNTHETIC_THRESHOLD) {
                flag("SYNTHETIC_CLICK", "no recent hardware mouse click, delta=" + delta + "ms");
                syntheticClickStreak = 0;
            }
        } else {
            // authentic, reset streak
            syntheticClickStreak = 0;
        }
    }

    /**
     * Called when Java receives mouse click via GLFW (fallback).
     */
    public void onJavaMouseClick() {
        lastJavaMouseClick = System.currentTimeMillis();
    }

    public void onJavaKeyPress() {
        lastJavaKeyPress = System.currentTimeMillis();
    }

    /**
     * Called when player uses item (right click)
     */
    public void onUseItem() {
        // Similar logic to onAttack but for right click
        long now = System.currentTimeMillis();
        long lastHwMouse = NativeBridge.isLoaded() ? NativeBridge.safeGetLastHardwareMouse() : lastJavaMouseClick;
        long delta = now - lastHwMouse;
        if (lastHwMouse == 0 || delta > config.inputAuthWindowMs) {
            syntheticClickStreak++;
            if (syntheticClickStreak >= SYNTHETIC_THRESHOLD) {
                flag("SYNTHETIC_USE", "no recent hardware for use item, delta=" + delta);
                syntheticClickStreak = 0;
            }
        } else {
            syntheticClickStreak = 0;
        }
    }

    private void flag(String type, String detail) {
        // Forward to violation handler (SelfBanPacket)
        // We'll use static access to ViolationReporter
        com.anticheat.client.network.ViolationReporter.reportInputViolation(type, detail);
    }

    /**
     * Mouse movement authenticity.
     * Computes linearity of recent mouse moves.
     * If movement is perfectly linear (e.g., robot), flag.
     */
    public static class MouseMovementAnalyzer {
        private static final int HISTORY = 20;
        private final float[] deltaYawHistory = new float[HISTORY];
        private final float[] deltaPitchHistory = new float[HISTORY];
        private int index = 0;
        private int count = 0;

        public void addDelta(float yawDelta, float pitchDelta) {
            deltaYawHistory[index] = yawDelta;
            deltaPitchHistory[index] = pitchDelta;
            index = (index + 1) % HISTORY;
            if (count < HISTORY) count++;
        }

        public boolean isSuspiciousLinear() {
            if (count < 10) return false;
            // Compute variance of yaw/pitch
            // Perfect robot often has zero variance in pitch when doing horizontal sweep, or constant ratio
            // Compute entropy or correlation
            // Simplified: if all deltas identical within epsilon -> suspicious
            float firstYaw = deltaYawHistory[0];
            boolean allSameYaw = true;
            for (int i = 1; i < count; i++) {
                if (Math.abs(deltaYawHistory[i] - firstYaw) > 0.01f) {
                    allSameYaw = false;
                    break;
                }
            }
            if (allSameYaw && Math.abs(firstYaw) > 0.1f) {
                return true; // constant yaw movement impossible for human
            }
            // Check correlation: yaw and pitch ratio constant
            // For human, ratio varies; for linear aimbot, ratio fixed
            // Compute covariance?
            // Simplified check: if pitch variance very low while yaw moving -> possible aimbot
            return false;
        }
    }
}
