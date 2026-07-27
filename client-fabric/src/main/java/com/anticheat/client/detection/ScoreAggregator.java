package com.anticheat.client.detection;

import com.anticheat.client.network.ViolationReporter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evolution: Violation scoring system instead of instant ban on first detection.
 * Each violation type has weight, decays over time, and accumulates.
 * Only when total score > threshold, ban is triggered.
 *
 * This reduces false positives: single anomaly doesn't ban, but repeated pattern does.
 *
 * Math: Exponential decay: score(t) = score0 * e^(-lambda * deltaTime)
 *       lambda = ln(2)/halfLife, halfLife = 60 seconds.
 *       Effective score accumulates with decay.
 *
 * Performance: O(1) per violation, scheduled decay every second.
 */
public final class ScoreAggregator {

    public enum ViolationType {
        PACKAGE_CRITICAL(50),
        FILE_CRITICAL(50),
        MEMORY_CRITICAL(40),
        INPUT_SYNTHETIC(15),
        ROTATION_SNAP(20),
        CPS_AUTOCLICKER(20),
        MOVEMENT_FLY(25),
        MOVEMENT_SPEED(20),
        MOVEMENT_TIMER(30),
        WORLD_REACH(15),
        BYTECODE_CRITICAL(40),
        CLASSLOADER(25),
        MIXIN(20),
        PACKET_SPAM(10),
        HWID(5);

        public final int weight;
        ViolationType(int w) { weight = w; }
    }

    private final Map<ViolationType, Double> scores = new ConcurrentHashMap<>();
    private double totalScore = 0.0;
    private long lastDecayTime = System.currentTimeMillis();

    private static final double HALF_LIFE_MS = 60000.0; // 60 sec half life
    private static final double LAMBDA = Math.log(2) / HALF_LIFE_MS;
    private static final double BAN_THRESHOLD = 80.0;
    private static final double WARNING_THRESHOLD = 40.0;

    public synchronized void addViolation(ViolationType type, String detail) {
        decay();

        double current = scores.getOrDefault(type, 0.0);
        current += type.weight;
        scores.put(type, current);
        totalScore += type.weight;

        System.out.println("[ScoreAggregator] Violation " + type + " weight=" + type.weight + " total=" + String.format("%.1f", totalScore) + " detail=" + detail);

        if (totalScore >= BAN_THRESHOLD) {
            ViolationReporter.reportTamper("SCORE_THRESHOLD", "totalScore=" + totalScore + " threshold=" + BAN_THRESHOLD + " lastViolation=" + type + ":" + detail);
            // Reset after ban to avoid spam
            reset();
        } else if (totalScore >= WARNING_THRESHOLD) {
            System.out.println("[ScoreAggregator] Warning threshold reached: " + totalScore);
        }
    }

    private synchronized void decay() {
        long now = System.currentTimeMillis();
        double delta = now - lastDecayTime;
        if (delta <= 0) return;
        double factor = Math.exp(-LAMBDA * delta);
        totalScore *= factor;
        for (Map.Entry<ViolationType, Double> e : scores.entrySet()) {
            e.setValue(e.getValue() * factor);
        }
        lastDecayTime = now;
        // Remove tiny scores
        scores.entrySet().removeIf(entry -> entry.getValue() < 0.1);
        if (totalScore < 0.1) totalScore = 0;
    }

    public synchronized double getTotalScore() {
        decay();
        return totalScore;
    }

    public synchronized void reset() {
        scores.clear();
        totalScore = 0;
        lastDecayTime = System.currentTimeMillis();
    }
}
