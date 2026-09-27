package dev.ifuto.lessping.relay;

import dev.ifuto.lessping.tunnel.lpx.LpxFrame;
import dev.ifuto.lessping.tunnel.lpx.LpSecret;
import dev.ifuto.lessping.tunnel.lpx.LpxStream;
import dev.ifuto.lessping.tunnel.lpx.Stun;
import org.bukkit.Bukkit;

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
 * 「narena」で直結できるように、Paper と同じ PC 上で UDP 端末を務める:
 *
 * <ol>
 *   <li>STUN で自分の公開 UDP アドレスを調べ、定期的に更新（NAT マップの維持）</li>
 *   <li>候補アドレスをサーバーリスト ping 応答の version 名に載せて公開
 *       （{@code LP1:...}。LessPing MOD だけが読む。ゲーム通信は経由しない）</li>
 *   <li>参加者からの PUNCH に PONG で応答（ホールパンチの成立）</li>
 *   <li>DATA が来たら LPX ストリームを張り、localhost の Paper（backend）へ中継</li>
 * </ol>
 *
 * <p>セキュリティ: {@code host-endpoint.secret} を設定すると、正しいトークンの
 * PUNCH だけを受け付け、ping に載せる情報もマスクされる。
 */
public final class TunnelHost {

    /** ストリームのアイドル切断（ミリ秒）。Minecraft の keepalive(約15秒)より十分長く */
    private static final long STREAM_IDLE_MS = 60_000;
    /** PUNCH 済みアドレスからの新規接続を許可する期間 */
    private static final long KNOWN_ADDR_TTL_MS = 60_000;

    private final LessPingRelayPlugin plugin;
    private final int udpPort;
    private final String backendHost;
    private final int backendPort;
    private final String secret;
    private final List<String> stunServers;
    private final long stunIntervalMs;
    private final long keepaliveMs;
    private final int maxConns;
    private final boolean publish;

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

    public TunnelHost(LessPingRelayPlugin plugin) {
        this.plugin = plugin;
        this.udpPort = plugin.getConfig().getInt("host-endpoint.udp-port", 0);
        String backend = plugin.getConfig().getString("host-endpoint.backend", "");
        if (backend == null || backend.isBlank()) {
            // 指定がなければサーバー自身（Paper の bind アドレスとポート）
            String ip = Bukkit.getIp();
            this.backendHost = (ip == null || ip.isBlank()) ? "127.0.0.1" : ip;
            this.backendPort = Bukkit.getPort();
        } else {
            String[] hp = backend.split(":");
            this.backendHost = hp[0].isBlank() ? "127.0.0.1" : hp[0];
            this.backendPort = Integer.parseInt(hp[hp.length - 1]);
        }
        this.secret = plugin.getConfig().getString("host-endpoint.secret", "");
        this.stunServers = plugin.getConfig().getStringList("host-endpoint.stun-servers");
        if (this.stunServers.isEmpty()) {
            this.stunServers.addAll(List.of("stun.cloudflare.com:3478", "stun.l.google.com:19302"));
        }
        this.stunIntervalMs = Math.max(10_000L, plugin.getConfig().getLong("host-endpoint.stun-interval-ms", 30_000L));
        this.keepaliveMs = 5000L;
        this.maxConns = Math.max(1, plugin.getConfig().getInt("host-endpoint.max-connections", 32));
        this.publish = plugin.getConfig().getBoolean("host-endpoint.publish", true);
    }

    // ------------------------------------------------------------------ ライフサイクル

    public void start() throws IOException {
        udp = new DatagramSocket(new InetSocketAddress(udpPort));
        udp.setReceiveBufferSize(1 << 20);
        stopped = false;
        receiveThread = new Thread(this::receiveLoop, "lessping-host-udp");
        receiveThread.setDaemon(true);
        receiveThread.start();
        timer.scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::stunRefresh, 0, stunIntervalMs, TimeUnit.MILLISECONDS);
        plugin.getLogger().info("ホスト端末を開始: UDP " + udp.getLocalPort()
                + " → backend " + backendHost + ":" + backendPort
                + "（ゲーム起動なしで「narena」から直結できます）");
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

    /** サーバーリスト ping の version 名に載せる blob（{@code LP1:...}） */
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
        sb.append("|host=1|src=plugin|v=").append(plugin.getDescription().getVersion());
        return LpSecret.encode(sb.toString(), secret);
    }

    public boolean shouldPublish() {
        return publish && !candidates().isEmpty();
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
                + ", 接続中=" + conns.size() + "/" + maxConns
                + ", 認証=" + (secret == null || secret.isEmpty() ? "なし(公開)" : "あり");
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
                        long expected = LpSecret.punchToken(secret);
                        if (frame.token() == expected) {
                            knownAddrs.put(from, System.currentTimeMillis());
                            send(LpxFrame.token(LpxFrame.T_PONG, frame.conv(), frame.token()), from);
                        } else if (plugin.getConfig().getBoolean("debug", false)) {
                            plugin.getLogger().warning("トークン不一致の PUNCH を無視: " + from);
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
                    plugin.getLogger().warning("受信エラー: " + t);
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
            if (conns.size() >= maxConns) {
                plugin.getLogger().warning("接続数が上限 (" + maxConns + ") に達したので拒否: " + frame.from());
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

    /** 新しい conv が来たら backend（localhost の Paper）へ中継する */
    private Conn openConn(int conv, InetSocketAddress remote) {
        try {
            Socket tcp = new Socket();
            tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(backendHost, backendPort), 5000);
            LpxStream stream = new LpxStream(conv, remote,
                    (frame, to) -> send(frame, to), keepaliveMs);
            Conn conn = new Conn(stream, tcp);
            conns.put(conv, conn);
            plugin.getLogger().info("新しい接続を中継開始 conv=" + conv + " " + remote
                    + " → " + backendHost + ":" + backendPort);
            splice(stream, tcp);
            return conn;
        } catch (Exception e) {
            plugin.getLogger().warning("backend へ接続できません (" + backendHost + ":" + backendPort
                    + "): " + e);
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
        for (String server : stunServers) {
            InetSocketAddress result = queryStun(server);
            if (result != null) {
                if (publicEndpoint == null || !result.equals(publicEndpoint)) {
                    plugin.getLogger().info("公開アドレス: " + result + "（STUN " + server + " 経由）");
                }
                publicEndpoint = result;
                return;
            }
        }
        plugin.getLogger().warning("STUN に全部失敗（インターネット越えの候補を出せません）");
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
