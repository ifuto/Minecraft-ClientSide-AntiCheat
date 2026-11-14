package com.ifuto.heartsmp.listener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.death.DeathRecord;
import com.ifuto.heartsmp.util.ItemCodec;
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.Msg;
import com.ifuto.heartsmp.util.PdcKeys;

/**
 * 参加 / 退出メッセージと Combat 中のログアウト処理。
 */
public final class ConnectionListener implements Listener {

    private final HeartSMP plugin;
    private final Random random = new Random();

    public ConnectionListener(HeartSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(nameMessage("join", "JoinPlayer_Name", player));
        plugin.hearts().apply(player);
        plugin.playerData().markDirty(player.getUniqueId());
        if (plugin.delivery().bagagesCount(player.getUniqueId()) > 0) {
            plugin.messages().send(player, "delivery-arrived-hint");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.quitMessage(nameMessage("quit", "LeavePlayer_Name", player));

        if (plugin.combat().inCombat(player.getUniqueId())) {
            handleCombatLogout(player);
        }
        plugin.combat().clear(player.getUniqueId());
        plugin.tpa().playerQuit(player.getUniqueId());
        plugin.combatCommand().clear(player);
        plugin.playerData().markDirty(player.getUniqueId());
        plugin.playerData().saveSync();
    }

    private Component nameMessage(String key, String placeholder, Player player) {
        String template = plugin.messages().raw(key);
        if (player.isInvisible()) {
            Map<String, Component> placeholders = new LinkedHashMap<>();
            placeholders.put(placeholder, Msg.parse(plugin.messages().raw("hidden-name")));
            return Msg.build(template, placeholders);
        }
        return Msg.build(template, placeholder, player.getName());
    }

    // ------------------------------------------------------------------
    // Combat 中のログアウト
    // ------------------------------------------------------------------

    private void handleCombatLogout(Player player) {
        Location location = player.getLocation().clone();
        String deathId = UUID.randomUUID().toString();
        Set<UUID> opponents = new LinkedHashSet<>(plugin.combat().opponents(player.getUniqueId()));

        ItemStack[] contents = player.getInventory().getContents();
        Map<Integer, String> snapshot = new LinkedHashMap<>();
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (Items.isAir(item)) {
                continue;
            }
            ItemStack tracked = plugin.settings().trackDrops
                    ? track(item.clone(), deathId, player.getUniqueId())
                    : item.clone();
            snapshot.put(slot, ItemCodec.encode(tracked));
            drops.add(tracked);
        }
        player.getInventory().clear();
        scatter(location, drops);

        // ハートの推移
        boolean lost = plugin.hearts().add(player, -1, false);
        if (lost) {
            if (opponents.size() == 1) {
                UUID receiver = opponents.iterator().next();
                Player online = Bukkit.getPlayer(receiver);
                if (online != null) {
                    plugin.hearts().add(online, 1, true);
                } else {
                    var data = plugin.playerData().get(receiver);
                    data.hearts(plugin.hearts().clamp(data.hearts() + 1));
                    plugin.playerData().markDirty(receiver);
                }
            } else if (location.getWorld() != null) {
                location.getWorld().dropItemNaturally(location, plugin.hearts().createHeartStar());
            }
        }

        List<String> participants = new ArrayList<>();
        for (UUID uuid : opponents) {
            String name = Bukkit.getPlayer(uuid) != null
                    ? Bukkit.getPlayer(uuid).getName() : plugin.playerData().get(uuid).name();
            if (!participants.contains(name)) {
                participants.add(name);
            }
        }
        plugin.deaths().add(new DeathRecord(
                deathId,
                player.getUniqueId(),
                player.getName(),
                opponents.size() == 1 ? opponents.iterator().next() : null,
                opponents.size() == 1 ? participants.get(0) : null,
                DeathRecord.Type.COMBAT_LOGOUT,
                System.currentTimeMillis(),
                "Combat 中のログアウト",
                location.getWorld() == null ? "world" : location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                participants,
                snapshot,
                player.getTotalExperience()
        ));

        plugin.messages().broadcast(Msg.build(plugin.messages().raw("combat-logout"),
                "player", player.getName()));
    }

    private void scatter(Location origin, List<ItemStack> items) {
        double radius = Math.max(0.0D, plugin.settings().logoutScatterRadius);
        if (origin.getWorld() == null) {
            return;
        }
        for (ItemStack item : items) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = radius <= 0 ? 0 : random.nextDouble() * radius;
            Location target = origin.clone().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
            origin.getWorld().dropItemNaturally(target, item);
        }
    }

    private ItemStack track(ItemStack item, String deathId, UUID owner) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && PdcKeys.DEATH_ID != null) {
            meta.getPersistentDataContainer().set(PdcKeys.DEATH_ID, PersistentDataType.STRING, deathId);
            meta.getPersistentDataContainer().set(PdcKeys.DROP_OWNER, PersistentDataType.STRING, owner.toString());
            meta.getPersistentDataContainer().set(PdcKeys.DROP_TIME, PersistentDataType.LONG, System.currentTimeMillis());
            item.setItemMeta(meta);
        }
        return item;
    }
}
