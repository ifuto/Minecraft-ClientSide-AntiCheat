package com.ifuto.heartsmp.death;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;

import com.ifuto.heartsmp.HeartSMP;

/** deaths.yml への死亡ログの永続化。 */
public final class DeathStore {

    private final HeartSMP plugin;
    private final Map<String, DeathRecord> records = new LinkedHashMap<>();

    public DeathStore(HeartSMP plugin) {
        this.plugin = plugin;
        load();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "deaths.yml");
    }

    public void load() {
        records.clear();
        File file = file();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> map : yaml.getMapList("records")) {
            DeathRecord record = DeathRecord.fromMap(map);
            if (record != null) {
                records.put(record.id(), record);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (DeathRecord record : records.values()) {
            list.add(record.toMap());
        }
        yaml.set("records", list);
        try {
            File file = file();
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("deaths.yml の保存に失敗しました: " + ex.getMessage());
        }
    }

    public void add(DeathRecord record) {
        records.put(record.id(), record);
        prune(record.victim());
        save();
    }

    public void remove(String id) {
        if (records.remove(id) != null) {
            save();
        }
    }

    private void prune(UUID victim) {
        int max = Math.max(5, plugin.settings().rollbackMaxRecordsPerPlayer);
        List<DeathRecord> mine = new ArrayList<>();
        for (DeathRecord record : records.values()) {
            if (record.victim().equals(victim)) {
                mine.add(record);
            }
        }
        if (mine.size() <= max) {
            return;
        }
        mine.sort(Comparator.comparingLong(DeathRecord::time));
        int toRemove = mine.size() - max;
        for (int i = 0; i < toRemove; i++) {
            records.remove(mine.get(i).id());
        }
    }

    public DeathRecord byId(String id) {
        return records.get(id);
    }

    /** 新しい順。 */
    public List<DeathRecord> byVictim(UUID victim) {
        List<DeathRecord> result = new ArrayList<>();
        for (DeathRecord record : records.values()) {
            if (record.victim().equals(victim)) {
                result.add(record);
            }
        }
        result.sort(Comparator.comparingLong(DeathRecord::time).reversed());
        return result;
    }

    public List<DeathRecord> all() {
        List<DeathRecord> result = new ArrayList<>(records.values());
        result.sort(Comparator.comparingLong(DeathRecord::time).reversed());
        return result;
    }

    public Map<String, DeathRecord> raw() {
        return new HashMap<>(records);
    }
}
