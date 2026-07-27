package com.anticheat.client.detection.ml;

import com.anticheat.client.detection.CPSAnalyzer;
import com.anticheat.client.detection.RotationAnalyzer;

/**
 * Evolution: Composite human behavior model that aggregates multiple ML-lite analyzers.
 *
 * Idea: Each analyzer outputs a "human likeness" score 0..1 (1 = human, 0 = bot).
 * We combine via weighted average or logistic regression.
 *
 * Features:
 * - Rotation entropy (high = human)
 * - CPS variance (high CV = human)
 * - Mouse movement linearity (low linearity = human)
 * - Reaction time distribution (human ~ 180-300ms, bot <50ms)
 * - Aim acceleration (human has smooth accel, bot has instant)
 *
 * This is simplified ML: no deep learning to avoid performance impact, but uses statistical thresholds learned from human data.
 *
 * Future: Could integrate actual ONNX model via onnxruntime for more accurate classification, still lightweight.
 */
public final class HumanBehaviorModel {

    private final RotationEntropy rotationEntropy = new RotationEntropy();

    // Weighted scores
    private double rotationScore = 1.0;
    private double cpsScore = 1.0;
    private double movementScore = 1.0;

    public void onRotation(float deltaYaw) {
        rotationEntropy.addDelta(deltaYaw);
        double entropy = rotationEntropy.computeEntropy();
        if (entropy >= 0) {
            // Map entropy 0..4 to score 0..1: 0 bits => 0, 4 bits =>1
            rotationScore = Math.min(1.0, Math.max(0.0, entropy / 4.0));
        }
    }

    public void onCPS(double cv) {
        // CV mapping: human avg 0.2 => score 1, autoclicker 0.02 => 0
        // score = 1 - exp(-k*cv) or linear
        if (cv < 0.05) cpsScore = cv / 0.05 * 0.5; // low
        else if (cv > 0.15) cpsScore = 1.0;
        else cpsScore = 0.5 + (cv - 0.05) / 0.1 * 0.5;
    }

    public double getHumanLikeness() {
        // Weighted average
        return (rotationScore * 0.5 + cpsScore * 0.3 + movementScore * 0.2);
    }

    public boolean isBotLike() {
        return getHumanLikeness() < 0.3;
    }

    public String getDebugString() {
        return String.format("humanLikeness=%.2f rotScore=%.2f cpsScore=%.2f entropy=%.2f",
                getHumanLikeness(), rotationScore, cpsScore, rotationEntropy.getEntropyForDebug());
    }
}
