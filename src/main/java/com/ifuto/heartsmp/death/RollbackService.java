package com.ifuto.heartsmp.death;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BundleMeta;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.util.ExpUtil;
import com.ifuto.heartsmp.util.ItemCodec;
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.PdcKeys;

/**
 * ドロップ品の追跡 / RollBack / Undo。
 *
 * <p>死亡時にドロップしたアイテムには死亡ログ ID が PDC で付与されているので、
 * オンラインプレイヤーのインベントリ・エンダーチェスト・読み込み済みチェスト・
 * 地面・シュルカーボックスやバンドルの中まで辿って回収できます。</p>
 */
public final class RollbackService {

    private final HeartSMP plugin;
    private final Map<String, UndoState> undoStates = new LinkedHashMap<>();

    public RollbackService(HeartSMP plugin) {
        this.plugin = plugin;
        load();
    }

    public record Removed(String origin, ItemStack item) {
    }

    public record Result(int found, int restored, int dropped) {
    }

    public record UndoState(String deathId, UUID victim, int expBefore, int expGiven, long time, List<Removed> items) {
    }

    // ------------------------------------------------------------------
    // 永続化
    // ------------------------------------------------------------------

    private File file() {
        return new File(plugin.getDataFolder(), "rollbacks.yml");
    }

    public void load() {
        undoStates.clear();
        File file = file();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> map : yaml.getMapList("undo")) {
            try {
                String deathId = String.valueOf(map.get("death-id"));
                UUID victim = UUID.fromString(String.valueOf(map.get("victim")));
                int expBefore = toInt(map.get("exp-before"));
                int expGiven = toInt(map.get("exp-given"));
                long time = toLong(map.get("time"));
                List<Removed> items = new ArrayList<>();
                Object raw = map.get("items");
                if (raw instanceof List<?> list) {
                    for (Object entry : list) {
                        if (entry instanceof Map<?, ?> itemMap) {
                            ItemStack stack = ItemCodec.decode(String.valueOf(itemMap.get("data")));
                            if (stack != null) {
                                items.add(new Removed(String.valueOf(itemMap.get("origin")), stack));
                            }
                        }
                    }
                }
                undoStates.put(deathId, new UndoState(deathId, victim, expBefore, expGiven, time, items));
            } catch (RuntimeException ignored) {
                // 壊れた行は無視
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (UndoState state : undoStates.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("death-id", state.deathId());
            map.put("victim", state.victim().toString());
            map.put("exp-before", state.expBefore());
            map.put("exp-given", state.expGiven());
            map.put("time", state.time());
            List<Map<String, Object>> items = new ArrayList<>();
            for (Removed removed : state.items()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("origin", removed.origin());
                item.put("data", ItemCodec.encode(removed.item()));
                items.add(item);
            }
            map.put("items", items);
            list.add(map);
        }
        yaml.set("undo", list);
        try {
            File file = file();
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("rollbacks.yml の保存に失敗しました: " + ex.getMessage());
        }
    }

    public boolean hasUndo(String deathId) {
        return undoStates.containsKey(deathId);
    }

    public UndoState undoState(String deathId) {
        return undoStates.get(deathId);
    }

    // ------------------------------------------------------------------
    // 回収
    // ------------------------------------------------------------------

    /** 死亡ログ ID が付いたアイテムを世界中から回収します。 */
    public List<Removed> sweep(String deathId) {
        List<Removed> removed = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Origin inv = Origin.player("PLAYER_INV", player);
            sweepInventory(player.getInventory(), inv, deathId, removed);
            sweepInventory(player.getEnderChest(), inv.withType("ENDER_CHEST"), deathId, removed);
        }

        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Container container) {
                        Location location = state.getLocation();
                        sweepInventory(container.getInventory(),
                                Origin.block("CONTAINER", location), deathId, removed);
                    }
                }
            }
        }

        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Item drop : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                ItemStack stack = drop.getItemStack();
                if (Items.isAir(stack)) {
                    continue;
                }
                Location location = drop.getLocation();
                Origin origin = Origin.block("GROUND", location);
                if (matches(stack, deathId)) {
                    removed.add(new Removed(origin.encode(), stack.clone()));
                    drop.remove();
                    continue;
                }
                ItemStack updated = sweepNested(stack, origin, deathId, removed);
                if (updated != null) {
                    drop.setItemStack(updated);
                }
            }
        }

        return removed;
    }

    private boolean matches(ItemStack item, String deathId) {
        if (Items.isAir(item) || PdcKeys.DEATH_ID == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        String id = meta.getPersistentDataContainer().get(PdcKeys.DEATH_ID, PersistentDataType.STRING);
        return deathId.equals(id);
    }

    private void sweepInventory(Inventory inventory, Origin origin, String deathId, List<Removed> removed) {
        if (inventory == null) {
            return;
        }
        int size = inventory.getContents().length;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (Items.isAir(stack)) {
                continue;
            }
            Origin slotOrigin = origin.withSlot(slot);
            if (matches(stack, deathId)) {
                removed.add(new Removed(slotOrigin.encode(), stack.clone()));
                inventory.setItem(slot, null);
                continue;
            }
            ItemStack updated = sweepNested(stack, slotOrigin, deathId, removed);
            if (updated != null) {
                inventory.setItem(slot, updated);
            }
        }
    }

    /** シュルカーボックス / バンドルの中身を再帰的に回収します。 */
    private ItemStack sweepNested(ItemStack stack, Origin origin, String deathId, List<Removed> removed) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        if (meta instanceof BlockStateMeta blockMeta) {
            BlockState state = blockMeta.getBlockState();
            if (state instanceof Container container) {
                int before = removed.size();
                sweepInventory(container.getInventory(), origin.withType("CONTAINER_NESTED"), deathId, removed);
                if (removed.size() != before) {
                    blockMeta.setBlockState(state);
                    ItemStack copy = stack.clone();
                    copy.setItemMeta(blockMeta);
                    return copy;
                }
            }
            return null;
        }
        if (meta instanceof BundleMeta bundleMeta) {
            List<ItemStack> contents = bundleMeta.getItems();
            if (contents == null || contents.isEmpty()) {
                return null;
            }
            List<ItemStack> kept = new ArrayList<>();
            boolean changed = false;
            for (ItemStack content : contents) {
                if (matches(content, deathId)) {
                    removed.add(new Removed(origin.withType("BUNDLE").encode(), content.clone()));
                    changed = true;
                } else {
                    kept.add(content);
                }
            }
            if (changed) {
                bundleMeta.setItems(kept);
                ItemStack copy = stack.clone();
                copy.setItemMeta(bundleMeta);
                return copy;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // RollBack / Undo
    // ------------------------------------------------------------------

    /** ドロップ品を回収して死亡時のインベントリへ戻します。 */
    public Result rollback(DeathRecord record, boolean restoreExp) {
        Player victim = Bukkit.getPlayer(record.victim());
        if (victim == null) {
            return null;
        }
        List<Removed> removed = sweep(record.id());
        int expBefore = victim.getTotalExperience();

        int restored = 0;
        int dropped = 0;
        Inventory inventory = victim.getInventory();
        int size = inventory.getContents().length;
        for (Map.Entry<Integer, String> entry : record.items().entrySet()) {
            ItemStack item = ItemCodec.decode(entry.getValue());
            if (Items.isAir(item)) {
                continue;
            }
            int slot = entry.getKey();
            if (slot >= 0 && slot < size && Items.isAir(inventory.getItem(slot))) {
                inventory.setItem(slot, item);
                restored++;
                continue;
            }
            Map<Integer, ItemStack> leftover = inventory.addItem(item);
            if (leftover.isEmpty()) {
                restored++;
            } else {
                for (ItemStack remain : leftover.values()) {
                    victim.getWorld().dropItemNaturally(victim.getLocation(), remain);
                    dropped++;
                }
            }
        }

        int expGiven = 0;
        if (restoreExp && record.experience() > 0) {
            ExpUtil.setTotal(victim, expBefore + record.experience());
            expGiven = record.experience();
        }

        undoStates.put(record.id(), new UndoState(record.id(), victim.getUniqueId(), expBefore, expGiven,
                System.currentTimeMillis(), removed));
        save();
        return new Result(removed.size(), restored, dropped);
    }

    /** RollBack を取り消します。 */
    public Result undo(DeathRecord record) {
        UndoState state = undoStates.get(record.id());
        if (state == null) {
            return null;
        }
        // 返却したアイテムを回収
        sweep(record.id());

        int restored = 0;
        int dropped = 0;
        for (Removed removed : state.items()) {
            boolean ok = restoreOrigin(removed);
            if (ok) {
                restored++;
            } else {
                dropped++;
            }
        }
        Player victim = Bukkit.getPlayer(state.victim());
        if (victim != null && state.expGiven() > 0) {
            ExpUtil.setTotal(victim, state.expBefore());
        }
        undoStates.remove(record.id());
        save();
        return new Result(state.items().size(), restored, dropped);
    }

    private boolean restoreOrigin(Removed removed) {
        Origin origin = Origin.parse(removed.origin());
        if (origin == null) {
            return false;
        }
        ItemStack item = removed.item();
        switch (origin.type()) {
            case "PLAYER_INV", "PLAYER_INV_NESTED" -> {
                Player player = origin.player();
                if (player == null) {
                    dropAt(origin, item);
                    return false;
                }
                return putInto(player.getInventory(), origin.slot(), item, origin);
            }
            case "ENDER_CHEST" -> {
                Player player = origin.player();
                if (player == null) {
                    dropAt(origin, item);
                    return false;
                }
                return putInto(player.getEnderChest(), origin.slot(), item, origin);
            }
            case "CONTAINER", "CONTAINER_NESTED" -> {
                World world = Bukkit.getWorld(origin.world());
                if (world == null) {
                    return false;
                }
                BlockState state = world.getBlockAt(origin.x(), origin.y(), origin.z()).getState();
                if (state instanceof Container container) {
                    return putInto(container.getInventory(), origin.slot(), item, origin);
                }
                dropAt(origin, item);
                return false;
            }
            default -> {
                dropAt(origin, item);
                return false;
            }
        }
    }

    private boolean putInto(Inventory inventory, int slot, ItemStack item, Origin origin) {
        if (slot >= 0 && slot < inventory.getContents().length && Items.isAir(inventory.getItem(slot))) {
            inventory.setItem(slot, item);
            return true;
        }
        Map<Integer, ItemStack> leftover = inventory.addItem(item);
        if (leftover.isEmpty()) {
            return true;
        }
        for (ItemStack remain : leftover.values()) {
            dropAt(origin, remain);
        }
        return false;
    }

    private void dropAt(Origin origin, ItemStack item) {
        if (Items.isAir(item)) {
            return;
        }
        World world = Bukkit.getWorld(origin.world());
        if (world == null) {
            world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            return;
        }
        world.dropItemNaturally(new Location(world, origin.x() + 0.5D, origin.y() + 0.5D, origin.z() + 0.5D), item);
    }

    // ------------------------------------------------------------------
    // Origin 表現
    // ------------------------------------------------------------------

    /** {@code type|key|world|x|y|z|slot} で直列化します。 */
    public record Origin(String type, String key, String world, int x, int y, int z, int slot) {

        public static Origin player(String type, Player player) {
            Location location = player.getLocation();
            World world = location.getWorld();
            return new Origin(type, player.getUniqueId().toString(),
                    world == null ? "-" : world.getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ(), -1);
        }

        public static Origin block(String type, Location location) {
            World world = location.getWorld();
            String worldName = world == null ? "-" : world.getName();
            return new Origin(type, "-", worldName,
                    location.getBlockX(), location.getBlockY(), location.getBlockZ(), -1);
        }

        public Origin withSlot(int slot) {
            return new Origin(type, key, world, x, y, z, slot);
        }

        public Origin withType(String newType) {
            return new Origin(newType, key, world, x, y, z, slot);
        }

        public Player player() {
            try {
                return Bukkit.getPlayer(UUID.fromString(key));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        public String encode() {
            return String.join("|", type, key, world, String.valueOf(x), String.valueOf(y), String.valueOf(z),
                    String.valueOf(slot));
        }

        public static Origin parse(String raw) {
            if (raw == null) {
                return null;
            }
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 7) {
                return null;
            }
            try {
                return new Origin(parts[0], parts[1], parts[2],
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                        Integer.parseInt(parts[6]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    private static int toInt(Object value) {
        return (int) toLong(value);
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

    /** 追跡タグを付与します。 */
    public static ItemStack track(ItemStack item, String deathId, UUID owner) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && PdcKeys.DEATH_ID != null) {
            meta.getPersistentDataContainer().set(PdcKeys.DEATH_ID, PersistentDataType.STRING, deathId);
            meta.getPersistentDataContainer().set(PdcKeys.DROP_OWNER, PersistentDataType.STRING, owner.toString());
            meta.getPersistentDataContainer().set(PdcKeys.DROP_TIME, PersistentDataType.LONG, System.currentTimeMillis());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 死亡ログ ID がついたアイテムかどうか。 */
    public boolean isTracked(ItemStack item, String deathId) {
        return matches(item, deathId);
    }

    public boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }
}
