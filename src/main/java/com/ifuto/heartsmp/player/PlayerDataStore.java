package com.ifuto.heartsmp.player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.ifuto.heartsmp.HeartSMP;

/** players.yml (ハート / ランク / 表示名) の読み書き。 */
public final class PlayerDataStore {

    private final HeartSMP plugin;
    private final Map<UUID, PlayerData> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();

    public PlayerDataStore(HeartSMP plugin) {
        this.plugin = plugin;
        load();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "players.yml");
    }

    public void load() {
        cache.clear();
        File file = file();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            String name = entry.getString("name", "unknown");
            int hearts = entry.getInt("hearts", plugin.settings().heartsDefault);
            Rank rank = Rank.parse(entry.getString("rank"));
            cache.put(uuid, new PlayerData(uuid, name, hearts, rank == null ? Rank.DEFAULT : rank));
        }
    }

    public void saveSync() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (PlayerData data : cache.values()) {
            String base = "players." + data.uuid() + ".";
            yaml.set(base + "name", data.name());
            yaml.set(base + "hearts", data.hearts());
            yaml.set(base + "rank", data.rank().configKey());
        }
        try {
            File file = file();
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            yaml.save(file);
            dirty.clear();
        } catch (IOException ex) {
            plugin.getLogger().warning("players.yml の保存に失敗しました: " + ex.getMessage());
        }
    }

    public void markDirty(UUID uuid) {
        dirty.add(uuid);
    }

    public void saveIfDirty() {
        if (!dirty.isEmpty()) {
            saveSync();
        }
    }

    public PlayerData get(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) {
            String name = nameOf(uuid);
            data = new PlayerData(uuid, name, plugin.settings().heartsDefault, Rank.DEFAULT);
            cache.put(uuid, data);
            dirty.add(uuid);
        }
        return data;
    }

    public PlayerData get(Player player) {
        PlayerData data = get(player.getUniqueId());
        if (!player.getName().equals(data.name())) {
            data.name(player.getName());
            dirty.add(player.getUniqueId());
        }
        return data;
    }

    public PlayerData get(OfflinePlayer player) {
        PlayerData data = get(player.getUniqueId());
        String name = player.getName();
        if (name != null && !name.equals(data.name())) {
            data.name(name);
            dirty.add(player.getUniqueId());
        }
        return data;
    }

    public int hearts(UUID uuid) {
        return get(uuid).hearts();
    }

    public Rank rank(UUID uuid) {
        return get(uuid).rank();
    }

    public List<PlayerData> all() {
        return new ArrayList<>(cache.values());
    }

    private String nameOf(UUID uuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
