package dev.ifuto.mcsa.server.net;

import dev.ifuto.mcsa.server.McsaConfig;
import dev.ifuto.mcsa.server.McsaPlugin;
import dev.ifuto.mcsa.server.alert.AlertService;
import dev.ifuto.mcsa.server.report.ClientReport;
import dev.ifuto.mcsa.server.util.Hex;
import dev.ifuto.mcsa.server.util.Hmac;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * ハンドシェイクの進行管理。
 *
 * <pre>
 * 入室 ──▶ (delay-ticks) ──▶ mcsa:challenge 送信
 *                                    │
 *                        クライアント │ mcsa:hello
 *                                    │ mcsa:report ×N（gzip 断片）
 *                                    ▼ mcsa:seal（HMAC）
 *                            検証 → 判定 → 保存 → アラート
 *
 * 入室 ──▶ (grace-ticks) ──▶ 導入必須なら HELLO の有無を判定（KICK/WARN/LOG）
 * </pre>
 */
public final class SessionManager implements Listener {

    private static final int MAX_REPORT_BYTES = 8 * 1024 * 1024;

    private final McsaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextSessionId = new AtomicInteger(1);
    private final SecureRandom random = new SecureRandom();

    public SessionManager(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ 入室

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Session session = new Session(player.getUniqueId(), nextSessionId.getAndIncrement(), newNonce());
        sessions.put(player.getUniqueId(), session);

        McsaConfig config = plugin.config();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> sendChallenge(player),
                Math.max(1, config.challengeDelayTicks));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> enforce(player),
                Math.max(1, config.graceTicks));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    public Session session(UUID playerId) {
        return sessions.get(playerId);
    }

    public Session session(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    public boolean isModInstalled(Player player) {
        Session session = session(player);
        return session != null && session.hasMod();
    }

    // ------------------------------------------------------------------ 送信

    public boolean sendChallenge(Player player) {
        Session session = session(player);
        if (session == null || !player.isOnline()) {
            return false;
        }
        McsaConfig config = plugin.config();
        byte[] payload = Wire.encodeChallenge(config.protocol, session.sessionId(), session.nonce(),
                plugin.getServer().getName(), config.probeClasses, config.collectFlags);
        try {
            player.sendPluginMessage(plugin, Wire.CH_CHALLENGE, payload);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(player.getName() + " へのチャレンジ送信に失敗: " + e);
            return false;
        }
        session.markChallenged();
        return true;
    }

    /** 定期再要求（interval-ticks）。 */
    public void rechallenges() {
        long intervalMillis = plugin.config().challengeIntervalTicks * 50L;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Session session = session(player);
            if (session == null) {
                continue;
            }
            if (System.currentTimeMillis() - session.lastChallengeAt() >= intervalMillis) {
                sendChallenge(player);
            }
        }
    }

    // ------------------------------------------------------------------ 受信

    public void onHello(Player player, byte[] raw) {
        Session session = session(player);
        if (session == null) {
            return;
        }
        Wire.Hello hello;
        try {
            hello = Wire.decodeHello(raw);
        } catch (IllegalArgumentException e) {
            session.lastError("HELLO の解析に失敗: " + e.getMessage());
            return;
        }
        session.hello(hello);
        session.lastError(null);

        McsaConfig config = plugin.config();
        if (config.hmacEnabled && config.hmacKey.length > 0) {
            String expected = Hmac.keyId(config.hmacKey);
            if (!expected.equalsIgnoreCase(hello.keyId())) {
                session.lastError("鍵の指紋が一致しません (server=" + expected + ", client=" + hello.keyId() + ")");
                plugin.alerts().info(player, "クライアントの鍵が違います。配布した jar と config.yml の hmac.key を確認してください");
            }
        }
        plugin.alerts().info(player, "HELLO 受信 (mod=" + hello.modVersion() + ", jar="
                + abbreviate(hello.selfJarSha256()) + ", flags=" + hello.flags() + ")");
    }

    public void onChunk(Player player, byte[] raw) {
        Session session = session(player);
        if (session == null) {
            return;
        }
        try {
            session.accept(Wire.decodeChunk(raw));
        } catch (IllegalArgumentException e) {
            session.lastError("レポート断片の解析に失敗: " + e.getMessage());
        }
    }

    public void onSeal(Player player, byte[] raw) {
        Session session = session(player);
        if (session == null) {
            return;
        }
        Wire.Seal seal;
        try {
            seal = Wire.decodeSeal(raw);
        } catch (IllegalArgumentException e) {
            session.lastError("SEAL の解析に失敗: " + e.getMessage());
            return;
        }

        byte[] gzip = session.takeAssembled();
        if (gzip == null) {
            session.lastError("SEAL が届く前にレポートが揃っていません");
            return;
        }
        if (gzip.length > MAX_REPORT_BYTES || seal.dataLength() != gzip.length) {
            session.lastError("レポート長が一致しません (seal=" + seal.dataLength() + ", actual=" + gzip.length + ")");
            return;
        }

        McsaConfig config = plugin.config();
        boolean valid = config.hmacEnabled
                ? Hmac.verify(config.hmacKey, session.nonce(), gzip, seal.hmac())
                : true;
        if (!valid && "REJECT".equalsIgnoreCase(config.hmacOnInvalid)) {
            session.lastError("HMAC 検証に失敗したためレポートを破棄しました");
            plugin.alerts().info(player, "レポートの HMAC 検証に失敗しました（破棄）");
            return;
        }

        String json;
        try {
            json = gunzip(gzip);
        } catch (IOException e) {
            session.lastError("レポートの展開に失敗: " + e.getMessage());
            return;
        }

        ClientReport report;
        try {
            String keyId = session.hello() == null ? "" : session.hello().keyId();
            report = ClientReport.parse(player, json, valid, keyId);
        } catch (RuntimeException e) {
            session.lastError("レポート JSON の解析に失敗: " + e.getMessage());
            return;
        }

        session.report(report);
        session.lastError(null);
        plugin.modPolicy().classify(report);
        plugin.reports().put(player, report);
        plugin.alerts().onReport(player, report);

        if (report.hasCritical() && "KICK".equalsIgnoreCase(config.onBanned)) {
            player.kick(AlertService.legacy(config.bannedKickMessage));
        }
    }

    // ------------------------------------------------------------------ 判定

    /** 導入必須ポリシーの適用。入室後 grace-ticks で 1 回。 */
    public void enforce(Player player) {
        if (!player.isOnline()) {
            return;
        }
        McsaConfig config = plugin.config();
        if ("OFF".equalsIgnoreCase(config.enforceMode)) {
            return;
        }
        if (player.hasPermission("mcsa.bypass")) {
            return;
        }
        if (!config.isRequired(player)) {
            return;
        }
        if (isModInstalled(player)) {
            return;
        }
        switch (config.enforceAction.toUpperCase()) {
            case "KICK" -> player.kick(AlertService.legacy(config.kickMessage));
            case "WARN" -> {
                player.sendMessage(AlertService.legacy(config.warnMessage));
                plugin.alerts().info(player, "導入必須の対象ですが MOD を検出できませんでした");
            }
            default -> plugin.alerts().info(player, "導入必須の対象ですが MOD を検出できませんでした (LOG)");
        }
    }

    // ------------------------------------------------------------------ 内部

    private String newNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Hex.encode(bytes);
    }

    private static String gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length * 4));
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_REPORT_BYTES) {
                    throw new IOException("レポートが大きすぎます");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 12) {
            return String.valueOf(value);
        }
        return value.substring(0, 12) + "…";
    }
}
