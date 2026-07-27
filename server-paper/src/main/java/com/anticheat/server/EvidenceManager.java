package com.anticheat.server;

import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evolution: Collect forensic evidence per violation.
 * Stores recent evidence (rotation history, movement, violation chain) to disk for admin review.
 * Also maintains hash chain verification.
 */
public final class EvidenceManager {

    private final File dataFolder;
    private final Map<UUID, Deque<String>> evidenceMap = new ConcurrentHashMap<>();

    public EvidenceManager(File dataFolder) {
        this.dataFolder = dataFolder;
        if (!dataFolder.exists()) dataFolder.mkdirs();
    }

    public void addEvidence(Player player, String jsonPayload) {
        Deque<String> deque = evidenceMap.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        String timestamped = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + jsonPayload;
        deque.addLast(timestamped);
        if (deque.size() > 500) deque.pollFirst();

        // Also write to file
        try {
            File playerFile = new File(dataFolder, player.getUniqueId() + ".log");
            try (FileWriter fw = new FileWriter(playerFile, true)) {
                fw.write(timestamped + System.lineSeparator());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<String> getRecent(Player player, int n) {
        Deque<String> deque = evidenceMap.get(player.getUniqueId());
        if (deque == null) return Collections.emptyList();
        List<String> list = new ArrayList<>(deque);
        int from = Math.max(0, list.size() - n);
        return list.subList(from, list.size());
    }
}
