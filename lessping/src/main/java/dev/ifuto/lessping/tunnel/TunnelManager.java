package dev.ifuto.lessping.tunnel;

import dev.ifuto.lessping.LessPing;
import dev.ifuto.lessping.LpConfig;
import dev.ifuto.lessping.signal.SignalPayload;
import dev.ifuto.lessping.tunnel.lpx.LpxFrame;
import dev.ifuto.lessping.tunnel.lpx.LpxStream;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

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

/**
 * トンネルの総括（シングルトン）。
 *
 * <pre>
 * ① サーバーに参加したら HELLO（自分のエンドポイント情報）を中継プラグインへ送る
 * ② INTRO（相手のエンドポイント）が届いたら UDP ホールパンチを開始
 * ③ PONG が返ってきたらトンネル確立。keepalive で維持
 * ④ Minecraft が「narena」へ接続するとき、アドレス解決を 127.0.0.1:localTcpPort
 *    へ差し替える（mixin）。ローカル TCP ⇄ LPX ストリーム（UDP）をスプライスし、
 *    ホスト側では backend（localhost の Paper）へ中継する
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
                Peer host1 = peers.get(config.hostPlayer == null ? "" : config.hostPlayer.toLowerCase());
                if (host1 != null && host1.ready() && localTcp != null && !localTcp.isClosed()) {
                    return Optional.of(new InetSocketAddress("127.0.0.1", localTcp.getLocalPort()));
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ 参加

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
                Peer host = peers.get(config.hostPlayer == null ? "" : config.hostPlayer.toLowerCase());
                if (host == null || !host.ready()) {
                    LessPing.LOGGER.warn("[LessPing] トンネルが確立していないため接続を拒否しました");
                    socket.close();
                    continue;
                }
                int conv = random.nextInt();
                LpxStream stream = new LpxStream(conv, host.working, this::sendFrame, config.keepaliveMs);
                conns.put(conv, new Conn(stream, socket));
                LessPing.LOGGER.info("[LessPing] 接続を開始 conv={} → {}", conv, host.working);
                splice(stream, socket);
            } catch (IOException e) {
                if (!stopped) {
                    LessPing.LOGGER.debug("[LessPing] accept エラー: {}", e.toString());
                }
            }
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
                        knownAddrs.put(from, System.currentTimeMillis());
                        send(LpxFrame.token(LpxFrame.T_PONG, frame.conv(), frame.token()), from);
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
        for (InetSocketAddress candidate : peer.candidates) {
            long token = random.nextLong();
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
