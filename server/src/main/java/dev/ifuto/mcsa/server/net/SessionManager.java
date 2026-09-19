package dev.ifuto.mcsa.server.net;

import dev.ifuto.mcsa.server.McsaConfig;
import dev.ifuto.mcsa.server.McsaPlugin;
import dev.ifuto.mcsa.server.alert.AlertService;
import dev.ifuto.mcsa.server.report.ClientReport;
import dev.ifuto.mcsa.server.util.Hmac;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import dev.ifuto.mcsa.server.util.Hex;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    /** 証拠転送の 1 断片（クライアント側 {@code ShotPayload.CHUNK_SIZE} と一致させる） */
    private static final int SHOT_CHUNK = 16384;

    private final McsaPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextSessionId = new AtomicInteger(1);
    private final AtomicInteger nextTransferId = new AtomicInteger(1);
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
        // 指示（task）で nonce が切り替わった直後に届いたレポートも拾えるよう、直前の nonce でも試す
        boolean valid = !config.hmacEnabled
                || Hmac.verify(config.hmacKey, session.nonce(), gzip, seal.hmac())
                || Hmac.verify(config.hmacKey, session.previousNonce(), gzip, seal.hmac());
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
        plugin.injectionPolicy().classify(report);
        for (String runtimeFlag : session.runtimeFlags()) {
            report.flag(runtimeFlag, true);
        }
        plugin.reports().put(player, report);
        plugin.alerts().onReport(player, report);

        if (report.hasCritical() && "KICK".equalsIgnoreCase(config.onBanned)) {
            player.kick(AlertService.legacy(config.bannedKickMessage));
        }
    }

    // ------------------------------------------------------------------ 指示

    /**
     * OP からの指示をクライアントへ送る。
     *
     * <p>指示ごとに nonce を切り替える。同じ指示を使い回せないようにするため。
     *
     * @return 送れたか
     */
    public boolean sendTask(Player player, int kind, String reason, int intervalSeconds, String requestedBy) {
        Session session = session(player);
        if (session == null || !player.isOnline()) {
            return false;
        }
        String nonce = session.rotateNonce(newNonce());
        session.request(requestedBy, reason);
        byte[] payload = Wire.encodeTask(plugin.config().protocol, session.sessionId(), nonce,
                kind, intervalSeconds, reason);
        try {
            player.sendPluginMessage(plugin, Wire.CH_TASK, payload);
        } catch (RuntimeException e) {
            plugin.getLogger().warning(player.getName() + " への指示送信に失敗: " + e);
            return false;
        }
        return true;
    }

    /**
     * 画面取得の指示。
     *
     * <p>既定では無効（{@code evidence.capture.enabled}）。有効化するには
     * サーバールールでの告知が必要（{@code docs/PRIVACY.md}）。
     */
    public String requestCapture(Player player, String reason, String requestedBy) {
        McsaConfig config = plugin.config();
        if (!config.captureEnabled) {
            return "画面取得は無効です (config.yml: evidence.capture.enabled=true にすると有効化できます。"
                    + "有効化する場合は利用規約での告知が必要です / docs/PRIVACY.md)";
        }
        Session session = session(player);
        if (session == null) {
            return "セッションがありません";
        }
        if (!session.hasMod()) {
            return player.getName() + " は MCSA クライアント MOD が入っていないようです";
        }
        long now = System.currentTimeMillis();
        long minimum = config.captureMinIntervalSeconds * 1000L;
        if (session.lastCaptureAt() > 0 && now - session.lastCaptureAt() < minimum) {
            return "取得間隔が短すぎます（あと "
                    + ((minimum - (now - session.lastCaptureAt())) / 1000 + 1) + " 秒）";
        }
        if (!session.captureSupported()) {
            plugin.alerts().info(player, "このクライアントは画面取得に対応していない可能性があります"
                    + "（ダイジェストのフラグに capture が出ていません）");
        }
        session.markCapture();
        boolean sent = sendTask(player, Wire.TASK_CAPTURE, reason, 0, requestedBy);
        if (!sent) {
            return "指示を送れませんでした";
        }
        plugin.alerts().info(player, "画面取得を要求しました (by=" + requestedBy + ", reason="
                + (reason == null || reason.isBlank() ? "-" : reason) + ")");
        return "要求しました。届くと " + plugin.evidence().root() + " に保存されます";
    }

    /** 監視モード（一定時間、高頻度のダイジェスト＋画面取得） */
    public String startWatch(Player player, int seconds, String requestedBy) {
        McsaConfig config = plugin.config();
        Session session = session(player);
        if (session == null) {
            return "セッションがありません";
        }
        int capped = Math.max(10, Math.min(config.watchMaxSeconds, seconds));
        session.watchUntil(System.currentTimeMillis() + capped * 1000L);
        sendTask(player, Wire.TASK_WATCH_ON, "watch", config.watchIntervalSeconds, requestedBy);
        plugin.alerts().info(player, "監視を開始しました (" + capped + " 秒, by=" + requestedBy + ")");
        return capped + " 秒間の監視を開始しました（ダイジェスト間隔 " + config.watchIntervalSeconds + " 秒）";
    }

    public String stopWatch(Player player, String requestedBy) {
        Session session = session(player);
        if (session == null) {
            return "セッションがありません";
        }
        session.watchUntil(0);
        sendTask(player, Wire.TASK_WATCH_OFF, "", 0, requestedBy);
        return "監視を終了しました (by=" + requestedBy + ")";
    }

    // -------------------------------------------------------------- 証拠 / 監視

    /** C2S: 証拠（画面 / テキスト） */
    public void onEvidence(Player player, byte[] raw) {
        Session session = session(player);
        if (session == null) {
            return;
        }
        Wire.Evidence evidence;
        try {
            evidence = Wire.decodeEvidence(raw);
        } catch (IllegalArgumentException e) {
            session.lastError("証拠の解析に失敗: " + e.getMessage());
            return;
        }
        if (evidence.sessionId() != session.sessionId()) {
            session.lastError("証拠のセッション不一致 (expected=" + session.sessionId()
                    + ", got=" + evidence.sessionId() + ")");
            return;
        }
        byte[] data = session.acceptEvidence(evidence);
        if (data == null) {
            return; // まだ揃っていない
        }
        McsaConfig config = plugin.config();
        if (data.length > config.captureMaxBytes) {
            session.lastError("証拠が大きすぎます: " + data.length);
            plugin.alerts().info(player, "証拠が大きすぎるため破棄しました (" + data.length + " bytes)");
            return;
        }
        boolean valid = !config.hmacEnabled
                || Hmac.verify(config.hmacKey, session.nonce(), data, evidence.hmac())
                || Hmac.verify(config.hmacKey, session.previousNonce(), data, evidence.hmac());
        Path file = plugin.evidence().save(player, evidence.kind(), evidence.name(), data,
                session.requestedBy(), session.requestReason(), evidence.hmac(), valid);
        if (file == null) {
            plugin.alerts().info(player, "証拠の保存に失敗しました");
            return;
        }
        if (!valid) {
            session.runtimeFlag("EVIDENCE_HMAC_INVALID");
            plugin.alerts().info(player, "証拠の HMAC が一致しません（改ざんの可能性。保存はしました）");
        }
        plugin.alerts().evidence(player, (evidence.kind() == Wire.EVIDENCE_SHOT ? "画面" : "テキスト")
                + "を受信しました: " + plugin.evidence().root().relativize(file)
                + " (" + data.length + " bytes, hmac=" + (valid ? "ok" : "INVALID") + ")");

        // 指示を出した OP が OP 用 MOD を入れていたら、その画面に転送する
        Player requester = Bukkit.getPlayerExact(session.requestedBy());
        if (requester != null && forwardEvidence(requester, evidence.kind(), evidence.name(), data)) {
            plugin.alerts().info(requester, player.getName() + " の"
                    + (evidence.kind() == Wire.EVIDENCE_SHOT ? "画面" : "テキスト")
                    + "をあなたのクライアントに転送しました");
        }
    }

    /**
     * 証拠（画面）を OP のクライアントへ転送する。
     *
     * <p>OP 用 MOD（{@code mcsa-admin}）を入れていない相手には送れないので、
     * {@code hasListeningPluginChannel} で確認してから送る。
     *
     * @return 転送できたか
     */
    public boolean forwardEvidence(Player admin, int kind, String name, byte[] data) {
        if (admin == null || !admin.isOnline() || data == null) {
            return false;
        }
        if (!admin.getListeningPluginChannels().contains(Wire.CH_SHOT)) {
            return false;
        }
        int transferId = nextTransferId.getAndIncrement();
        int total = Math.max(1, (data.length + SHOT_CHUNK - 1) / SHOT_CHUNK);
        try {
            for (int seq = 0; seq < total; seq++) {
                int from = seq * SHOT_CHUNK;
                int length = Math.min(SHOT_CHUNK, data.length - from);
                byte[] chunk = new byte[Math.max(length, 0)];
                if (length > 0) {
                    System.arraycopy(data, from, chunk, 0, length);
                }
                admin.sendPluginMessage(plugin, Wire.CH_SHOT,
                        Wire.encodeShot(transferId, kind, seq, total, name, chunk));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("証拠の転送に失敗しました: " + e);
            return false;
        }
        return true;
    }

    /** C2S: ウォッチドッグのダイジェスト（ハートビート） */
    public void onDigest(Player player, byte[] raw) {
        Session session = session(player);
        if (session == null) {
            return;
        }
        Wire.Digest digest;
        try {
            digest = Wire.decodeDigest(raw);
        } catch (IllegalArgumentException e) {
            session.lastError("ダイジェストの解析に失敗: " + e.getMessage());
            return;
        }
        if (digest.sessionId() != session.sessionId()) {
            return;
        }
        int previous = session.digestFlags();
        session.onDigest(digest);
        if (session.silentAlerted()) {
            session.silentAlerted(false);
            plugin.alerts().info(player, "ダイジェストの送信が再開しました");
        }
        int changed = digest.flags() & ~Wire.DIGEST_CAPTURE_SUPPORTED;
        if (changed == 0) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        append(builder, changed, Wire.DIGEST_SELF_JAR_CHANGED, "自分の jar が起動時と違う");
        append(builder, changed, Wire.DIGEST_MIXIN_CHANGED, "Mixin 設定が増減した");
        append(builder, changed, Wire.DIGEST_LIBRARY_CHANGED, "読み込み中のライブラリが変わった");
        append(builder, changed, Wire.DIGEST_PROBE_APPEARED, "検知対象のクラスが現れた");
        append(builder, changed, Wire.DIGEST_CLASSLOADER_CHANGED, "クラスローダの連鎖が変わった");
        session.runtimeFlag("STATE_CHANGED:" + digest.stateHex());
        plugin.alerts().warn(player, "実行時の状態が変化しました: " + builder);
        // 詳細は次のレポートで取れるので、すぐに取り直す
        sendChallenge(player);
        if (previous == 0 && plugin.config().watchdogKickOnChange) {
            player.kick(AlertService.legacy(plugin.config().kickMessage));
        }
    }

    private static void append(StringBuilder builder, int flags, int bit, String message) {
        if ((flags & bit) != 0) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(message);
        }
    }

    /** C2S: OP 用 MOD からのコマンド実行要求 */
    public void onAdmin(CommandSender sender, byte[] raw) {
        String text;
        try {
            text = Wire.decodeAdmin(raw).trim();
        } catch (IllegalArgumentException e) {
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        if (!sender.hasPermission("mcsa.admin")) {
            reply(sender, "権限がありません (mcsa.admin)");
            return;
        }
        String command = "ac " + text;
        plugin.getLogger().info("[admin-mod] " + sender.getName() + " -> /" + command);
        reply(sender, "/" + command + " を実行します");
        Bukkit.dispatchCommand(sender, command);
    }

    private void reply(CommandSender sender, String message) {
        if (!(sender instanceof Player player)) {
            return;
        }
        try {
            player.sendPluginMessage(plugin, Wire.CH_ADMIN_MSG, Wire.encodeAdminMessage(message));
        } catch (RuntimeException ignored) {
            // チャンネル未登録のクライアントには届かないだけ
        }
    }

    /** 定期監視。ダイジェストの途絶と、監視モード中の画面取得を見る。 */
    public void watchdogTick() {
        McsaConfig config = plugin.config();
        long timeoutMillis = config.watchdogTimeoutSeconds * 1000L;
        long captureEveryMillis = config.watchCaptureIntervalSeconds * 1000L;
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Session session = session(player);
            if (session == null || !session.hasMod()) {
                continue;
            }
            long last = session.lastDigestAt();
            if (last > 0 && timeoutMillis > 0 && now - last > timeoutMillis) {
                if (!session.silentAlerted()) {
                    session.silentAlerted(true);
                    session.runtimeFlag("WATCHDOG_SILENT");
                    plugin.alerts().warn(player, "状態ダイジェストが "
                            + ((now - last) / 1000) + " 秒届いていません（MOD が停止された可能性）");
                }
            }
            if (session.watching() && config.captureEnabled
                    && now - session.lastCaptureAt() >= captureEveryMillis) {
                session.markCapture();
                sendTask(player, Wire.TASK_CAPTURE, "watch", 0, "watchdog");
            }
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
