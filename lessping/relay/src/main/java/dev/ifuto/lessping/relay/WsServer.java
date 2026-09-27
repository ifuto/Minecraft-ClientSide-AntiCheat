package dev.ifuto.lessping.relay;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket ⇄ TCP ブリッジ（Cloudflare Tunnel 等と組むためのもの）。
 *
 * <p>Cloudflare の無料 Tunnel は外側（エッジ）が HTTPS/WSS しか受けないため、
 * 生の Minecraft TCP をそのまま通せない。このサーバーが家の中で WebSocket を
 * 終端して、接続ごとに backend（この Velocity）へ TCP 中継する:
 *
 * <pre>
 * 友達のMOD ──WSS(443)──▶ Cloudflare エッジ ──▶ cloudflared ──HTTP(WS)──▶ このサーバー ──TCP──▶ Velocity
 * </pre>
 *
 * <p>これにより「サーバーPC の IP を一切公開せず」「家のポートを全閉じたまま」
 * 参加者が接続できる経路ができる（P2P 直結が失敗したときのフォールバックや、
 * シグナリングの経路として使う）。cloudflared のサービスは
 * {@code http://localhost:8081} を向くように設定する。
 *
 * <p>実装は RFC 6455 の最小部分（バイナリフレーム・ping/pong・close）のみ。
 * Java 標準ライブラリだけで動く（依存追加なし）。
 */
public final class WsServer {

    /** WebSocket ブリッジの設定 */
    public record Settings(String listenHost, int listenPort, int maxConns,
                           String backendHost, int backendPort, String version) {
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int OPCODE_CONT = 0;
    private static final int OPCODE_TEXT = 1;
    private static final int OPCODE_BIN = 2;
    private static final int OPCODE_CLOSE = 8;
    private static final int OPCODE_PING = 9;
    private static final int OPCODE_PONG = 10;

    private final Logger logger;
    private final Settings settings;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean stopped;
    private final AtomicInteger conns = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "lessping-ws");
        thread.setDaemon(true);
        return thread;
    });

    public WsServer(Logger logger, Settings settings) {
        this.logger = logger;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ ライフサイクル

    public void start() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(settings.listenHost(), settings.listenPort()), 16);
        stopped = false;
        acceptThread = new Thread(this::acceptLoop, "lessping-ws-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("WebSocket ブリッジを開始: ws://{}:{} → backend {}:{}"
                        + "（cloudflared のサービスは http://{}:{} を向けてください）",
                settings.listenHost(), settings.listenPort(),
                settings.backendHost(), settings.backendPort(),
                settings.listenHost(), settings.listenPort());
    }

    public void stop() {
        stopped = true;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
            // 既に閉じている
        }
        pool.shutdownNow();
    }

    public String describe() {
        if (stopped || server == null) {
            return "停止中";
        }
        return "ws://" + settings.listenHost() + ":" + settings.listenPort()
                + " 接続中 " + conns.get() + "/" + settings.maxConns();
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket socket = server.accept();
                if (conns.get() >= settings.maxConns()) {
                    logger.warn("WebSocket 接続数が上限 ({}) のため拒否", settings.maxConns());
                    socket.close();
                    continue;
                }
                conns.incrementAndGet();
                pool.execute(() -> {
                    try {
                        handle(socket);
                    } finally {
                        conns.decrementAndGet();
                    }
                });
            } catch (IOException e) {
                if (!stopped) {
                    logger.warn("WebSocket accept エラー: {}", e.toString());
                }
            }
        }
    }

    // ------------------------------------------------------------------ 接続処理

    private void handle(Socket socket) {
        Socket tcp = null;
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String head = readHttpHead(in);
            if (head == null) {
                return;
            }
            String upgrade = headerValue(head, "upgrade");
            if (upgrade == null || !upgrade.toLowerCase(java.util.Locale.ROOT).contains("websocket")) {
                // WebSocket でなければヘルスチェック用に 200 を返す
                respond(out, "200 OK", "LessPing-NArena WS bridge OK v" + settings.version() + "\n");
                return;
            }
            String key = headerValue(head, "sec-websocket-key");
            if (key == null) {
                respond(out, "400 Bad Request", "Sec-WebSocket-Key がありません\n");
                return;
            }
            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // backend（この Velocity）へ
            tcp = new Socket();
            tcp.setTcpNoDelay(true);
            tcp.connect(new InetSocketAddress(settings.backendHost(), settings.backendPort()), 5000);

            // TCP → WebSocket（別スレッド）
            final Socket backendSocket = tcp;
            final Socket clientSocket = socket;
            Future<?> tcpToWs = pool.submit(() -> {
                byte[] buf = new byte[16384];
                try {
                    InputStream tcpIn = backendSocket.getInputStream();
                    while (!stopped) {
                        int n = tcpIn.read(buf);
                        if (n < 0) {
                            break;
                        }
                        if (n > 0) {
                            sendFrame(out, OPCODE_BIN, buf, n);
                        }
                    }
                } catch (Exception ignored) {
                    // 切断
                } finally {
                    closeQuietly(backendSocket);
                    closeQuietly(clientSocket);
                }
            });

            // WebSocket → TCP（このスレッド）
            try {
                OutputStream tcpOut = tcp.getOutputStream();
                wsToTcpLoop(in, out, tcpOut);
            } finally {
                tcpToWs.cancel(false);
                closeQuietly(tcp);
                closeQuietly(socket);
            }
        } catch (Exception e) {
            if (!stopped) {
                logger.debug("WebSocket 接続の処理に失敗: {}", e.toString());
            }
        } finally {
            closeQuietly(tcp);
            closeQuietly(socket);
        }
    }

    /** WebSocket フレームを読んで backend の TCP へ流す。close で戻る */
    private void wsToTcpLoop(InputStream in, OutputStream wsOut, OutputStream tcpOut) throws IOException {
        while (!stopped) {
            int b0 = in.read();
            if (b0 < 0) {
                return;
            }
            int b1 = in.read();
            if (b1 < 0) {
                return;
            }
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7FL;
            if (len == 126) {
                len = readU16(in);
            } else if (len == 127) {
                len = readU64(in);
            }
            if (len < 0 || len > MAX_FRAME) {
                return; // 異常なフレーム
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (mask != null) {
                applyMask(payload, mask);
            }
            switch (opcode) {
                case OPCODE_BIN, OPCODE_CONT -> {
                    tcpOut.write(payload);
                    tcpOut.flush();
                }
                case OPCODE_TEXT -> {
                    // テキストフレームは使わない。ヘルスチェックは upgrade 前に処理済み
                }
                case OPCODE_CLOSE -> {
                    sendFrame(wsOut, OPCODE_CLOSE, new byte[0], 0);
                    return;
                }
                case OPCODE_PING -> sendFrame(wsOut, OPCODE_PONG, payload, payload.length);
                case OPCODE_PONG -> {
                    // 応答は不要
                }
                default -> {
                    // 未知の opcode は無視
                }
            }
        }
    }

    /** サーバー → クライアントのフレーム（マスクしない） */
    private void sendFrame(OutputStream out, int opcode, byte[] payload, int len) throws IOException {
        synchronized (out) {
            out.write(0x80 | opcode);
            if (len < 126) {
                out.write(len);
            } else if (len < 65536) {
                out.write(126);
                out.write(len >>> 8);
                out.write(len & 0xFF);
            } else {
                out.write(127);
                for (int i = 7; i >= 0; i--) {
                    out.write((int) ((long) len >>> (8 * i)) & 0xFF);
                }
            }
            out.write(payload, 0, len);
            out.flush();
        }
    }

    // ------------------------------------------------------------------ 下請け

    private void respond(OutputStream out, String status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (Exception ignored) {
            // ベストエフォート
        }
    }

    /** HTTP ヘッダ部（\r\n\r\n まで）を読む。8KB 超 or 切断で null */
    private static String readHttpHead(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int last = 0;
        while (out.size() < 8192) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            out.write(b);
            if (last == '\r' && b == '\n' && out.size() >= 4
                    && out.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                return out.toString(StandardCharsets.US_ASCII);
            }
            last = b;
        }
        return null;
    }

    /** ヘッダ値の取得（大文字小文字を区別しない） */
    private static String headerValue(String head, String name) {
        for (String line : head.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) {
                return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) {
                throw new IOException("ストリームの途中で切断されました");
            }
            read += n;
        }
    }

    private static int readU16(InputStream in) throws IOException {
        int hi = in.read();
        int lo = in.read();
        if (hi < 0 || lo < 0) {
            throw new IOException("切断されました");
        }
        return (hi << 8) | lo;
    }

    private static long readU64(InputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("切断されました");
            }
            v = (v << 8) | b;
        }
        return v;
    }

    private static void applyMask(byte[] data, byte[] mask) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= mask[i & 3];
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
