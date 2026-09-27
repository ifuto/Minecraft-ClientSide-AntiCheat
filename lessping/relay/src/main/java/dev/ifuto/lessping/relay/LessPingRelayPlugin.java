package dev.ifuto.lessping.relay;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LessPing-NArena 中継プラグイン（Velocity）。
 *
 * <p>やることは 2 つ:
 * <ol>
 *   <li>「アドレスの紹介」（シグナリング）: LessPing MOD 入りのクライアントが
 *       HELLO（自分の UDP アドレス）を送ってくる。SMP と PvP のどちらのバックエンドに
 *       いても、プラグインメッセージはプロキシ（ここ）で受け取れる。INTRO_REQUEST で
 *       両者へ相手のアドレスを紹介する → 両者は UDP ホールパンチで直接つながる</li>
 *   <li>「ホスト側トンネル端末」({@link TunnelHost}): このプロキシと同じ PC 上で
 *       P2P の受け口を務める。サーバー主がゲームを起動していなくても、参加者が
 *       「narena」に接続するだけでこの PC の Velocity へ直接届く。候補アドレスは
 *       サーバーリスト ping 応答の version 名に載せて公開する</li>
 * </ol>
 *
 * <p>ゲームの通信はこのプロキシを経由しない（P2P）。ホールパンチが失敗する
 * （対称 NAT 等）場合は通常接続にフォールバックするだけ。
 */
// 注意: version はリテラルで書くこと（定数参照はアノテーション処理中に
// AnnotationTypeMismatchException になる）。リリース時は VERSION 定数と合わせる
@Plugin(id = "lessping-narena", name = "LessPing-NArena", version = "0.3.2",
        description = "LessPing-NArena のシグナリング中継 + ホスト側トンネル端末（P2P 直結）",
        url = "https://github.com/ifuto/Minecraft-ClientSide-AntiCheat",
        authors = {"ifuto"})
public final class LessPingRelayPlugin {

    /** gradle.properties の lessping_version と合わせる */
    public static final String VERSION = "0.3.2";

    /** クライアント → プロキシ（HELLO / INTRO_REQUEST） */
    public static final MinecraftChannelIdentifier CHANNEL_IN =
            MinecraftChannelIdentifier.create("lessping", "signal");
    /** プロキシ → クライアント（INTRO） */
    public static final MinecraftChannelIdentifier CHANNEL_OUT =
            MinecraftChannelIdentifier.create("lessping", "events");

    private static final int TYPE_HELLO = 1;
    private static final int TYPE_INTRO_REQUEST = 2;
    private static final int TYPE_INTRO = 3;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Properties config = new Properties();
    private TunnelHost tunnelHost;
    /** WebSocket ⇄ TCP ブリッジ（Cloudflare Tunnel 等の IP 非公開経路） */
    private WsServer wsServer;

    /** 登録されたプレイヤー（小文字名 → 情報） */
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    /** まだオフラインの対象への紹介要求（対象の小文字名 → 要求者の名前） */
    private final Map<String, Set<String>> pendingRequests = new ConcurrentHashMap<>();

    private record PeerInfo(String name, String data, long at) {
    }

    @Inject
    public LessPingRelayPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    // ------------------------------------------------------------------ ライフサイクル

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        proxy.getEventManager().register(this, this);
        proxy.getChannelRegistrar().register(CHANNEL_IN);

        CommandManager commands = proxy.getCommandManager();
        CommandMeta meta = commands.metaBuilder("lessping").aliases("lp").plugin(this).build();
        commands.register(meta, new LpCommand());

        if (getBool("host-endpoint.enabled", false)) {
            try {
                tunnelHost = new TunnelHost(logger, buildSettings());
                tunnelHost.start();
            } catch (Exception e) {
                logger.error("ホスト端末を開始できません: {}", e.toString());
                tunnelHost = null;
            }
        }
        if (getBool("ws.enabled", true)) {
            try {
                String[] backend = backendTarget();
                String listen = getString("ws.listen", "127.0.0.1:8081");
                String[] lp = listen.split(":");
                wsServer = new WsServer(logger, new WsServer.Settings(
                        lp[0], Integer.parseInt(lp[lp.length - 1]),
                        (int) getLong("ws.max-connections", 16),
                        backend[0], Integer.parseInt(backend[1]), VERSION));
                wsServer.start();
            } catch (Exception e) {
                logger.error("WebSocket ブリッジを開始できません: {}", e.toString());
                wsServer = null;
            }
        }
        logger.info("LessPing-NArena 中継を開始（{}）。ゲーム通信は経由しません（P2P）。", VERSION);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (wsServer != null) {
            wsServer.stop();
            wsServer = null;
        }
        if (tunnelHost != null) {
            tunnelHost.stop();
            tunnelHost = null;
        }
        peers.clear();
        pendingRequests.clear();
    }

    // ------------------------------------------------------------------ 設定

    private void loadConfig() {
        Path file = dataDirectory.resolve("config.properties");
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/config.properties")) {
                    if (in != null) {
                        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file);
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    config.load(reader);
                }
            }
        } catch (IOException e) {
            logger.warn("設定の読み込みに失敗（既定値を使います）: {}", e.toString());
        }
    }

    /**
     * トンネル / WebSocket ブリッジの転送先（既定 = このプロキシ自身。
     * SMP / PvP どちらにも /server で行ける）。{host, port} を返す
     */
    private String[] backendTarget() {
        String backend = getString("host-endpoint.backend", "");
        if (backend == null || backend.isBlank()) {
            InetSocketAddress bound = proxy.getBoundAddress();
            String host = bound.getHostString();
            if (host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::")) {
                host = "127.0.0.1";
            }
            return new String[]{host, String.valueOf(bound.getPort())};
        }
        String[] hp = backend.split(":");
        return new String[]{hp[0].isBlank() ? "127.0.0.1" : hp[0], hp[hp.length - 1]};
    }

    /** config.properties + プロキシの状態からホスト端末の設定を組み立てる */
    private TunnelHost.Settings buildSettings() {
        String[] backend = backendTarget();
        String backendHost = backend[0];
        int backendPort = Integer.parseInt(backend[1]);
        List<String> stun = getList("host-endpoint.stun-servers",
                List.of("stun.cloudflare.com:3478", "stun.l.google.com:19302"));
        return new TunnelHost.Settings(
                (int) getLong("host-endpoint.udp-port", 0),
                backendHost,
                backendPort,
                dev.ifuto.lessping.tunnel.lpx.LpSecret.resolve(getString("host-endpoint.secret", "")),
                stun,
                Math.max(10_000L, getLong("host-endpoint.stun-interval-ms", 30_000L)),
                5000L,
                (int) getLong("host-endpoint.max-connections", 32),
                getBool("host-endpoint.publish", true),
                VERSION);
    }

    private String getString(String key, String def) {
        String value = config.getProperty(key);
        return value == null ? def : value.trim();
    }

    private long getLong(String key, long def) {
        try {
            return Long.parseLong(getString(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean getBool(String key, boolean def) {
        String value = getString(key, String.valueOf(def));
        return value.isEmpty() ? def : Boolean.parseBoolean(value);
    }

    private List<String> getList(String key, List<String> def) {
        String value = getString(key, "");
        if (value.isEmpty()) {
            return new ArrayList<>(def);
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private boolean debug() {
        return getBool("debug", false);
    }

    // ------------------------------------------------------------------ プラグインメッセージ（ゲーム内シグナリング）

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL_IN.equals(event.getIdentifier())) {
            return;
        }
        // このチャンネルはこのプラグインで消費する（バックエンドへ流さない）
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof Player player)) {
            return;
        }
        Wire.Msg msg = Wire.parse(event.getData());
        if (msg == null) {
            logger.warn("{} から解析できないメッセージ（{} bytes）",
                    player.getUsername(), event.getData().length);
            return;
        }
        switch (msg.type()) {
            case TYPE_HELLO -> onHello(player, msg);
            case TYPE_INTRO_REQUEST -> onIntroRequest(player, msg);
            default -> {
                // 知らないタイプは無視
            }
        }
    }

    private void onHello(Player player, Wire.Msg msg) {
        // 名前は偽装できないように実際の接続者を使う
        peers.put(player.getUsername().toLowerCase(Locale.ROOT),
                new PeerInfo(player.getUsername(), msg.data(), System.currentTimeMillis()));
        if (debug()) {
            logger.info("{} の HELLO: {}", player.getUsername(), msg.data());
        }
        // このプレイヤーへの紹介要求が溜まっていたら応える
        Set<String> waiting = pendingRequests.remove(player.getUsername().toLowerCase(Locale.ROOT));
        if (waiting != null) {
            for (String requesterName : waiting) {
                Optional<Player> requester = proxy.getPlayer(requesterName);
                requester.ifPresent(r -> introduce(r, player));
            }
        }
    }

    private void onIntroRequest(Player player, Wire.Msg msg) {
        String targetName = msg.player();
        Optional<Player> targetOpt = proxy.getPlayer(targetName);
        PeerInfo target = peers.get(targetName.toLowerCase(Locale.ROOT));
        long freshness = getLong("freshness-ms", 600_000L);
        if (targetOpt.isPresent() && target != null
                && System.currentTimeMillis() - target.at() < freshness) {
            // 双方へ紹介（両者が同時に穴あけを始められるように）
            introduce(player, targetOpt.get());
            introduce(targetOpt.get(), player);
        } else {
            // 対象がまだ来ていない → 対象の参加時に紹介する
            pendingRequests.computeIfAbsent(targetName.toLowerCase(Locale.ROOT),
                    k -> ConcurrentHashMap.newKeySet()).add(player.getUsername());
            send(player, TYPE_INTRO, targetName, ""); // 空データ = まだ居ない、の合図
            if (debug()) {
                logger.info("{} が {} を要求したが、まだ登録がない", player.getUsername(), targetName);
            }
        }
    }

    /** about のアドレスを to へ教える */
    private void introduce(Player to, Player about) {
        PeerInfo info = peers.get(about.getUsername().toLowerCase(Locale.ROOT));
        String data = info == null ? "" : info.data();
        send(to, TYPE_INTRO, about.getUsername(), data);
        if (debug()) {
            logger.info("{} へ {} を紹介: {}", to.getUsername(), about.getUsername(), data);
        }
    }

    private void send(Player to, int type, String player, String data) {
        try {
            to.sendPluginMessage(CHANNEL_OUT, Wire.build(type, player, data));
        } catch (Exception e) {
            logger.warn("{} への送信に失敗: {}", to.getUsername(), e.toString());
        }
    }

    // ------------------------------------------------------------------ イベント

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        String name = event.getPlayer().getUsername();
        peers.remove(name.toLowerCase(Locale.ROOT));
        pendingRequests.values().forEach(set -> set.remove(name));
    }

    /**
     * サーバーリスト ping にホスト端末の候補アドレスを載せる（version 名の {@code LP1/LP2}）。
     * LessPing MOD はこれを読んで、本サーバーに入らずに直接穴あけを始める。
     * 通常のクライアントには（プロトコル一致なら）version 名は表示されない。
     * MOTD・人数などは Velocity が組み立てた内容をそのまま維持する。
     */
    @Subscribe
    public void onServerListPing(ProxyPingEvent event) {
        if (tunnelHost == null || !tunnelHost.isStarted() || !tunnelHost.shouldPublish()) {
            return;
        }
        ServerPing ping = event.getPing();
        ServerPing.Version version = ping.getVersion();
        if (version == null) {
            return;
        }
        event.setPing(ping.asBuilder()
                .version(new ServerPing.Version(version.getProtocol(), tunnelHost.blob()))
                .build());
    }

    // ------------------------------------------------------------------ コマンド

    // Velocity 4.x の Command は sealed（直接実装不可）なので Bukkit 風の
    // SimpleCommand を使う
    private final class LpCommand implements SimpleCommand {

        @Override
        public void execute(SimpleCommand.Invocation invocation) {
            CommandSource source = invocation.source();
            source.sendMessage(Component.text("[LessPing-NArena] 中継 v" + VERSION, NamedTextColor.GOLD));
            source.sendMessage(Component.text(
                    "ホスト端末: " + (tunnelHost == null ? "無効" : tunnelHost.describe()),
                    NamedTextColor.GRAY));
            source.sendMessage(Component.text(
                    "WSブリッジ: " + (wsServer == null ? "無効" : wsServer.describe()),
                    NamedTextColor.GRAY));
            List<String> names = peers.values().stream().map(PeerInfo::name).sorted().toList();
            source.sendMessage(Component.text(
                    "登録済み: " + (names.isEmpty() ? "なし" : String.join(", ", names)),
                    NamedTextColor.GRAY));
            if (!pendingRequests.isEmpty()) {
                source.sendMessage(Component.text(
                        "待機中の紹介要求: " + pendingRequests.size() + " 件", NamedTextColor.GRAY));
            }
            for (PeerInfo info : peers.values()) {
                source.sendMessage(Component.text(
                        "- " + info.name() + ": " + abbreviate(info.data(), 100),
                        NamedTextColor.DARK_GRAY));
            }
            source.sendMessage(Component.text("（ゲーム通信は経由しません。P2P の紹介だけです）",
                    NamedTextColor.GRAY));
        }

        @Override
        public boolean hasPermission(SimpleCommand.Invocation invocation) {
            return invocation.source().hasPermission("lessping.admin");
        }
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
