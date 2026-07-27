package com.anticheat.client.detection.ml;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Evolution: ML-lite rotation entropy analysis.
 * Human rotation has high entropy (random micro-jitters), bot has low entropy (perfectly smooth or quantized).
 *
 * We compute:
 * - Shannon entropy of yaw delta distribution (binned)
 * - Approximate entropy or sample entropy of rotation sequence
 * - Hurst exponent via R/S analysis (human has 0.5-0.7, bot has ~1.0 or ~0)
 *
 * Simplified: compute entropy of deltaYaw over window 50.
 *
 * Human: entropy > 3.5 bits
 * AimBot: entropy < 2.0 bits (very predictable)
 */
public final class RotationEntropy {

    private final Deque<Float> yawDeltas = new ArrayDeque<>();
    private static final int WINDOW = 50;

    public void addDelta(float deltaYaw) {
        yawDeltas.addLast(deltaYaw);
        if (yawDeltas.size() > WINDOW) yawDeltas.pollFirst();
    }

    public double computeEntropy() {
        if (yawDeltas.size() < WINDOW) return -1; // not enough data

        // Bin deltas into 10 bins from -10 to +10 degrees (clamped)
        int bins = 10;
        int[] counts = new int[bins];
        for (float d : yawDeltas) {
            // Normalize: clamp -10..10
            float clamped = Math.max(-10f, Math.min(10f, d));
            int bin = (int) ((clamped + 10f) / 20f * bins);
            if (bin >= bins) bin = bins-1;
            if (bin < 0) bin = 0;
            counts[bin]++;
        }

        double entropy = 0.0;
        for (int count : counts) {
            if (count == 0) continue;
            double p = (double) count / WINDOW;
            entropy -= p * (Math.log(p) / Math.log(2)); // log2
        }
        return entropy;
    }

    public boolean isSuspicious() {
        double e = computeEntropy();
        if (e < 0) return false; // not enough data
        return e < 2.0; // low entropy => bot-like
    }

    public double getEntropyForDebug() {
        return computeEntropy();
    }
}
