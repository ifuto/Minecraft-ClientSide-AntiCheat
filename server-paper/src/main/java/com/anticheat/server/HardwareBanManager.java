package com.anticheat.server;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Evolution: HWID ban manager.
 * Stores HWID hashes that are banned, preventing alt accounts on same machine.
 *
 * HWID is generated client-side from OS + hashed MAC etc (privacy preserving, truncated hash).
 * Server receives HWID in handshake (optional, if client sends).
 */
public final class HardwareBanManager {

    private final File file;
    private final Set<String> bannedHwids = new HashSet<>();

    public HardwareBanManager(File dataFolder) {
        this.file = new File(dataFolder, "hwid-bans.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        bannedHwids.addAll(cfg.getStringList("banned"));
    }

    private void save() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.set("banned", new java.util.ArrayList<>(bannedHwids));
        try { cfg.save(file); } catch (IOException e) { e.printStackTrace(); }
    }

    public boolean isBanned(String hwid) {
        return hwid != null && bannedHwids.contains(hwid.toLowerCase());
    }

    public void banHwid(String hwid, String reason) {
        if (hwid == null || hwid.equalsIgnoreCase("unknown") || hwid.startsWith("native-not")) return;
        bannedHwids.add(hwid.toLowerCase());
        save();
        Bukkit.getLogger().warning("[Anticheat] HWID banned: " + hwid + " reason: " + reason);
    }

    public void unbanHwid(String hwid) {
        bannedHwids.remove(hwid.toLowerCase());
        save();
    }
}
