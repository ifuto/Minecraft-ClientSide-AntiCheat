package com.ifuto.heartsmp.delivery;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.util.ItemCodec;

/** 配送 (発送 / 到着 / Bagages) の管理。 */
public final class DeliveryManager {

    private final HeartSMP plugin;
    private final Map<String, DeliveryPackage> pending = new LinkedHashMap<>();
    private final Map<UUID, List<DeliveryPackage>> bagages = new LinkedHashMap<>();

    public DeliveryManager(HeartSMP plugin) {
        this.plugin = plugin;
        load();
    }

    private File file() {
        return new File(plugin.getDataFolder(), "deliveries.yml");
    }

    // ------------------------------------------------------------------
    // 永続化
    // ------------------------------------------------------------------

    public void load() {
        pending.clear();
        bagages.clear();
        File file = file();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        for (Map<?, ?> map : yaml.getMapList("pending")) {
            DeliveryPackage pkg = read(map, true);
            if (pkg != null) {
                pending.put(pkg.id(), pkg);
            }
        }

        var section = yaml.getConfigurationSection("bagages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                List<DeliveryPackage> list = new ArrayList<>();
                for (Map<?, ?> map : section.getMapList(key)) {
                    DeliveryPackage pkg = read(map, false);
                    if (pkg != null) {
                        list.add(pkg);
                    }
                }
                bagages.put(uuid, list);
            }
        }
    }

    private DeliveryPackage read(Map<?, ?> map, boolean withTarget) {
        try {
            ItemStack item = ItemCodec.decode(String.valueOf(map.get("item")));
            if (item == null) {
                return null;
            }
            String id = String.valueOf(map.get("id"));
            UUID sender = UUID.fromString(String.valueOf(map.get("sender")));
            String senderName = map.get("sender-name") == null ? "unknown" : String.valueOf(map.get("sender-name"));
            UUID target = withTarget && map.get("target") != null
                    ? UUID.fromString(String.valueOf(map.get("target")))
                    : new UUID(0L, 0L);
            boolean fast = Boolean.parseBoolean(String.valueOf(map.get("fast")));
            long arriveAt = toLong(map.get("arrive-at"));
            return new DeliveryPackage(id, sender, senderName, target, item, fast, arriveAt);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> pendingList = new ArrayList<>();
        for (DeliveryPackage pkg : pending.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", pkg.id());
            map.put("sender", pkg.sender().toString());
            map.put("sender-name", pkg.senderName());
            map.put("target", pkg.target().toString());
            map.put("fast", pkg.fast());
            map.put("arrive-at", pkg.arriveAt());
            map.put("item", ItemCodec.encode(pkg.item()));
            pendingList.add(map);
        }
        yaml.set("pending", pendingList);

        for (Map.Entry<UUID, List<DeliveryPackage>> entry : bagages.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (DeliveryPackage pkg : entry.getValue()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", pkg.id());
                map.put("sender", pkg.sender().toString());
                map.put("sender-name", pkg.senderName());
                map.put("fast", pkg.fast());
                map.put("arrive-at", pkg.arriveAt());
                map.put("item", ItemCodec.encode(pkg.item()));
                list.add(map);
            }
            yaml.set("bagages." + entry.getKey(), list);
        }

        try {
            File file = file();
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("deliveries.yml の保存に失敗しました: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 発送 / 到着
    // ------------------------------------------------------------------

    public int pendingCount(UUID sender) {
        int count = 0;
        for (DeliveryPackage pkg : pending.values()) {
            if (pkg.sender().equals(sender)) {
                count++;
            }
        }
        return count;
    }

    public List<DeliveryPackage> bagages(UUID target) {
        return bagages.computeIfAbsent(target, key -> new ArrayList<>());
    }

    public int bagagesCount(UUID target) {
        return bagages(target).size();
    }

    public boolean isFull(UUID target) {
        return bagagesCount(target) >= plugin.settings().deliveryMaxBagages;
    }

    public DeliveryPackage send(Player sender, UUID target, ItemStack item, boolean fast) {
        long arriveAt = fast ? System.currentTimeMillis()
                : System.currentTimeMillis() + plugin.settings().deliveryTravelMillis;
        DeliveryPackage pkg = new DeliveryPackage(UUID.randomUUID().toString(), sender.getUniqueId(),
                sender.getName(), target, item.clone(), fast, arriveAt);
        pending.put(pkg.id(), pkg);
        save();
        schedule(pkg);
        return pkg;
    }

    private void schedule(DeliveryPackage pkg) {
        long delayMillis = Math.max(0L, pkg.arriveAt() - System.currentTimeMillis());
        long ticks = Math.max(1L, (delayMillis + 49L) / 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> arrive(pkg.id()), ticks);
    }

    /** 起動時に残りの荷物を再スケジュールします。 */
    public void resume() {
        for (DeliveryPackage pkg : new ArrayList<>(pending.values())) {
            schedule(pkg);
        }
    }

    private void arrive(String id) {
        DeliveryPackage pkg = pending.remove(id);
        if (pkg == null) {
            return;
        }
        bagages(pkg.target()).add(pkg);
        save();

        Player target = Bukkit.getPlayer(pkg.target());
        if (target != null && target.isOnline()) {
            plugin.messages().send(target, "delivery-arrived", "player", pkg.senderName());
            plugin.messages().send(target, "delivery-arrived-hint");
            target.playSound(target.getLocation(), "entity.item.pickup", 1.0F, 0.9F);
        }
    }

    /** Bagages から 1 件取り出します。 */
    public DeliveryPackage take(UUID target, String id) {
        List<DeliveryPackage> list = bagages(target);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                DeliveryPackage pkg = list.remove(i);
                save();
                return pkg;
            }
        }
        return null;
    }

    public List<DeliveryPackage> pendingAll() {
        return new ArrayList<>(pending.values());
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
