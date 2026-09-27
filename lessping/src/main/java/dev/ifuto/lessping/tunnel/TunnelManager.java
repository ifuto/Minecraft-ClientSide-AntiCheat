package dev.ifuto.lessping.tunnel;

import dev.ifuto.lessping.LessPing;
import dev.ifuto.lessping.LpConfig;
import dev.ifuto.lessping.signal.SignalClient;
import dev.ifuto.lessping.signal.SignalPayload;
import dev.ifuto.lessping.tunnel.lpx.LpxFrame;
import dev.ifuto.lessping.tunnel.lpx.LpSecret;
import dev.ifuto.lessping.tunnel.lpx.LpxStream;
import dev.ifuto.lessping.tunnel.lpx.Stun;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * トンネルの総括（シングルトン）。
 *
 * <pre>
 * ⓪ クライアント起動直後、サーバーリスト ping 経由でシグナリング（v0.2.0〜）。
 *    中継プラグインが Paper と同じ PC で持つ「ホスト端末」の候補アドレスを
 *    status 応答の version 名から受け取り、穴あけを始める。本サーバーに入る必要なし
 * ① （従来経路）サーバーに参加したら HELLO を中継プラグインへ送る
 * ② INTRO（相手のエンドポイント）が届いたら UDP ホールパンチを開始
 * ③ PONG が返ってきたらトンネル確立。keepalive で維持
 * ④ Minecraft が「narena」へ接続するとき、アドレス解決を 127.0.0.1:localTcpPort
 *    へ差し替える（mixin）。ローカル TCP ⇄ LPX ストリーム（UDP）をスプライスし、
 *    ホスト側では backend（localhost の Paper）へ中継する。
 *    まだトンネルが無ければ最大 10 秒待って、ダメなら signalServer（本サーバー）へ
 *    素通しして通常接続として続行（「narena」が繋がらなくなることは無い）
 * </pre>
 *
 * <p>トンネル自体は純粋な UDP なので、Minecraft の接続が切れても生き続ける
 * （いったん穴あけ済みなら、サーバーリストへの表示もトンネル経由で ping される）。
 */
public final class TunnelManager {

    private static final TunnelManager INSTANCE = new TunnelManager();

    public static TunnelManager get() {
        return INSTANCE;
    }

    /** PUNCH の最大試行回数（1 回 250ms なので 40 回 = 10 秒） */
    private static final int MAX_PUNCH_TRIES = 40;
    /** ストリームのアイドル切断（ミリ秒） */
    private static final long STREAM_IDLE_MS = 30_000;
    /** ホスト側で「知らないアドレスからの接続」を許可する期間（ミリ秒） */
    private static final long KNOWN_ADDR_TTL_MS = 60_000;
    /** STUN の結果を使い回す期間 */
    private static final long STUN_CACHE_MS = 120_000;

    private LpConfig config;
    private volatile boolean enabled;
    private volatile boolean started;
    private volatile boolean stopped;

    private DatagramSocket udp;
    private ServerSocket localTcp;
    private Thread receiveThread;
    private Thread acceptThread;
    private final ScheduledExecutorService ticker = new ScheduledThreadPoolExecutor(1);
    private final ExecutorService ioPool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "lessping-io");
        thread.setDaemon(true);
        return thread;
    });
    private final SecureRandom random = new SecureRandom();

    /** 仲介してもらった相手（プレイヤー名 → Peer） */
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    /** LPX ストリーム（conv → コネクション） */
    private final Map<Integer, Conn> conns = new ConcurrentHashMap<>();
    /** PUNCH トークン → 誰のどの候補アドレスへ送ったものか */
    private final Map<Long, PunchTarget> punchTokens = new ConcurrentHashMap<>();
    /** ホスト側: 最近 PUNCH/PONG/KEEP をくれたアドレス（ conv を開いてよい相手） */
    private final Map<SocketAddress, Long> knownAddrs = new ConcurrentHashMap<>();

    /** STUN の進行中の問い合わせ */
    private volatile StunPending stunPending;
    private volatile InetSocketAddress publicEndpoint;
    private volatile long publicEndpointAt;
    private volatile String selfName = "";
    /** シグナリング（サーバーリスト ping 経由）の実行中フラグと前回時刻 */
    private final AtomicBoolean signalBusy = new AtomicBoolean(false);
    private volatile long lastSignalPollAt;
    /** サーバーが P2P 候補を出していない（= P2P 無効の運用）。待たずにフォールバックしてよい */
    private volatile boolean p2pUnavailable;

    private record PunchTarget(String peer, InetSocketAddress candidate, long sentAt) {
    }

    private record StunPending(byte[] txid, CompletableFuture<InetSocketAddress> future) {
    }

    private static final class Peer {
        final String name;
        final List<InetSocketAddress> candidates = new CopyOnWriteArrayList<>();
        final Map<InetSocketAddress, Long> pendingTokens = new ConcurrentHashMap<>();
        volatile boolean hostFlag;
        volatile InetSocketAddress working;
        volatile long lastPongAt;
        volatile ScheduledFuture<?> punchTask;
        volatile int punchTries;

        Peer(String name) {
            this.name = name;
        }

        boolean ready() {
            return working != null;
        }
    }

    private record Conn(LpxStream stream, Socket tcp) {
    }

    private TunnelManager() {
    }

    // ------------------------------------------------------------------ 基本

    public void init(LpConfig cfg) {
        this.config = cfg;
        this.enabled = cfg != null && cfg.enabled;
        if (enabled) {
            LessPing.LOGGER.info("[LessPing] 有効 (magic={}, hostMode={}, hostPlayer={}, backend={})",
                    cfg.magicNames, cfg.hostMode, cfg.hostPlayer, cfg.backend);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * アドレス解決の差し替え判定（mixin から毎回呼ばれる。重い処理は禁止）。
     *
     * @return 差し替え先（トンネルが確立していなければ empty → 通常の解決に戻る）
     */
    public Optional<InetSocketAddress> redirect(String host) {
        if (!enabled || !started || host == null || stopped) {
            return Optional.empty();
        }
        for (String magic : config.magicNames) {
            if (magic.equalsIgnoreCase(host)) {
                if (localTcp == null || localTcp.isClosed()) {
                    return Optional.empty();
                }
                Peer host1 = peers.get(config.hostPlayer == null ? "" : config.hostPlayer.toLowerCase());
                if (host1 != null && host1.ready()) {
                    return Optional.of(new InetSocketAddress("127.0.0.1", localTcp.getLocalPort()));
                }
                // まだ確立していなくても、シグナリング先があるなら楽観的にローカル受け口へ。
                // 接続時に最大 10 秒待って、それでもダメなら通常経路へフォールバックする
                if (config.signalServer != null && !config.signalServer.isBlank()) {
                    return Optional.of(new InetSocketAddress("127.0.0.1", localTcp.getLocalPort()));
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ 参加

    /**
     * クライアント起動直後に呼ぶ。サーバーに入っていなくても、サーバーリスト ping
     * 経由のシグナリング（{@link SignalClient}）で先にトンネルを張り始める。
     */
    public void clientStarted() {
        if (!enabled || stopped) {
            return;
        }
        try {
            ensureStarted();
        } catch (IOException e) {
            LessPing.LOGGER.error("[LessPing] UDP ソケットを開けません: {}", e.toString());
            return;
        }
        pollSignal(true);
    }

    /**
     * サーバーリスト ping 経由でシグナリングする（ ioPool のスレッドで実行）。
     * 中継プラグインが持つホスト端末の候補アドレスを取得して穴あけを始める。
     */
    private void pollSignal(boolean urgent) {
        if (!started || stopped || config == null || config.hostMode) {
            return;
        }
        String server = config.signalServer;
        if (server == null || server.isBlank() || config.hostPlayer == null
                || config.hostPlayer.isBlank()) {
            return;
        }
        if (!signalBusy.compareAndSet(false, true)) {
            return; // すでに実行中
        }
        ioPool.execute(() -> {
            try {
                SignalClient.SignalResult result =
                        SignalClient.fetchCandidates(server, config.effectiveSecret(), config.wsUrl);
                if (result.reached() && result.candidates().isEmpty()) {
                    // サーバーは応答したが P2P 候補を出していない（host-endpoint.enabled=false
                    // の運用）。P2P を待たずに WebSocket 経由へ進んでよい
                    if (!p2pUnavailable) {
                        LessPing.LOGGER.info("[LessPing] サーバーは P2P 直結を受け付けていません"
                                + "（WebSocket 経由で接続します）");
                    }
                    p2pUnavailable = true;
                    return;
                }
                p2pUnavailable = false;
                if (result.candidates().isEmpty()) {
                    if (urgent) {
                        LessPing.LOGGER.info("[LessPing] {} からホストのエンドポイントを取得できませんでした"
                                + "（プラグイン未導入 or 応答なし）", server);
                    }
                    return;
                }
                LessPing.LOGGER.info("[LessPing] サーバーからホスト ({}) のエンドポイントを受信: {}"
                        + "（穴あけ開始）", config.hostPlayer, result.candidates());
                punchPeer(config.hostPlayer, result.candidates(), true);
            } finally {
                signalBusy.set(false);
            }
        });
    }

    /** Minecraft のサーバー参加時に呼ぶ（HELLO の送信とトンネル開始） */
    public void onServerJoin(String playerName) {
        if (!enabled) {
            return;
        }
        selfName = playerName == null ? "" : playerName;
        try {
            ensureStarted();
        } catch (IOException e) {
            LessPing.LOGGER.error("[LessPing] UDP ソケットを開けません: {}", e.toString());
            return;
        }
        // STUN が終わってから HELLO（外向きアドレスを載せるため）。遅くても 4 秒後には送る。
        resolvePublicEndpoint().whenComplete((endpoint, error) -> sendHello());
        ticker.schedule(this::sendHello, 4, TimeUnit.SECONDS);
    }

    /** 中継プラグインから INTRO が届いた */
    public void onIntro(String playerName, String data) {
        if (!enabled || playerName == null || data == null || data.isEmpty()) {
            if (data != null && data.isEmpty()) {
                LessPing.LOGGER.info("[LessPing] {} はまだオンラインではありません（トンネルなし）", playerName);
            }
            return;
        }
        List<InetSocketAddress> candidates = parseEndpointData(data);
        boolean hostFlag = data.contains("host=1");
        if (candidates.isEmpty()) {
            return;
        }
        // 自分向けの INTRO かどうか: ホストなら全員と、プレイヤーなら hostPlayer とのトンネルを張る
        boolean want = config.hostMode
                || (config.hostPlayer != null && config.hostPlayer.equalsIgnoreCase(playerName));
        if (!want) {
            return;
        }
        LessPing.LOGGER.info("[LessPing] {} のエンドポイントを受信: {}（穴あけ開始）",
                playerName, candidates);
        punchPeer(playerName, candidates, hostFlag);
    }

    /** 自分のエンドポイント情報の文字列化（HELLO に載せる） */
    private String selfData() {
        StringBuilder sb = new StringBuilder("pub=");
        if (publicEndpoint != null) {
            sb.append(publicEndpoint.getHostString()).append(':').append(publicEndpoint.getPort());
        }
        sb.append("|priv=");
        boolean first = true;
        for (InetAddress address : privateAddresses()) {
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(address.getHostAddress()).append(':').append(udp.getLocalPort());
        }
        sb.append("|host=").append(config.hostMode ? 1 : 0);
        sb.append("|v=").append(LessPing.VERSION);
        return sb.toString();
    }

    private void sendHello() {
        if (!enabled || !started || selfName.isEmpty()) {
            return;
        }
        try {
            ClientPlayNetworking.send(new SignalPayload(SignalPayload.TYPE_HELLO, selfName, selfData()));
            LessPing.LOGGER.info("[LessPing] HELLO を送信しました ({})", selfName);
            if (!config.hostMode && config.hostPlayer != null && !config.hostPlayer.isBlank()) {
                ClientPlayNetworking.send(new SignalPayload(SignalPayload.TYPE_INTRO_REQUEST,
                        config.hostPlayer, ""));
            }
        } catch (Throwable t) {
            // サーバーへ接続していない/チャンネルがまだ開いていない
            LessPing.LOGGER.debug("[LessPing] HELLO 送信をスキップ: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------ 開始

    private synchronized void ensureStarted() throws IOException {
        if (started) {
            return;
        }
        udp = new DatagramSocket(new InetSocketAddress(config.udpPort));
        udp.setReceiveBufferSize(1 << 20);
        LessPing.LOGGER.info("[LessPing] UDP 待受を開始: ポート {} (hostMode={})",
                udp.getLocalPort(), config.hostMode);

        started = true;
        receiveThread = new Thread(this::receiveLoop, "lessping-udp");
        receiveThread.setDaemon(true);
        receiveThread.start();

        ticker.scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.MILLISECONDS);

        if (!config.hostMode) {
            startLocalTcp();
        }
    }

    /** プレイヤー側: Minecraft が接続してくるローカル TCP の受け口 */
    private void startLocalTcp() {
        int port = config.localTcpPort;
        for (int i = 0; i < 20; i++) {
            try {
                localTcp = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
                break;
            } catch (IOException e) {
                port++;
            }
        }
        if (localTcp == null) {
            LessPing.LOGGER.error("[LessPing] ローカル TCP ポートが開けません (25599〜)");
            return;
        }
        LessPing.LOGGER.info("[LessPing] ローカル受け口: 127.0.0.1:{} （「{}」への接続はここへ差し替えられます）",
                localTcp.getLocalPort(), String.join("/", config.magicNames));
        acceptThread = new Thread(this::acceptLoop, "lessping-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket socket = localTcp.accept();
                ioPool.execute(() -> handleClient(socket));
            } catch (IOException e) {
                if (!stopped) {
                    LessPing.LOGGER.debug("[LessPing] accept エラー: {}", e.toString());
                }
            }
        }
    }

    /**
     * ローカル受け口に来た接続（= Minecraft が「narena」へ繋ごうとした）を処理する。
     *
     * <p>トンネルがまだ無い場合は、その場でシグナリングして最大 10 秒待つ。
     * それでも張れなければ {@code signalServer}（本サーバー）へ素通しして
     * 通常接続として続行する（「narena」が繋がらなくなることは無い）。
     */
    private void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
        } catch (Exception ignored) {
            // SO_NODELAY はベストエフォート
        }
        Peer host = hostPeer();
        boolean wsReady = config.wsUrl != null && !config.wsUrl.isBlank();
        if ((host == null || !host.ready()) && !p2pUnavailable) {
            LessPing.LOGGER.info("[LessPing] トンネル準備中… シグナリングして最大 {} 秒待ちます",
                    wsReady ? 5 : 10);
            pollSignal(true);
            long deadline = System.currentTimeMillis() + (wsReady ? 5_000L : 10_000L);
            while ((host = hostPeer()) == null || !host.ready()) {
                // p2pUnavailable が立ったら（サーバーが P2P 無効の運用）即フォールバック
                if (stopped || p2pUnavailable || System.currentTimeMillis() >= deadline) {
                    host = null;
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (stopped) {
            closeQuietly(socket);
            return;
        }
        if (host != null && host.ready()) {
            int conv = random.nextInt();
            LpxStream stream = new LpxStream(conv, host.working, this::sendFrame, config.keepaliveMs);
            conns.put(conv, new Conn(stream, socket));
            LessPing.LOGGER.info("[LessPing] 接続を開始 conv={} → {}", conv, host.working);
            splice(stream, socket);
            return;
        }
        // フォールバック 1: WebSocket 経由（Cloudflare Tunnel 等。IP を公開しない経路）
        if (wsReady) {
            try {
                WsLink.WsConnection ws = WsLink.WsConnection.connect(config.wsUrl, 7000);
                LessPing.LOGGER.info("[LessPing] トンネル未確立のため WebSocket 経由 ({}) で接続します",
                        config.wsUrl);
                spliceWs(socket, ws);
                return;
            } catch (Exception e) {
                LessPing.LOGGER.debug("[LessPing] WebSocket 接続に失敗: {}", e.toString());
            }
        }
        // フォールバック 2: signalServer（本サーバー）へ素通し
        if (config.signalServer != null && !config.signalServer.isBlank()) {
            LessPing.LOGGER.warn("[LessPing] トンネルを張れませんでした。通常経路 ({}) へ接続します",
                    config.signalServer);
            fallbackSplice(socket, config.signalServer);
            return;
        }
        LessPing.LOGGER.warn("[LessPing] トンネルが確立していないため接続を拒否しました");
        closeQuietly(socket);
    }

    /** クライアントの TCP ⇄ WebSocket の双方向ポンプ（CF 経由フォールバック用） */
    private void spliceWs(Socket client, WsLink.WsConnection ws) {
        ioPool.execute(() -> {
            byte[] buf = new byte[16384];
            try (Socket ignored = client) {
                InputStream in = client.getInputStream();
                OutputStream out = ws.out();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
                // 切断
            } finally {
                ws.close();
            }
        });
        ioPool.execute(() -> {
            byte[] buf = new byte[16384];
            try (Socket ignored = client) {
                InputStream in = ws.in();
                OutputStream out = client.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
                // 切断
            } finally {
                ws.close();
            }
        });
    }

    private Peer hostPeer() {
        return peers.get(config.hostPlayer == null ? "" : config.hostPlayer.toLowerCase());
    }

    /**
     * 通常経路へのフォールバック: クライアント ⇄ 本サーバーを素通しする。
     * 最初のハンドシェイクの hostname（"narena"）は本サーバーのアドレスに
     * 書き換えてから流す（リバースプロキシが hostname でルーティングする場合のため）。
     */
    private void fallbackSplice(Socket client, String server) {
        String[] hp = LpConfig.parseAddress(server, 25565);
        Socket upstream = null;
        try {
            upstream = new Socket();
            upstream.setTcpNoDelay(true);
            upstream.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 5000);
            rewriteHandshakeHost(client, upstream, hp[0], Integer.parseInt(hp[1]));
            spliceTcp(client, upstream);
        } catch (Exception e) {
            LessPing.LOGGER.warn("[LessPing] 通常経路へのフォールバックに失敗: {}", e.toString());
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    /** クライアントが送った最初のパケット（Handshake）の hostname を書き換える */
    private void rewriteHandshakeHost(Socket client, Socket upstream, String newHost, int newPort)
            throws Exception {
        InputStream in = client.getInputStream();
        OutputStream out = upstream.getOutputStream();
        int len = readVarint(in);
        byte[] packet = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(packet, read, len - read);
            if (n < 0) {
                throw new IllegalStateException("ハンドシェイクの途中で切れました");
            }
            read += n;
        }
        // Handshake: varint id, varint protocol, string host, ushort port, varint state
        int[] pos = {0};
        int id = readVarint(packet, pos);
        if (id != 0x00) {
            // ハンドシェイクでなければそのまま流す
            writeVarint(out, len);
            out.write(packet);
            return;
        }
        readVarint(packet, pos); // protocol
        int hostLen = readVarint(packet, pos);
        pos[0] += hostLen; // 元の hostname を読み飛ばす
        pos[0] += 2;       // port
        int state = readVarint(packet, pos);
        int restLen = len - pos[0];

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        writeVarint(buf, id);
        writeVarint(buf, SharedConstants.getProtocolVersion());
        byte[] hostBytes = newHost.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(buf, hostBytes.length);
        buf.write(hostBytes);
        buf.write((newPort >>> 8) & 0xFF);
        buf.write(newPort & 0xFF);
        writeVarint(buf, state);
        buf.write(packet, len - restLen, restLen);
        byte[] rewritten = buf.toByteArray();
        writeVarint(out, rewritten.length);
        out.write(rewritten);
        out.flush();
    }

    /** TCP ⇄ TCP の双方向ポンプ（フォールバック用） */
    private void spliceTcp(Socket a, Socket b) {
        ioPool.execute(() -> pump(a, b));
        ioPool.execute(() -> pump(b, a));
    }

    private void pump(Socket from, Socket to) {
        byte[] buf = new byte[16384];
        try (Socket ignored = from) {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
            // 切断
        } finally {
            closeQuietly(to);
        }
    }

    private static int readVarint(InputStream in) throws Exception {
        int value = 0;
        int position = 0;
        while (position < 5) {
            int b = in.read();
            if (b < 0) {
                throw new IllegalStateException("接続が切れました");
            }
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IllegalStateException("varint が長すぎます");
    }

    private static int readVarint(byte[] buf, int[] pos) {
        int value = 0;
        int position = 0;
        while (position < 5 && pos[0] < buf.length) {
            int b = buf[pos[0]++];
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        return value;
    }

    private static void writeVarint(OutputStream out, int value) throws Exception {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    // ------------------------------------------------------------------ 受信

    private void receiveLoop() {
        byte[] buf = new byte[1500];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (!stopped) {
            try {
                udp.receive(packet);
                int len = packet.getLength();
                SocketAddress from = packet.getSocketAddress();

                // STUN 応答（先頭バイト 0x00/0x01。LPX は 0xC7 なので区別できる）
                if (len >= 20 && (buf[0] & 0xFF) == 0x01) {
                    StunPending pending = stunPending;
                    if (pending != null) {
                        InetSocketAddress mapped = Stun.parseResponse(buf, len, pending.txid);
                        if (mapped != null) {
                            pending.future.complete(mapped);
                        }
                    }
                    continue;
                }

                LpxFrame.Decoded frame = LpxFrame.decode(buf, len, from);
                if (frame == null) {
                    continue;
                }
                switch (frame.type()) {
                    case LpxFrame.T_PUNCH -> {
                        // 正しいトークン（= 鍵を共有する相手）の PUNCH だけ応答する
                        if (frame.token() == LpSecret.punchToken(config.effectiveSecret())) {
                            knownAddrs.put(from, System.currentTimeMillis());
                            send(LpxFrame.token(LpxFrame.T_PONG, frame.conv(), frame.token()), from);
                        }
                    }
                    case LpxFrame.T_PONG -> {
                        knownAddrs.put(from, System.currentTimeMillis());
                        onPong(frame, from);
                    }
                    case LpxFrame.T_KEEP -> {
                        knownAddrs.put(from, System.currentTimeMillis());
                        touchPeer(from);
                    }
                    case LpxFrame.T_DATA, LpxFrame.T_ACK, LpxFrame.T_CLOSE -> {
                        touchPeer(from);
                        route(frame);
                    }
                    default -> {
                    }
                }
            } catch (Throwable t) {
                if (!stopped) {
                    LessPing.LOGGER.debug("[LessPing] 受信エラー: {}", t.toString());
                }
            }
        }
    }

    private void onPong(LpxFrame.Decoded frame, SocketAddress from) {
        PunchTarget target = punchTokens.remove(frame.token());
        if (target == null) {
            return;
        }
        Peer peer = peers.get(target.peer().toLowerCase());
        if (peer == null) {
            return;
        }
        long rtt = System.currentTimeMillis() - target.sentAt();
        peer.working = (InetSocketAddress) from;
        peer.lastPongAt = System.currentTimeMillis();
        peer.pendingTokens.clear();
        if (peer.punchTask != null) {
            peer.punchTask.cancel(false);
            peer.punchTask = null;
        }
        if (peer.hostFlag) {
            LessPing.LOGGER.info("[LessPing] ★ ホストへのトンネル確立: {} まで {}ms 「{}」に接続できます",
                    from, rtt, String.join("/", config.magicNames));
        } else {
            LessPing.LOGGER.info("[LessPing] ★ トンネル確立: {} がこっちへ接続できます ({}ms)", from, rtt);
        }
    }

    private void touchPeer(SocketAddress from) {
        for (Peer peer : peers.values()) {
            if (from.equals(peer.working)) {
                peer.lastPongAt = System.currentTimeMillis();
            }
        }
    }

    /** conv でストリームを探す。ホスト側なら知らない conv を新規接続として受け入れる */
    private void route(LpxFrame.Decoded frame) {
        int conv = frame.conv();
        Conn conn = conns.get(conv);
        if (conn == null) {
            if (!config.hostMode) {
                return;
            }
            Long lastSeen = knownAddrs.get(frame.from());
            if (lastSeen == null || System.currentTimeMillis() - lastSeen > KNOWN_ADDR_TTL_MS) {
                return; // 穴あけしていない相手からのデータは無視
            }
            conn = openHostConn(conv, (InetSocketAddress) frame.from());
            if (conn == null) {
                return;
            }
        }
        conn.stream().onFrame(frame);
    }

    /** ホスト側: 新しい conv が来たら backend（localhost の Paper 等）へ中継する */
    private Conn openHostConn(int conv, InetSocketAddress remote) {
        String[] backend = LpConfig.parseAddress(config.backend, 25565);
        try {
            Socket tcp = new Socket();
            tcp.connect(new InetSocketAddress(backend[0], Integer.parseInt(backend[1])), 5000);
            LpxStream stream = new LpxStream(conv, remote, this::sendFrame, config.keepaliveMs);
            Conn conn = new Conn(stream, tcp);
            conns.put(conv, conn);
            LessPing.LOGGER.info("[LessPing] 新しい接続を中継開始 conv={} {} → {}:{}", conv, remote,
                    backend[0], backend[1]);
            splice(stream, tcp);
            return conn;
        } catch (Exception e) {
            LessPing.LOGGER.warn("[LessPing] backend へ接続できません ({}): {}", config.backend, e.toString());
            send(LpxFrame.simple(LpxFrame.T_CLOSE, conv), remote);
            return null;
        }
    }

    // ------------------------------------------------------------------ スプライス

    /** TCP ⇄ LPX ストリームの双方向ポンプ */
    private void splice(LpxStream stream, Socket tcp) {
        ioPool.execute(() -> {
            byte[] buf = new byte[16384];
            try (Socket ignored = tcp) {
                InputStream in = tcp.getInputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) {
                        stream.write(buf, 0, n);
                    }
                }
            } catch (Exception e) {
                // TCP 切断
            } finally {
                stream.close();
            }
        });
        ioPool.execute(() -> {
            try (Socket ignored = tcp) {
                OutputStream out = tcp.getOutputStream();
                while (true) {
                    byte[] chunk = stream.outbox().poll(1, TimeUnit.SECONDS);
                    if (chunk == null) {
                        if (stream.isRemoteClosed()) {
                            return;
                        }
                        continue;
                    }
                    if (chunk.length == 0) {
                        return; // EOF
                    }
                    out.write(chunk);
                    out.flush();
                }
            } catch (Exception e) {
                // 切断
            } finally {
                stream.close();
                conns.remove(stream.conv());
            }
        });
    }

    // ------------------------------------------------------------------ 穴あけ

    private void punchPeer(String name, List<InetSocketAddress> candidates, boolean hostFlag) {
        Peer peer = peers.computeIfAbsent(name.toLowerCase(), Peer::new);
        peer.candidates.clear();
        peer.candidates.addAll(candidates);
        peer.hostFlag = hostFlag;
        peer.punchTries = 0;
        if (peer.ready()) {
            // 既に確立済み。相手のアドレスが変わっていれば張り直す
            if (candidates.contains(peer.working)) {
                return;
            }
            peer.working = null;
        }
        startPunch(peer);
    }

    private synchronized void startPunch(Peer peer) {
        if (peer.punchTask != null && !peer.punchTask.isDone()) {
            return;
        }
        peer.punchTries = 0;
        peer.punchTask = ticker.scheduleWithFixedDelay(() -> {
            try {
                punchOnce(peer);
            } catch (Throwable t) {
                LessPing.LOGGER.debug("[LessPing] punch エラー: {}", t.toString());
            }
        }, 0, Math.max(50, config.punchIntervalMs), TimeUnit.MILLISECONDS);
    }

    private void punchOnce(Peer peer) {
        if (peer.ready()) {
            if (peer.punchTask != null) {
                peer.punchTask.cancel(false);
                peer.punchTask = null;
            }
            return;
        }
        if (peer.punchTries >= MAX_PUNCH_TRIES) {
            LessPing.LOGGER.warn("[LessPing] {} への穴あけに失敗しました（対称 NAT の可能性。"
                    + "通常接続にフォールバック）", peer.name);
            if (peer.punchTask != null) {
                peer.punchTask.cancel(false);
                peer.punchTask = null;
            }
            return;
        }
        peer.punchTries++;
        long token = LpSecret.punchToken(config.effectiveSecret());
        for (InetSocketAddress candidate : peer.candidates) {
            punchTokens.put(token, new PunchTarget(peer.name, candidate, System.currentTimeMillis()));
            peer.pendingTokens.put(candidate, token);
            send(LpxFrame.token(LpxFrame.T_PUNCH, 0, token), candidate);
        }
    }

    // ------------------------------------------------------------------ 定期

    private void tick() {
        if (!started) {
            return;
        }
        long now = System.currentTimeMillis();
        // ストリームのドライブと切断
        for (Conn conn : conns.values()) {
            conn.stream().tick(now);
            if (now - conn.stream().lastActivity() > STREAM_IDLE_MS
                    || conn.stream().finished()) {
                conn.stream().hardClose();
                closeQuietly(conn.tcp());
                conns.remove(conn.stream().conv());
            }
        }
        // keepalive と切断検知
        for (Peer peer : peers.values()) {
            if (peer.ready()) {
                if (now - peer.lastPongAt > config.idleTimeoutMs) {
                    LessPing.LOGGER.warn("[LessPing] {} からの音信が途絶えたのでトンネルを切りました", peer.name);
                    peer.working = null;
                } else if (now - peer.lastPongAt > config.keepaliveMs) {
                    // 相手から何も来ていない → keepalive を送って NAT マップを維持する
                    send(LpxFrame.simple(LpxFrame.T_KEEP, 0), peer.working);
                }
            } else if (!peer.candidates.isEmpty()) {
                startPunch(peer);
            }
        }
        // 古い knownAddrs の掃除
        knownAddrs.entrySet().removeIf(e -> now - e.getValue() > KNOWN_ADDR_TTL_MS * 2);
        // サーバーリスト ping 経由のシグナリング（ホストへのトンネルが無い間だけ定期実行）
        if (!config.hostMode && config.signalServer != null && !config.signalServer.isBlank()
                && config.hostPlayer != null && !config.hostPlayer.isBlank()) {
            Peer host = peers.get(config.hostPlayer.toLowerCase());
            // P2P 無効の運用と分かっている場合はポーリングを間引く（再び有効化された
            // ときに追いつけるよう、完全には止めない）
            long interval = p2pUnavailable
                    ? Math.max(60_000L, config.signalPollMs * 4L)
                    : Math.max(3000, config.signalPollMs);
            if ((host == null || !host.ready()) && now - lastSignalPollAt > interval) {
                lastSignalPollAt = now;
                pollSignal(false);
            }
        }
    }

    // ------------------------------------------------------------------ STUN

    /** 自分の公開エンドポイントを調べる（キャッシュあり）。必ず非同期で完了する。 */
    private CompletableFuture<InetSocketAddress> resolvePublicEndpoint() {
        CompletableFuture<InetSocketAddress> done = new CompletableFuture<>();
        if (publicEndpoint != null && System.currentTimeMillis() - publicEndpointAt < STUN_CACHE_MS) {
            done.complete(publicEndpoint);
            return done;
        }
        ioPool.execute(() -> {
            List<String> servers = config.stunServers == null ? List.of() : config.stunServers;
            for (String server : servers) {
                InetSocketAddress result = queryStun(server);
                if (result != null) {
                    publicEndpoint = result;
                    publicEndpointAt = System.currentTimeMillis();
                    LessPing.LOGGER.info("[LessPing] 自分の公開アドレス: {}（STUN {} 経由）", result, server);
                    done.complete(result);
                    return;
                }
            }
            LessPing.LOGGER.warn("[LessPing] STUN に全部失敗（同じ LAN 内なら通信可能。"
                    + "インターネット越えはできません）");
            done.complete(null);
        });
        return done;
    }

    private InetSocketAddress queryStun(String server) {
        try {
            String[] parts = LpConfig.parseAddress(server, 3478);
            InetSocketAddress target = new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
            byte[] txid = Stun.newTransactionId();
            CompletableFuture<InetSocketAddress> future = new CompletableFuture<>();
            stunPending = new StunPending(txid, future);
            send(Stun.bindingRequest(txid), target);
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** LAN 内アドレス（同じネットワークの相手への候補） */
    private List<InetAddress> privateAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isSiteLocalAddress()) {
                        out.add(address);
                    }
                }
            }
        } catch (Exception ignored) {
            // 取れなければ空
        }
        return out;
    }

    // ------------------------------------------------------------------ 下請け

    private void sendFrame(byte[] frame, SocketAddress to) {
        send(frame, to);
    }

    private void send(byte[] data, SocketAddress to) {
        try {
            udp.send(new DatagramPacket(data, data.length, to));
        } catch (IOException e) {
            LessPing.LOGGER.debug("[LessPing] 送信失敗 {} へ: {}", to, e.toString());
        }
    }

    /** INTRO の data 文字列を候補アドレスへ分解 */
    private static List<InetSocketAddress> parseEndpointData(String data) {
        List<InetSocketAddress> out = new ArrayList<>();
        for (String part : data.split("\\|")) {
            String value = part.startsWith("pub=") || part.startsWith("priv=")
                    ? part.substring(part.indexOf('=') + 1) : null;
            if (value == null) {
                continue;
            }
            for (String entry : value.split(";")) {
                String[] hp = LpConfig.parseAddress(entry, 0);
                if (hp[1].equals("0")) {
                    continue;
                }
                try {
                    out.add(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
                } catch (Exception ignored) {
                    // 不正なエントリは無視
                }
            }
        }
        return out;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
            // 既に閉じている
        }
    }

    /** ゲーム終了時に全部止める */
    public void shutdown() {
        stopped = true;
        for (Conn conn : conns.values()) {
            conn.stream().hardClose();
            closeQuietly(conn.tcp());
        }
        conns.clear();
        try {
            if (udp != null) {
                udp.close();
            }
            if (localTcp != null) {
                localTcp.close();
            }
        } catch (Exception ignored) {
            // 終了処理
        }
        ticker.shutdownNow();
        ioPool.shutdownNow();
    }
}
