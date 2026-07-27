package com.anticheat.server;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evolution: Server-side violation scoring mirroring client ScoreAggregator but authoritative.
 * Aggregates violations per player with decay, thresholds for warn/ban.
 */
public final class ViolationScore {

    private static final double BAN_THRESHOLD = 100.0;
    private static final double WARN_THRESHOLD = 50.0;
    private static final double HALF_LIFE_MS = 120_000; // 2 min

    private static class PlayerScore {
        double score = 0;
        long lastUpdate = System.currentTimeMillis();
        Map<String, Integer> counts = new ConcurrentHashMap<>();

        synchronized void add(String type, int weight) {
            long now = System.currentTimeMillis();
            double delta = now - lastUpdate;
            double lambda = Math.log(2) / HALF_LIFE_MS;
            score *= Math.exp(-lambda * delta);
            score += weight;
            lastUpdate = now;
            counts.merge(type, 1, Integer::sum);
        }

        synchronized double getScore() {
            long now = System.currentTimeMillis();
            double delta = now - lastUpdate;
            double lambda = Math.log(2) / HALF_LIFE_MS;
            return score * Math.exp(-lambda * delta);
        }
    }

    private final Map<java.util.UUID, PlayerScore> scores = new ConcurrentHashMap<>();

    public void addViolation(Player player, String type, int weight) {
        PlayerScore ps = scores.computeIfAbsent(player.getUniqueId(), k -> new PlayerScore());
        ps.add(type, weight);
        double current = ps.getScore();
        if (current >= BAN_THRESHOLD) {
            // Ban handled by AntiCheatServerPlugin
            // Reset to avoid spam
            scores.remove(player.getUniqueId());
        }
    }

    public double getScore(Player player) {
        PlayerScore ps = scores.get(player.getUniqueId());
        return ps == null ? 0 : ps.getScore();
    }

    public boolean shouldBan(Player player) {
        return getScore(player) >= BAN_THRESHOLD;
    }

    public boolean shouldWarn(Player player) {
        return getScore(player) >= WARN_THRESHOLD;
    }
}
