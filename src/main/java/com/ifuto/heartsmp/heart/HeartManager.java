package com.ifuto.heartsmp.heart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.util.Items;
import com.ifuto.heartsmp.util.Msg;
import com.ifuto.heartsmp.util.PdcKeys;

/** ハート (最大体力) の管理。 */
public final class HeartManager implements Listener {

    private final HeartSMP plugin;

    public HeartManager(HeartSMP plugin) {
        this.plugin = plugin;
    }

    public int hearts(Player player) {
        return clamp(plugin.playerData().get(player).hearts());
    }

    public int clamp(int hearts) {
        return Math.max(plugin.settings().heartsMin, Math.min(plugin.settings().heartsMax, hearts));
    }

    /** 属性 (最大体力) を現在のハート数に合わせます。 */
    public void apply(Player player) {
        int hearts = clamp(plugin.playerData().get(player).hearts());
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double max = hearts * 2.0D;
        if (attribute != null) {
            attribute.setBaseValue(max);
        }
        if (player.getHealth() > max) {
            player.setHealth(Math.max(0.5D, max));
        }
    }

    public void setHearts(Player player, int hearts, boolean notify) {
        int clamped = clamp(hearts);
        plugin.playerData().get(player).hearts(clamped);
        plugin.playerData().markDirty(player.getUniqueId());
        apply(player);
        if (notify) {
            plugin.messages().send(player, "heart-lost", "hearts", String.valueOf(clamped));
        }
    }

    /**
     * ハートを増減します。
     *
     * @return 実際に変動した場合は true
     */
    public boolean add(Player player, int delta, boolean notify) {
        int current = hearts(player);
        int next = clamp(current + delta);
        if (next == current) {
            if (notify) {
                if (delta < 0) {
                    plugin.messages().error(player, "heart-min");
                }
            }
            return false;
        }
        plugin.playerData().get(player).hearts(next);
        plugin.playerData().markDirty(player.getUniqueId());
        apply(player);
        if (notify) {
            plugin.messages().send(player, delta > 0 ? "heart-gained" : "heart-lost", "hearts", String.valueOf(next));
        }
        return true;
    }

    /** ハートの欠片 (ネザースター)。 */
    public ItemStack createHeartStar() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Msg.parse(plugin.messages().raw("heart-star-name")));
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.messages().rawList("heart-star-lore")) {
                lore.add(Msg.parse(line));
            }
            meta.lore(lore);
            if (PdcKeys.HEART_STAR != null) {
                meta.getPersistentDataContainer().set(PdcKeys.HEART_STAR, PersistentDataType.BYTE, (byte) 1);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isHeartStar(ItemStack item) {
        if (Items.isAir(item) || PdcKeys.HEART_STAR == null || item.getType() != Material.NETHER_STAR) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(PdcKeys.HEART_STAR, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> apply(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> apply(event.getPlayer()));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!isHeartStar(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        int hearts = hearts(player);
        if (hearts >= plugin.settings().heartsMax) {
            plugin.messages().error(player, "heart-star-max");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!Items.isAir(hand)) {
            ItemStack copy = hand.clone();
            copy.setAmount(copy.getAmount() - 1);
            player.getInventory().setItemInMainHand(copy.getAmount() <= 0 ? null : copy);
        }
        add(player, 1, false);
        plugin.messages().send(player, "heart-star-consumed",
                "hearts", String.valueOf(hearts(player)));
        player.playSound(player.getLocation(), "entity.player.levelup", 1.0F, 1.2F);
    }

    /** キルログ用にプレイヤー名へランク色を付けた Component を返します。 */
    public Component coloredName(Player player) {
        return Msg.parse(playerRankColorTag(player) + Msg.escape(player.getName()));
    }

    public String playerRankColorTag(Player player) {
        return plugin.playerData().rank(player.getUniqueId()).nameColorTag(plugin);
    }

    public Map<String, Component> namePlaceholders(Player player) {
        return Map.of("name", Component.text(player.getName()));
    }
}
