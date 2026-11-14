package com.ifuto.heartsmp.combat;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.ifuto.heartsmp.HeartSMP;

/** Combat タグの管理。 */
public final class CombatManager {

    private final HeartSMP plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;

    public CombatManager(HeartSMP plugin) {
        this.plugin = plugin;
    }

    private static final class Session {
        private long expiry;
        private final Set<UUID> opponents = new LinkedHashSet<>();
    }

    public void start() {
        stop();
        long interval = plugin.settings().actionbarIntervalTicks;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 双方の Combat をリセット (延長) します。 */
    public void tag(Player attacker, Player victim) {
        long expiry = System.currentTimeMillis() + plugin.settings().combatMillis;
        Session a = sessions.computeIfAbsent(attacker.getUniqueId(), key -> new Session());
        a.expiry = expiry;
        a.opponents.add(victim.getUniqueId());
        Session v = sessions.computeIfAbsent(victim.getUniqueId(), key -> new Session());
        v.expiry = expiry;
        v.opponents.add(attacker.getUniqueId());
    }

    public boolean inCombat(UUID uuid) {
        Session session = sessions.get(uuid);
        return session != null && session.expiry > System.currentTimeMillis();
    }

    public long remainingMillis(UUID uuid) {
        Session session = sessions.get(uuid);
        if (session == null) {
            return 0L;
        }
        return Math.max(0L, session.expiry - System.currentTimeMillis());
    }

    public int remainingSeconds(UUID uuid) {
        long millis = remainingMillis(uuid);
        return (int) Math.ceil(millis / 1000.0D);
    }

    /** Combat 中の対戦相手 (現在も Combat 中の相手のみ)。 */
    public Set<UUID> opponents(UUID uuid) {
        Set<UUID> result = new LinkedHashSet<>();
        Session session = sessions.get(uuid);
        if (session == null) {
            return result;
        }
        for (UUID other : session.opponents) {
            if (inCombat(other)) {
                result.add(other);
            }
        }
        return result;
    }

    public void clear(UUID uuid) {
        sessions.remove(uuid);
    }

    public void clearAll() {
        sessions.clear();
    }

    public Set<UUID> combatants() {
        Set<UUID> result = new LinkedHashSet<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (entry.getValue().expiry > now) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (UUID uuid : new LinkedHashSet<>(sessions.keySet())) {
            Session session = sessions.get(uuid);
            if (session == null) {
                continue;
            }
            if (session.expiry <= now) {
                sessions.remove(uuid);
                continue;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            int seconds = (int) Math.ceil((session.expiry - now) / 1000.0D);
            plugin.messages().actionBar(player, "combat-actionbar", "seconds", String.valueOf(seconds));
        }
    }
}
