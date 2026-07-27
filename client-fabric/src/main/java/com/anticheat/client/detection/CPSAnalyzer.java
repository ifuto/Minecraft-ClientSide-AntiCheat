package com.anticheat.client.detection;

import com.anticheat.client.network.ViolationReporter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * CPS (Clicks Per Second) analyzer for Autoclicker detection.
 *
 * Human model:
 * - Average human CPS: 6-12 for normal clicking, 12-16 for jitter, 16-20 for butterfly, >20 usually drag or autoclicker.
 * - Interval between clicks: human has variance (jitter). Let intervals I_i.
 * - Coefficient of Variation CV = stddev(I) / mean(I). For human, CV typically >0.1 - 0.3.
 * - Autoclicker tries to maintain constant CPS: CV < 0.05, often <0.02.
 * - Also autocorrelation: autoclicker intervals are periodic, autocorrelation at lag 1 high (~1), human lower.
 *
 * Detection:
 * - Maintain sliding window of last N click intervals (e.g., 20)
 * - Compute mean, stddev, CV
 * - If CV < threshold (config) and mean CPS > threshold (e.g., >12) -> flag.
 * - Also detect impossible CPS > 20 sustained (without drag) as suspicious.
 *
 * False positives:
 * - Legit drag clicking can achieve 20+ CPS with moderate variance, might be flagged incorrectly.
 * - Mitigation: require low CV AND high CPS, or very low CV even at moderate CPS.
 * - Butterfly clicking can have relatively consistent intervals but still human variance; set threshold conservatively.
 *
 * Mathematical correctness:
 * - Sample size 20 gives reasonable estimate of stddev, but small sample variance itself variable.
 * - Use unbiased estimator for stddev.
 * - For prevention of division by zero, mean >0 check.
 */
public final class CPSAnalyzer {

    private final int windowSize;
    private final double varianceThreshold;
    private final Deque<Long> clickTimes = new ArrayDeque<>();
    private final Deque<Long> intervals = new ArrayDeque<>();

    private int autoclickerStreak = 0;
    private static final int STREAK_TO_FLAG = 3;

    public CPSAnalyzer(int windowSize, double varianceThreshold) {
        this.windowSize = windowSize;
        this.varianceThreshold = varianceThreshold;
    }

    /**
     * Call on each left click (attack).
     * @return true if currently flagged as autoclicker (for immediate feedback)
     */
    public boolean onClick(long timestampMs) {
        if (!clickTimes.isEmpty()) {
            long last = clickTimes.peekLast();
            long interval = timestampMs - last;
            // Ignore intervals too large (>1000ms) as they break combo, not part of sustained CPS
            if (interval > 0 && interval < 1000) {
                intervals.addLast(interval);
                if (intervals.size() > windowSize) {
                    intervals.pollFirst();
                }
            }
        }
        clickTimes.addLast(timestampMs);
        if (clickTimes.size() > windowSize + 1) {
            clickTimes.pollFirst();
        }

        if (intervals.size() >= windowSize) {
            // Compute stats
            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            if (mean <= 0) return false;
            double variance = 0;
            for (long iv : intervals) {
                double diff = iv - mean;
                variance += diff * diff;
            }
            variance /= intervals.size();
            double stddev = Math.sqrt(variance);
            double cv = stddev / mean;
            double cps = 1000.0 / mean;

            // Debug print
            // System.out.printf("[Anticheat][CPS] cps=%.2f mean=%.2f cv=%.4f std=%.2f%n", cps, mean, cv, stddev);

            // Autoclicker condition: low CV and high CPS, OR extremely low CV even at moderate CPS
            boolean lowCV = cv < varianceThreshold;
            boolean highCPS = cps > 15.0;
            boolean veryLowCV = cv < 0.02;

            if ((lowCV && highCPS) || veryLowCV) {
                autoclickerStreak++;
                System.out.println("[Anticheat][CPS] Autoclicker suspected: cps=" + String.format("%.2f", cps) + " cv=" + String.format("%.4f", cv) + " streak=" + autoclickerStreak);
                if (autoclickerStreak >= STREAK_TO_FLAG) {
                    ViolationReporter.reportCPSViolation("AUTOCLICKER", "cps=" + String.format("%.2f", cps) + " cv=" + String.format("%.4f", cv));
                    autoclickerStreak = 0;
                    return true;
                }
            } else {
                // decay streak
                if (autoclickerStreak > 0) autoclickerStreak--;
            }

            // Also flag impossible CPS >25 sustained
            if (cps > 25.0) {
                System.out.println("[Anticheat][CPS] Impossible CPS detected: " + cps);
                ViolationReporter.reportCPSViolation("IMPOSSIBLE_CPS", "cps=" + cps);
                return true;
            }
        }
        return false;
    }

    public void reset() {
        clickTimes.clear();
        intervals.clear();
        autoclickerStreak = 0;
    }
}
