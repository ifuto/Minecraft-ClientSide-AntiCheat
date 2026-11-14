package com.ifuto.heartsmp.tpa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.util.Msg;

/** TPA / TPA Here の管理。 */
public final class TpaManager {

    public enum Type {
        TPA,
        TPA_HERE
    }

    /** 送信者ごとの有効なリクエスト。 */
    private static final class Request {
        private final UUID sender;
        private final UUID target;
        private final Type type;
        private long expiresAt;

        private Request(UUID sender, UUID target, Type type, long expiresAt) {
            this.sender = sender;
            this.target = target;
            this.type = type;
            this.expiresAt = expiresAt;
        }
    }

    private final HeartSMP plugin;
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Long> lastSendAt = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private BukkitTask expiryTask;

    public TpaManager(HeartSMP plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
    }

    // ------------------------------------------------------------------
    // 送信
    // ------------------------------------------------------------------

    public void request(Player sender, Player target, Type type) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            plugin.messages().error(sender, "tpa-self");
            return;
        }
        long now = System.currentTimeMillis();
        Long cooldown = cooldownUntil.get(sender.getUniqueId());
        if (cooldown != null && cooldown > now) {
            plugin.messages().error(sender, "tpa-cooldown", "seconds", String.valueOf(seconds(cooldown - now)));
            return;
        }

        Request existing = requests.get(sender.getUniqueId());
        if (existing != null && existing.expiresAt > now && existing.target.equals(target.getUniqueId())
                && existing.type == type) {
            // 期限延長 + 再通知
            existing.expiresAt = now + plugin.settings().tpaLifetimeMillis;
            plugin.messages().send(sender, "tpa-extended",
                    "seconds", String.valueOf(plugin.settings().tpaLifetimeMillis / 1000L));
            notifyTarget(sender, target, type);
            return;
        }

        Long last = lastSendAt.get(sender.getUniqueId());
        if (existing != null && existing.expiresAt > now) {
            // 別の相手への送信は再送扱い (30s クールダウン)
            if (last != null && now - last < plugin.settings().tpaResendCooldownMillis) {
                plugin.messages().error(sender, "tpa-cooldown", "seconds",
                        String.valueOf(seconds(plugin.settings().tpaResendCooldownMillis - (now - last))));
                return;
            }
            requests.remove(sender.getUniqueId());
        } else {
            requests.remove(sender.getUniqueId());
            if (last != null && now - last < plugin.settings().tpaResendCooldownMillis) {
                plugin.messages().error(sender, "tpa-cooldown", "seconds",
                        String.valueOf(seconds(plugin.settings().tpaResendCooldownMillis - (now - last))));
                return;
            }
        }

        Request request = new Request(sender.getUniqueId(), target.getUniqueId(), type,
                now + plugin.settings().tpaLifetimeMillis);
        requests.put(sender.getUniqueId(), request);
        lastSendAt.put(sender.getUniqueId(), now);

        String key = type == Type.TPA ? "tpa-sent" : "tpa-sent-here";
        Component message = plugin.messages().get(key, "player", target.getName())
                .append(Component.newline())
                .append(plugin.messages().get("tpa-sent-cancel-button")
                        .clickEvent(ClickEvent.runCommand("/tpacancel " + target.getName())));
        sender.sendMessage(message);
        notifyTarget(sender, target, type);
    }

    private void notifyTarget(Player sender, Player target, Type type) {
        String key = type == Type.TPA ? "tpa-received" : "tpa-received-here";
        Component accept = plugin.messages().get("tpa-accept-button")
                .clickEvent(ClickEvent.runCommand("/tpaccept " + sender.getName()));
        Component deny = plugin.messages().get("tpa-deny-button")
                .clickEvent(ClickEvent.runCommand("/tpadeny " + sender.getName()));
        target.sendMessage(plugin.messages().get(key, "player", sender.getName())
                .append(Component.newline())
                .append(accept)
                .append(Component.space())
                .append(deny));
        target.playSound(target.getLocation(), "entity.experience_orb.pickup", 0.7F, 1.4F);
    }

    // ------------------------------------------------------------------
    // 受諾 / 拒否 / 取消
    // ------------------------------------------------------------------

    public void accept(Player target, String senderName) {
        Player sender = resolveSender(target, senderName);
        if (sender == null) {
            return;
        }
        Request request = requests.get(sender.getUniqueId());
        if (request == null || request.expiresAt <= System.currentTimeMillis()
                || !request.target.equals(target.getUniqueId())) {
            plugin.messages().error(target, "tpa-no-request");
            return;
        }
        requests.remove(sender.getUniqueId());
        startCountdown(sender, target, request.type);
    }

    public void deny(Player target, String senderName) {
        Player sender = resolveSender(target, senderName);
        if (sender == null) {
            return;
        }
        Request request = requests.get(sender.getUniqueId());
        if (request == null || !request.target.equals(target.getUniqueId())) {
            plugin.messages().error(target, "tpa-no-request");
            return;
        }
        requests.remove(sender.getUniqueId());
        plugin.messages().send(target, "tpa-denied-target", "player", sender.getName());
        plugin.messages().send(sender, "tpa-denied", "player", target.getName());
    }

    public void cancel(Player sender, String targetName) {
        Request request = requests.get(sender.getUniqueId());
        if (request == null || request.expiresAt <= System.currentTimeMillis()) {
            requests.remove(sender.getUniqueId());
            plugin.messages().error(sender, "tpa-no-request");
            return;
        }
        Player target = Bukkit.getPlayer(request.target);
        if (targetName != null && target != null && !target.getName().equalsIgnoreCase(targetName)) {
            plugin.messages().error(sender, "tpa-no-request");
            return;
        }
        requests.remove(sender.getUniqueId());
        plugin.messages().send(sender, "tpa-cancelled",
                "player", target == null ? targetNameOr(request, sender) : target.getName());
    }

    private String targetNameOr(Request request, Player sender) {
        Player target = Bukkit.getPlayer(request.target);
        if (target != null) {
            return target.getName();
        }
        return plugin.playerData().get(request.target).name();
    }

    private Player resolveSender(Player target, String senderName) {
        if (senderName != null) {
            Player sender = Bukkit.getPlayerExact(senderName);
            if (sender != null) {
                return sender;
            }
        }
        // 引数なし: 最も新しい受信リクエスト
        Request latest = latestIncoming(target.getUniqueId());
        if (latest == null) {
            plugin.messages().error(target, "tpa-no-request");
            return null;
        }
        Player sender = Bukkit.getPlayer(latest.sender);
        if (sender == null) {
            plugin.messages().error(target, "unknown-player-online", "player", senderName == null ? "?" : senderName);
            return null;
        }
        return sender;
    }

    private Request latestIncoming(UUID target) {
        Request latest = null;
        long now = System.currentTimeMillis();
        for (Request request : requests.values()) {
            if (!request.target.equals(target) || request.expiresAt <= now) {
                continue;
            }
            if (latest == null || request.expiresAt > latest.expiresAt) {
                latest = request;
            }
        }
        return latest;
    }

    // ------------------------------------------------------------------
    // カウントダウン
    // ------------------------------------------------------------------

    private void startCountdown(Player sender, Player target, Type type) {
        int total = plugin.settings().tpaCountdownSeconds;
        plugin.messages().send(sender, "tpa-accepted", "seconds", String.valueOf(total));
        plugin.messages().send(target, "tpa-accepted-target",
                "player", sender.getName(), "seconds", String.valueOf(total));

        Location senderStart = sender.getLocation().clone();
        Location targetStart = target.getLocation().clone();
        double threshold = plugin.settings().tpaMoveThreshold;

        Countdown countdown = new Countdown(plugin, total, new Countdown.Step() {
            @Override
            public boolean tick(int remaining) {
                if (!sender.isOnline() || !target.isOnline()) {
                    return false;
                }
                if (moved(senderStart, sender.getLocation(), threshold)
                        || moved(targetStart, target.getLocation(), threshold)) {
                    return false;
                }
                if (remaining > 0) {
                    plugin.messages().send(sender, "tpa-countdown", "seconds", String.valueOf(remaining));
                } else {
                    finish(sender, target, type);
                }
                return true;
            }

            @Override
            public void abort() {
                cooldownUntil.put(sender.getUniqueId(),
                        System.currentTimeMillis() + plugin.settings().tpaAbortCooldownMillis);
                if (sender.isOnline()) {
                    plugin.messages().error(sender, "tpa-aborted");
                }
                if (target.isOnline()) {
                    plugin.messages().error(target, "tpa-aborted");
                }
            }
        });
        countdown.run();
    }

    private boolean moved(Location start, Location current, double threshold) {
        if (start.getWorld() == null || current.getWorld() == null || !start.getWorld().equals(current.getWorld())) {
            return true;
        }
        return start.distanceSquared(current) > threshold * threshold;
    }

    private void finish(Player sender, Player target, Type type) {
        Player mover = type == Type.TPA ? sender : target;
        Player destination = type == Type.TPA ? target : sender;
        mover.teleport(destination.getLocation());
        plugin.messages().send(mover, "tpa-teleported");
        plugin.messages().send(type == Type.TPA ? target : sender, "tpa-teleported");
        cooldownUntil.put(sender.getUniqueId(), System.currentTimeMillis() + plugin.settings().tpaPostTeleportCooldownMillis);
    }

    // ------------------------------------------------------------------
    // 期限切れ処理
    // ------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Request> entry : requests.entrySet()) {
            if (entry.getValue().expiresAt <= now) {
                expired.add(entry.getKey());
            }
        }
        for (UUID uuid : expired) {
            Request request = requests.remove(uuid);
            if (request == null) {
                continue;
            }
            Player sender = Bukkit.getPlayer(uuid);
            Player target = Bukkit.getPlayer(request.target);
            if (sender != null) {
                plugin.messages().send(sender, "tpa-expired-sender", "player",
                        target != null ? target.getName() : plugin.playerData().get(request.target).name());
            }
            if (target != null) {
                plugin.messages().send(target, "tpa-expired-target", "player",
                        sender != null ? sender.getName() : plugin.playerData().get(uuid).name());
            }
        }
    }

    /** 退出時に送信側のリクエストを破棄します。 */
    public void playerQuit(UUID sender) {
        requests.remove(sender);
    }

    public boolean hasRequest(UUID sender) {
        Request request = requests.get(sender);
        return request != null && request.expiresAt > System.currentTimeMillis();
    }

    private static long seconds(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    /** 秒刻みのカウントダウン用ヘルパ。 */
    public static final class Countdown {

        public interface Step {
            /** @return false でカウントダウンを中断します (abort が呼ばれます)。 */
            boolean tick(int remaining);

            void abort();
        }

        private final org.bukkit.plugin.Plugin plugin;
        private final Step step;
        private BukkitTask task;
        private int remaining;

        public Countdown(org.bukkit.plugin.Plugin plugin, int seconds, Step step) {
            this.plugin = plugin;
            this.step = step;
            this.remaining = seconds;
        }

        public void run() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (remaining < 0) {
                    cancel();
                    return;
                }
                boolean keep;
                try {
                    keep = step.tick(remaining);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("TPA カウントダウン中にエラー: " + ex.getMessage());
                    keep = false;
                }
                if (!keep) {
                    boolean aborted = remaining > 0;
                    cancel();
                    if (aborted) {
                        step.abort();
                    }
                    return;
                }
                remaining--;
            }, 0L, 20L);
        }

        public void cancel() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
