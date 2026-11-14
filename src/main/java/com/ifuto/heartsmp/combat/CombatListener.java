package com.ifuto.heartsmp.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.projectiles.ProjectileSource;

import com.ifuto.heartsmp.HeartSMP;

/** プレイヤー由来のダメージで双方を Combat 状態にします。 */
public final class CombatListener implements Listener {

    private final HeartSMP plugin;

    public CombatListener(HeartSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (!attacker.isOnline() || !victim.isOnline()) {
            return;
        }
        plugin.combat().tag(attacker, victim);
    }

    /**
     * Combat 中の滑空 (エリトラ) を禁止します。
     *
     * <p>トグルの瞬間にキャンセルし、その一瞬の滑空もサーバー側で打ち消します
     * （次 tick でもう一度滑空状態を落として、クライアント側の一瞬の滑空も
     * なかったことにします）。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!event.isGliding()) {
            // 滑空の終了はそのまま通す
            return;
        }
        if (!plugin.settings().combatBlockElytra || !plugin.combat().inCombat(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        plugin.combat().stopGlide(player, true);
        // クライアント側で一瞬だけ滑空していたケースも、次 tick で確実に打ち消す
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && plugin.combat().inCombat(player.getUniqueId())) {
                plugin.combat().stopGlide(player, false);
            }
        });
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player shooter) {
                return shooter;
            }
        }
        if (damager instanceof TNTPrimed tnt) {
            Entity source = tnt.getSource();
            if (source instanceof Player igniter) {
                return igniter;
            }
        }
        return null;
    }
}
