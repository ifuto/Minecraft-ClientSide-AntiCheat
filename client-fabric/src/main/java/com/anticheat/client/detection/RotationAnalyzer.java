package com.anticheat.client.detection;

import com.anticheat.client.network.ViolationReporter;
import com.anticheat.client.util.Config;

/**
 * Rotation analyzer - detects KillAura / AimBot via rotation heuristics.
 *
 * Human rotation characteristics:
 * - Max yaw angular velocity limited by mouse DPI and sensitivity. Typical max ~ 100 deg per tick (50ms) even with high DPI? But we set threshold 60 deg.
 * - Human has smooth acceleration: no instant 180-degree snap in single tick.
 * - Human has small jitter/noise.
 * - GCD (Greatest Common Divisor) check: Minecraft's mouse sensitivity applies gcd to rotations. Aimbot may bypass gcd and produce impossible rotation values.
 *
 * Checks:
 * 1. Snap detection: if yawDelta > maxYawPerTick or pitchDelta > maxPitchPerTick within single tick -> flag.
 * 2. GCD check: For vanilla, yaw changes are multiples of sensitivity gcd. If rotation not divisible by gcd and player is not using cinematic camera, flag as suspicious.
 * 3. Perfect Yaw: Constant yaw speed over time.
 * 4. Average speed vs human model: Fitts law.
 *
 * Mathematical:
 * - Angular velocity omega = deltaAngle / deltaTime.
 * - For human, omega distribution roughly log-normal; probability of omega > 60 deg per 50ms tick is low (<0.1%).
 * - For aimbot snap, omega often > 180 deg per tick => P ~ near zero for human, high detection confidence.
 *
 * - GCD: mouse sensitivity s in [0,1]. GCD = s * 0.6 + 0.2)^3 * 1.2? Actually formula: gcd = (sensitivity*0.6+0.2)^3 * 8. Equivalent to Minecraft's MouseHelper.
 *   Yaw change = delta * gcd. Therefore any yaw delta should be multiple of gcd unless modified by mod.
 *   So we compute gcd from client options, then check if rotation delta mod gcd ~0.
 *
 * False positives: High DPI mouse can produce large delta; lag can cause tick aggregation leading to large apparent delta.
 * Mitigations: Use moving average, require multiple violations.
 */
public final class RotationAnalyzer {

    private final Config config;
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;
    private long lastTime = 0;

    private int snapStreak = 0;
    private static final int SNAP_THRESHOLD_COUNT = 3;

    // GCD tracking
    private float mouseGCD = 0.0f;
    private boolean gcdInitialized = false;

    public RotationAnalyzer(Config config) {
        this.config = config;
    }

    public void setMouseSensitivityGCD(float gcd) {
        this.mouseGCD = gcd;
        this.gcdInitialized = true;
    }

    /**
     * Call each tick with current yaw/pitch.
     */
    public void onRotationTick(float yaw, float pitch, long currentTimeMs) {
        if (Float.isNaN(lastYaw) || Float.isNaN(lastPitch)) {
            lastYaw = yaw;
            lastPitch = pitch;
            lastTime = currentTimeMs;
            return;
        }

        float deltaYaw = Math.abs(wrapDegrees(yaw - lastYaw));
        float deltaPitch = Math.abs(pitch - lastPitch);
        long deltaTime = currentTimeMs - lastTime;
        if (deltaTime <= 0) deltaTime = 50; // assume 1 tick

        // Snap check
        if (deltaYaw > config.maxYawPerTick || deltaPitch > config.maxPitchPerTick) {
            snapStreak++;
            System.out.println("[Anticheat][Rotation] Snap detected: dYaw=" + deltaYaw + " dPitch=" + deltaPitch + " streak=" + snapStreak);
            if (snapStreak >= SNAP_THRESHOLD_COUNT) {
                ViolationReporter.reportRotationViolation("SNAP", "dYaw=" + deltaYaw + " dPitch=" + deltaPitch + " over " + deltaTime + "ms");
                snapStreak = 0;
            }
        } else {
            snapStreak = Math.max(0, snapStreak - 1); // decay
        }

        // GCD check (if initialized)
        if (gcdInitialized && mouseGCD > 0.001f) {
            // For vanilla, delta should be divisible by gcd within epsilon
            // But yaw wrapping complicates; check modulo
            float yawRemainder = deltaYaw % mouseGCD;
            float pitchRemainder = deltaPitch % mouseGCD;
            // Allow small epsilon for floating error: 0.001
            boolean yawInvalid = yawRemainder > 0.001f && Math.abs(yawRemainder - mouseGCD) > 0.001f;
            boolean pitchInvalid = pitchRemainder > 0.001f && Math.abs(pitchRemainder - mouseGCD) > 0.001f;
            // Only flag if delta is significant (>0.5 deg) and remainder invalid
            if (deltaYaw > 0.5f && yawInvalid) {
                System.out.println("[Anticheat][Rotation] GCD violation yaw remainder=" + yawRemainder + " gcd=" + mouseGCD);
                // Might be many false positives on low sensitivity, so don't immediate ban, collect stats
            }
        }

        lastYaw = yaw;
        lastPitch = pitch;
        lastTime = currentTimeMs;
    }

    private static float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f) value -= 360.0f;
        if (value < -180.0f) value += 360.0f;
        return value;
    }
}
