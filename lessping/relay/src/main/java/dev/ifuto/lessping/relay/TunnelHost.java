package dev.ifuto.lessping.relay;

import dev.ifuto.lessping.tunnel.lpx.LpxFrame;
import dev.ifuto.lessping.tunnel.lpx.LpSecret;
import dev.ifuto.lessping.tunnel.lpx.LpxStream;
import dev.ifuto.lessping.tunnel.lpx.Stun;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ホスト側トンネル端末（このプラグイン自身が持つ）。
 *
 * <p>サーバー主のクライアントがゲームを起動していなくても参加者が
 * 「narena」で直結できるように、Velocity と同じ PC 上で UDP 端末を務める:
 *
 * <ol>
 *   <li>STUN で自分の公開 UDP アドレスを調べ、定期的に更新（NAT マップの維持）</li>
 *   <li>候補アドレスをサーバーリスト ping 応答の version 名に載せて公開
 *       （{@code LP1/LP2}。鍵を設定すると AES-GCM 暗号になる。LessPing MOD だけが読む）</li>
 *   <li>参加者からの PUNCH に PONG で応答（ホールパンチの成立）</li>
 *   <li>DATA が来たら LPX ストリームを張り、localhost の Velocity（backend）へ中継</li>
 * </ol>
 *
 * <p>セキュリティ: {@code host-endpoint.secret} を設定すると、正しいトークンの
 * PUNCH だけを受け付け、ping に載せる情報もマスクされる。
 *
 * <p>このクラスは Minecraft / Velocity の API に依存しない純粋 Java（MOD 側の
 * TunnelManager と同じ構造）。設定は {@link Settings} で渡す。
 */
public final class TunnelHost {

    /** ストリームのアイドル切断（ミリ秒）。Minecraft の keepalive(約15秒)より十分長く */
    private static final long STREAM_IDLE_MS = 60_000;
    /** PUNCH 済みアドレスからの新規接続を許可する期間 */
    private static final long KNOWN_ADDR_TTL_MS = 60_000;

    /** ホスト端末の設定（プラグイン側で config.properties から組み立てる） */
    public record Settings(int udpPort, String backendHost, int backendPort, String secret,
                           List<String> stunServers, long stunIntervalMs, long keepaliveMs,
                           int maxConns, boolean publish, String version) {
    }

    private final Logger logger;
    private final Settings settings;

    private DatagramSocket udp;
    private Thread receiveThread;
    private final ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "lessping-host-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "lessping-host-io");
        thread.setDaemon(true);
        return thread;
    });

    /** PUNCH 済みアドレス（conv を開いてよい相手） */
    private final Map<SocketAddress, Long> knownAddrs = new ConcurrentHashMap<>();
    /** LPX ストリーム（conv → コネクション） */
    private final Map<Integer, Conn> conns = new ConcurrentHashMap<>();
    /** STUN の進行中の問い合わせ */
    private volatile StunPending stunPending;
    private volatile InetSocketAddress publicEndpoint;
    private volatile boolean stopped;

    private record Conn(LpxStream stream, Socket tcp) {
    }

    private record StunPending(byte[] txid, CompletableFuture<InetSocketAddress> future) {
    }

    public TunnelHost(Logger logger, Settings settings) {
        this.logger = logger;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ ライフサイクル

    public void start() throws IOException {
        udp = new DatagramSocket(new InetSocketAddress(settings.udpPort()));
        udp.setReceiveBufferSize(1 << 20);
        stopped = false;
        receiveThread = new Thread(this::receiveLoop, "lessping-host-udp");
        receiveThread.setDaemon(true);
        receiveThread.start();
        timer.scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::stunRefresh, 0, settings.stunIntervalMs(), TimeUnit.MILLISECONDS);
        logger.info("ホスト端末を開始: UDP {} → backend {}:{}（ゲーム起動なしで「narena」から直結できます）",
                udp.getLocalPort(), settings.backendHost(), settings.backendPort());
    }

    public void stop() {
        stopped = true;
        try {
            if (udp != null) {
                udp.close();
            }
        } catch (Exception ignored) {
            // 既に閉じている
        }
        for (Conn conn : conns.values()) {
            conn.stream().hardClose();
            closeQuietly(conn.tcp());
        }
        conns.clear();
        timer.shutdownNow();
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ 公開情報

    /** 公開する候補（STUN の公開アドレス + LAN アドレス）。1 つも無ければ空 */
    public List<InetSocketAddress> candidates() {
        List<InetSocketAddress> out = new ArrayList<>();
        if (publicEndpoint != null) {
            out.add(publicEndpoint);
        }
        out.addAll(lanCandidates());
        return out;
    }

    /** サーバーリスト ping の version 名に載せる blob（鍵なし=LP1 平文 / 鍵あり=LP2 暗号） */
    public String blob() {
        InetSocketAddress pub = publicEndpoint;
        StringBuilder sb = new StringBuilder("pub=");
        if (pub != null) {
            sb.append(pub.getHostString()).append(':').append(pub.getPort());
        }
        sb.append("|priv=");
        boolean first = true;
        for (InetSocketAddress lan : lanCandidates()) {
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(lan.getHostString()).append(':').append(lan.getPort());
        }
        sb.append("|host=1|src=plugin|v=").append(settings.version());
        return LpSecret.encode(sb.toString(), settings.secret());
    }

    public boolean shouldPublish() {
        return settings.publish() && !candidates().isEmpty();
    }

    public boolean isStarted() {
        return !stopped && udp != null && !udp.isClosed();
    }

    /** /lp 表示用の状態 */
    public String describe() {
        if (stopped || udp == null) {
            return "停止中";
        }
        return "UDP " + udp.getLocalPort() + ", 公開=" + publicEndpoint
                + ", LAN候補=" + lanCandidates().size() + "件"
                + ", 接続中=" + conns.size() + "/" + settings.maxConns()
                + ", 認証=" + (settings.secret() == null || settings.secret().isEmpty() ? "なし(公開)" : "あり");
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

                // STUN 応答（先頭バイト 0x01。LPX は 0xC7 なので区別できる）
                if (len >= 20 && (buf[0] & 0xFF) == 0x01) {
                    StunPending pending = stunPending;
                    if (pending != null) {
                        InetSocketAddress mapped = Stun.parseResponse(buf, len, pending.txid());
                        if (mapped != null) {
                            pending.future().complete(mapped);
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
                        long expected = LpSecret.punchToken(settings.secret());
                        if (frame.token() == expected) {
                            knownAddrs.put(from, System.currentTimeMillis());
                            send(LpxFrame.token(LpxFrame.T_PONG, frame.conv(), frame.token()), from);
                        } else if (settings.secret() != null && !settings.secret().isEmpty()) {
                            logger.warn("トークン不一致の PUNCH を無視: {}", from);
                        }
                    }
                    case LpxFrame.T_KEEP -> {
                        // 知っている相手の keepalive だけ TTL 更新（新規登録はしない）。
                        // 応答も返す: こちら側の NAT マップも相手への送信で維持される
                        // （確立済みだけど未接続のアイドル時間にマップが切れないように）
                        if (knownAddrs.containsKey(from)) {
                            knownAddrs.put(from, System.currentTimeMillis());
                            send(LpxFrame.simple(LpxFrame.T_KEEP, 0), from);
                        }
                    }
                    case LpxFrame.T_DATA, LpxFrame.T_ACK, LpxFrame.T_CLOSE -> route(frame);
                    default -> {
                        // PONG などホスト側からは送らないので無視
                    }
                }
            } catch (Throwable t) {
                if (!stopped) {
                    logger.warn("受信エラー: {}", t.toString());
                }
            }
        }
    }

    /** conv でストリームを探す。知らない conv は PUNCH 済みアドレスからのみ新規受け入れ */
    private void route(LpxFrame.Decoded frame) {
        int conv = frame.conv();
        Conn conn = conns.get(conv);
        if (conn == null) {
            Long lastSeen = knownAddrs.get(frame.from());
            if (lastSeen == null || System.currentTimeMillis() - lastSeen > KNOWN_ADDR_TTL_MS) {
                return; // 穴あけしていない相手からのデータは無視
            }
            if (conns.size() >= settings.maxConns()) {
                logger.warn("接続数が上限 ({}) に達したので拒否: {}", settings.maxConns(), frame.from());
                send(LpxFrame.simple(LpxFrame.T_CLOSE, conv), frame.from());
                return;
            }
            conn = openConn(conv, (InetSocketAddress) frame.from());
            if (conn == null) {
                return;
            }
        }
        conn.stream().onFrame(frame);
    }

    /** 新しい conv が来たら backend（localhost の Velocity）へ中継する */
    private Conn openConn(int conv, InetSocketAddress remote) {
        try {
            Socket tcp = new Socket();
            tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(settings.backendHost(), settings.backendPort()), 5000);
            LpxStream stream = new LpxStream(conv, remote,
                    (frame, to) -> send(frame, to), settings.keepaliveMs());
            Conn conn = new Conn(stream, tcp);
            conns.put(conv, conn);
            logger.info("新しい接続を中継開始 conv={} {} → {}:{}",
                    conv, remote, settings.backendHost(), settings.backendPort());
            splice(stream, tcp);
            return conn;
        } catch (Exception e) {
            logger.warn("backend へ接続できません ({}:{}): {}",
                    settings.backendHost(), settings.backendPort(), e.toString());
            send(LpxFrame.simple(LpxFrame.T_CLOSE, conv), remote);
            return null;
        }
    }

    // ------------------------------------------------------------------ スプライス

    /** TCP ⇄ LPX ストリームの双方向ポンプ（MOD の TunnelManager と同じ構造） */
    private void splice(LpxStream stream, Socket tcp) {
        pool.execute(() -> {
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
        pool.execute(() -> {
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

    // ------------------------------------------------------------------ 定期処理

    private void tick() {
        if (stopped) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Conn conn : conns.values()) {
            conn.stream().tick(now);
            if (now - conn.stream().lastActivity() > STREAM_IDLE_MS
                    || conn.stream().finished()) {
                conn.stream().hardClose();
                closeQuietly(conn.tcp());
                conns.remove(conn.stream().conv());
            }
        }
        knownAddrs.entrySet().removeIf(e -> now - e.getValue() > KNOWN_ADDR_TTL_MS * 2);
    }

    /** STUN で公開アドレスを更新（NAT マップの維持も兼ねる） */
    private void stunRefresh() {
        if (stopped) {
            return;
        }
        for (String server : settings.stunServers()) {
            InetSocketAddress result = queryStun(server);
            if (result != null) {
                if (publicEndpoint == null || !result.equals(publicEndpoint)) {
                    logger.info("公開アドレス: {}（STUN {} 経由）", result, server);
                }
                publicEndpoint = result;
                return;
            }
        }
        logger.warn("STUN に全部失敗（インターネット越えの候補を出せません）");
    }

    private InetSocketAddress queryStun(String server) {
        try {
            String[] parts = server.split(":");
            InetSocketAddress target = new InetSocketAddress(parts[0],
                    parts.length > 1 ? Integer.parseInt(parts[1]) : 3478);
            byte[] txid = Stun.newTransactionId();
            CompletableFuture<InetSocketAddress> future = new CompletableFuture<>();
            stunPending = new StunPending(txid, future);
            send(Stun.bindingRequest(txid), target);
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** LAN 内アドレス（同じネットワークの参加者への候補） */
    private List<InetSocketAddress> lanCandidates() {
        List<InetSocketAddress> out = new ArrayList<>();
        if (udp == null) {
            return out;
        }
        int port = udp.getLocalPort();
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
                        out.add(new InetSocketAddress(address.getHostAddress(), port));
                    }
                }
            }
        } catch (Exception ignored) {
            // 取れなければ空
        }
        return out;
    }

    // ------------------------------------------------------------------ 下請け

    private void send(byte[] data, SocketAddress to) {
        try {
            udp.send(new DatagramPacket(data, data.length, to));
        } catch (IOException e) {
            // 相手が消えているだけなら頻発するので静かに
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
            // 既に閉じている
        }
    }
}
