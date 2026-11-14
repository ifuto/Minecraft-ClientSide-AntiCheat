package com.ifuto.heartsmp.death;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.player.PlayerData;
import com.ifuto.heartsmp.util.ExpUtil;
import com.ifuto.heartsmp.util.ItemCodec;
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.Msg;
import com.ifuto.heartsmp.util.PdcKeys;

/**
 * 死亡処理。
 * <ul>
 *   <li>プレイヤーに殺された場合はインベントリをその場にドロップ</li>
 *   <li>Mob / 自然死の場合は Keep Inventory</li>
 *   <li>Combat 中の死亡はハートの受け渡し (不確定なら Heart のネザースターをドロップ)</li>
 *   <li>キルログの差し替え</li>
 * </ul>
 */
public final class DeathListener implements Listener {

    private final HeartSMP plugin;
    private final Map<UUID, Snapshot> pendingRestore = new java.util.HashMap<>();

    public DeathListener(HeartSMP plugin) {
        this.plugin = plugin;
    }

    private record Snapshot(ItemStack[] contents, int experience) {
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        boolean playerKill = killer != null && !killer.getUniqueId().equals(victim.getUniqueId());
        boolean inCombat = plugin.combat().inCombat(victim.getUniqueId());

        Set<UUID> opponents = new LinkedHashSet<>(plugin.combat().opponents(victim.getUniqueId()));
        if (killer != null) {
            opponents.add(killer.getUniqueId());
        }

        // ---- 1. インベントリ ----
        if (playerKill) {
            dropInventoryOnDeath(event, victim, killer, opponents);
            event.deathMessage(killLog(killer, victim));
        } else {
            keepInventory(event, victim);
        }

        // ---- 2. ハートの推移 ----
        if (playerKill) {
            giveHeart(victim, killer.getUniqueId(), false);
        } else if (inCombat) {
            List<UUID> candidates = new ArrayList<>(plugin.combat().opponents(victim.getUniqueId()));
            if (candidates.size() == 1) {
                giveHeart(victim, candidates.get(0), false);
            } else {
                giveHeart(victim, null, true);
            }
        }

        // ---- 3. Combat 終了 ----
        plugin.combat().clear(victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Snapshot snapshot = pendingRestore.remove(event.getPlayer().getUniqueId());
        if (snapshot == null) {
            return;
        }
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.getInventory().setContents(snapshot.contents());
            ExpUtil.setTotal(player, snapshot.experience());
            plugin.hearts().apply(player);
        });
    }

    /**
     * 死亡画面のまま退出した場合でも Keep Inventory 分が消えないよう、
     * 退出時にスナップショットを戻します (サーバーのプレイヤーデータ保存より前)。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Snapshot snapshot = pendingRestore.remove(event.getPlayer().getUniqueId());
        if (snapshot == null) {
            return;
        }
        Player player = event.getPlayer();
        player.getInventory().setContents(snapshot.contents());
        ExpUtil.setTotal(player, snapshot.experience());
    }

    // ------------------------------------------------------------------
    // インベントリ
    // ------------------------------------------------------------------

    private void dropInventoryOnDeath(PlayerDeathEvent event, Player victim, Player killer, Set<UUID> opponents) {
        Location location = victim.getLocation().clone();
        ItemStack[] contents = victim.getInventory().getContents();
        int experience = victim.getTotalExperience();

        String deathId = UUID.randomUUID().toString();
        List<ItemStack> drops = new ArrayList<>();
        Map<Integer, String> snapshot = new LinkedHashMap<>();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (Items.isAir(item)) {
                continue;
            }
            ItemStack tracked = plugin.settings().trackDrops
                    ? tag(item.clone(), deathId, victim.getUniqueId())
                    : item.clone();
            drops.add(tracked);
            snapshot.put(slot, ItemCodec.encode(tracked));
        }

        // バニラのドロップは使わず、こちらで確実に落とす
        event.getDrops().clear();
        event.setDroppedExp(0);
        victim.getInventory().clear();
        for (ItemStack item : drops) {
            victim.getWorld().dropItemNaturally(location, item);
        }

        List<String> participants = new ArrayList<>();
        for (UUID uuid : opponents) {
            Player online = Bukkit.getPlayer(uuid);
            String name = online != null ? online.getName() : plugin.playerData().get(uuid).name();
            if (!participants.contains(name)) {
                participants.add(name);
            }
        }
        String cause = killer.getName() + " により殺害";
        plugin.deaths().add(new DeathRecord(
                deathId,
                victim.getUniqueId(),
                victim.getName(),
                killer.getUniqueId(),
                killer.getName(),
                DeathRecord.Type.PLAYER_KILL,
                System.currentTimeMillis(),
                cause,
                location.getWorld() == null ? "world" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                participants,
                snapshot,
                experience
        ));
    }

    private void keepInventory(PlayerDeathEvent event, Player victim) {
        ItemStack[] contents = victim.getInventory().getContents();
        int experience = victim.getTotalExperience();
        pendingRestore.put(victim.getUniqueId(), new Snapshot(contents, experience));
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    private ItemStack tag(ItemStack item, String deathId, UUID owner) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && PdcKeys.DEATH_ID != null) {
            meta.getPersistentDataContainer().set(PdcKeys.DEATH_ID, PersistentDataType.STRING, deathId);
            meta.getPersistentDataContainer().set(PdcKeys.DROP_OWNER, PersistentDataType.STRING, owner.toString());
            meta.getPersistentDataContainer().set(PdcKeys.DROP_TIME, PersistentDataType.LONG, System.currentTimeMillis());
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------------------
    // ハート
    // ------------------------------------------------------------------

    private void giveHeart(Player victim, UUID receiver, boolean ambiguous) {
        boolean lost = plugin.hearts().add(victim, -1, true);
        if (!lost) {
            return;
        }
        if (ambiguous || receiver == null) {
            Location location = victim.getLocation().clone();
            if (location.getWorld() != null) {
                location.getWorld().dropItemNaturally(location, plugin.hearts().createHeartStar());
            }
            return;
        }
        Player online = Bukkit.getPlayer(receiver);
        if (online == null) {
            PlayerData data = plugin.playerData().get(receiver);
            data.hearts(plugin.hearts().clamp(data.hearts() + 1));
            plugin.playerData().markDirty(receiver);
            return;
        }
        if (!plugin.hearts().add(online, 1, true)) {
            plugin.messages().error(online, "heart-max", "player", online.getName());
        }
    }

    // ------------------------------------------------------------------
    // キルログ
    // ------------------------------------------------------------------

    private Component killLog(Player killer, Player victim) {
        String template = plugin.messages().raw("kill-log")
                .replace("<name_color_kill>", rankColorTag(killer))
                .replace("<name_color_death>", rankColorTag(victim));

        Component killerName;
        if (killer.isInvisible()) {
            killerName = Msg.parse(plugin.messages().raw("hidden-name"));
        } else {
            killerName = Msg.parse(rankColorTag(killer) + Msg.escape(killer.getName()))
                    .decoration(TextDecoration.BOLD, true);
        }
        Component victimName = Msg.parse(rankColorTag(victim) + Msg.escape(victim.getName()));

        ItemStack held = killer.getInventory().getItemInMainHand();
        Component itemName = Items.killLogName(held, plugin.messages().raw("kill-log-custom-item"));
        if (itemName == null) {
            itemName = Msg.parse(plugin.messages().raw("kill-log-no-item"));
        }

        Map<String, Component> placeholders = new LinkedHashMap<>();
        placeholders.put("Killer_Name", killerName);
        placeholders.put("Death_Name", victimName);
        placeholders.put("Item_Name", itemName);
        return Msg.build(template, placeholders);
    }

    private String rankColorTag(Player player) {
        return plugin.playerData().rank(player.getUniqueId()).nameColorTag(plugin);
    }
}
