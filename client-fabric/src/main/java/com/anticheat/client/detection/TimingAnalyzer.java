package com.anticheat.client.detection;

import com.anticheat.client.network.ViolationReporter;

/**
 * Evolution: High-resolution timing analysis to detect Timer cheat and debugger presence via RDTSC.
 *
 * Timer cheat speeds up client tick rate. We measure real time vs game time drift.
 *
 * Method:
 * - Every tick, record System.nanoTime(). Expected 50ms per tick.
 * - Compute cumulative drift: game ticks * 50ms vs real elapsed.
 * - If drift exceeds threshold (e.g., game ahead by >500ms over 10 sec), flag Timer.
 *
 * Also detects debugger via timing: debugger causes irregular long pauses (breakpoints).
 *
 * Math:
 * - Let t_real[i] be real nanoTime at tick i, t_game[i]=i*50ms.
 * - Drift D = t_game - t_real. For legit, D ~ small random walk. For Timer 2x, D grows linearly ~ (speed-1)*time.
 * - Over window W=200 ticks (10 sec), D should be <200ms. If D>1000ms, flag.
 */
public final class TimingAnalyzer {

    private long startRealNano = 0;
    private long startGameTicks = 0;
    private long tickCount = 0;

    private static final double EXPECTED_TICK_MS = 50.0;
    private static final double DRIFT_THRESHOLD_MS = 800.0; // 0.8 sec over window
    private static final int WINDOW_TICKS = 200; // 10 sec

    private double accumulatedDrift = 0;

    public void onTick() {
        long nowNano = System.nanoTime();
        if (startRealNano == 0) {
            startRealNano = nowNano;
            startGameTicks = 0;
            tickCount = 0;
            return;
        }
        tickCount++;

        double realElapsedMs = (nowNano - startRealNano) / 1_000_000.0;
        double gameElapsedMs = tickCount * EXPECTED_TICK_MS;
        double drift = gameElapsedMs - realElapsedMs;

        // Only check after window
        if (tickCount % WINDOW_TICKS == 0) {
            if (Math.abs(drift) > DRIFT_THRESHOLD_MS) {
                // Positive drift means game time ahead of real time => Timer speeding up ticks
                // Negative drift could be lag or debugger pause
                if (drift > DRIFT_THRESHOLD_MS) {
                    ViolationReporter.reportTamper("TIMER_DRIFT", "drift=" + String.format("%.1f", drift) + "ms over " + WINDOW_TICKS + " ticks (game ahead)");
                } else if (drift < -2000) {
                    // Large negative drift: possible debugger pause or lag spike
                    ViolationReporter.reportTamper("DEBUG_TIMING", "drift=" + String.format("%.1f", drift) + "ms (game behind, possible debugger)");
                }
                // Reset to avoid repeated flagging from same drift
                startRealNano = nowNano;
                tickCount = 0;
            }
        }
    }

    public void reset() {
        startRealNano = 0;
        tickCount = 0;
    }
}
