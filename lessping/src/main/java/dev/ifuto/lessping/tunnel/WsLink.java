package dev.ifuto.lessping.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 汎用の双方向リンク（生 TCP でも WebSocket でも同じ形で扱う）。
 *
 * <p>{@link WsConnection} は RFC 6455 の WebSocket クライアントを Java 標準ライブラリ
 * だけで実装したもの。Cloudflare Tunnel のような「外側は HTTPS/WSS しか通さない」
 * 経路を通って、その裏で任意のバイトストリーム（Minecraft プロトコル）を運ぶために
 * 使う。TLS は {@code wss://} のとき javax.net.ssl（SNI 対応）、フレーミングは自前。
 *
 * <p>スレッド構成: {@link #connect} を呼んだスレッドでハンドシェイクまで行い、
 * 受信ループは専用スレッド（フレームを解析して {@link #in()} のパイプへ流す）。
 * 送信は {@link #out()}（バッファリングされ、flush() ごとに 1 バイナリフレーム）。
 */
public final class WsLink {

    private WsLink() {
    }

    /** 双方向リンク */
    public interface Link extends AutoCloseable {
        InputStream in() throws IOException;

        OutputStream out() throws IOException;

        @Override
        void close();
    }

    /** 生 TCP ソケットのラップ */
    public static final class SocketLink implements Link {
        private final Socket socket;

        public SocketLink(Socket socket) {
            this.socket = socket;
        }

        @Override
        public InputStream in() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream out() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 既に閉じている
            }
        }
    }

    // ------------------------------------------------------------------ WebSocket クライアント

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    private static final int OPCODE_CONT = 0;
    private static final int OPCODE_TEXT = 1;
    private static final int OPCODE_BIN = 2;
    private static final int OPCODE_CLOSE = 8;
    private static final int OPCODE_PING = 9;
    private static final int OPCODE_PONG = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** WebSocket 接続（クライアント側） */
    public static final class WsConnection implements Link {

        private final Socket socket;
        private final OutputStream rawOut;
        private final PipedInputStream pipeIn;
        private final PipedOutputStream pipeOut;
        private final WsOutputStream wsOut;
        private volatile boolean closed;

        private WsConnection(Socket socket, OutputStream rawOut) throws IOException {
            this.socket = socket;
            this.rawOut = rawOut;
            this.pipeIn = new PipedInputStream(64 * 1024);
            this.pipeOut = new PipedOutputStream(pipeIn);
            this.wsOut = new WsOutputStream();
            Thread reader = new Thread(this::readLoop, "lessping-ws-read");
            reader.setDaemon(true);
            reader.start();
        }

        /**
         * 接続する。
         *
         * @param url {@code ws://host:port/path} または {@code wss://host/path}
         * @param timeoutMs 接続タイムアウト
         */
        public static WsConnection connect(String url, int timeoutMs) throws IOException {
            boolean tls = url.startsWith("wss://");
            String rest = url.substring(tls ? 6 : 5);
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            if (hostPort == null || hostPort.isBlank()) {
                throw new IOException("不正な WebSocket URL: " + url);
            }
            int defaultPort = tls ? 443 : 80;
            String[] hp = dev.ifuto.lessping.LpConfig.parseAddress(hostPort, defaultPort);
            String host = hp[0];
            int port = Integer.parseInt(hp[1]);

            Socket socket = null;
            try {
                if (tls) {
                    javax.net.ssl.SSLSocketFactory factory =
                            (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                    javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) factory.createSocket();
                    // SNI を設定（Cloudflare 等の仮想ホスト振り分けに必要）
                    javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                    params.setServerNames(List.of(new javax.net.ssl.SNIHostName(host)));
                    ssl.setSSLParameters(params);
                    socket = ssl;
                    socket.connect(new InetSocketAddress(host, port), timeoutMs);
                    ssl.startHandshake();
                } else {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), timeoutMs);
                }
                socket.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // ハンドシェイク（RFC 6455）
                byte[] keyBytes = new byte[16];
                RANDOM.nextBytes(keyBytes);
                String key = Base64.getEncoder().encodeToString(keyBytes);
                String hostHeader = port == defaultPort ? host : host + ":" + port;
                String request = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + hostHeader + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: " + key + "\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "\r\n";
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String head = readHttpHead(in);
                if (head == null || !(head.startsWith("HTTP/1.1 101") || head.startsWith("HTTP/1.0 101"))) {
                    throw new IOException("WebSocket upgrade 失敗: "
                            + (head == null ? "応答なし" : head.split("\r\n", 2)[0]));
                }
                String accept = headerValue(head, "sec-websocket-accept");
                String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
                if (accept == null || !accept.trim().equals(expected)) {
                    throw new IOException("Sec-WebSocket-Accept 不一致");
                }
                return new WsConnection(socket, out);
            } catch (Exception e) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                        // 握りつぶす
                    }
                }
                if (e instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException(e);
            }
        }

        @Override
        public InputStream in() {
            return pipeIn;
        }

        @Override
        public OutputStream out() {
            return wsOut;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                sendFrame(OPCODE_CLOSE, new byte[0]);
            } catch (Exception ignored) {
                // ベストエフォート
            }
            try {
                socket.close();
            } catch (Exception ignored) {
                // 既に閉じている
            }
            try {
                pipeOut.close();
            } catch (Exception ignored) {
                // 既に閉じている
            }
        }

        // ---------------------------------------------------------------- 受信

        private void readLoop() {
            try {
                InputStream in = socket.getInputStream();
                while (!closed) {
                    int b0 = in.read();
                    if (b0 < 0) {
                        break;
                    }
                    int b1 = in.read();
                    if (b1 < 0) {
                        break;
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
                        break; // 異常なフレーム
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
                        case OPCODE_BIN, OPCODE_CONT -> pipeOut.write(payload);
                        case OPCODE_TEXT -> {
                            // テキストは使わない（ヘルスチェック等はサーバー側で処理済み）
                        }
                        case OPCODE_CLOSE -> {
                            close();
                            return;
                        }
                        case OPCODE_PING -> sendFrame(OPCODE_PONG, payload);
                        case OPCODE_PONG -> {
                            // 応答は不要
                        }
                        default -> {
                            // 未知の opcode は無視
                        }
                    }
                }
            } catch (Exception ignored) {
                // 切断
            } finally {
                try {
                    pipeOut.close();
                } catch (Exception ignored) {
                    // 既に閉じている
                }
            }
        }

        // ---------------------------------------------------------------- 送信

        /** クライアント → サーバーのフレームは必ずマスクする（RFC 6455） */
        private void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (rawOut) {
                byte[] mask = new byte[4];
                RANDOM.nextBytes(mask);
                rawOut.write(0x80 | opcode);
                int len = payload.length;
                if (len < 126) {
                    rawOut.write(len);
                } else if (len < 65536) {
                    rawOut.write(126);
                    rawOut.write(len >>> 8);
                    rawOut.write(len & 0xFF);
                } else {
                    rawOut.write(127);
                    for (int i = 7; i >= 0; i--) {
                        rawOut.write((int) ((long) len >>> (8 * i)) & 0xFF);
                    }
                }
                rawOut.write(mask);
                for (int i = 0; i < len; i++) {
                    rawOut.write(payload[i] ^ mask[i & 3]);
                }
                rawOut.flush();
            }
        }

        /** バッファリング付き出力。flush() ごとに 1 バイナリフレームになる */
        private final class WsOutputStream extends OutputStream {
            private byte[] buf = new byte[16384];
            private int count;

            @Override
            public void write(int b) throws IOException {
                if (count == buf.length) {
                    flushInternal();
                }
                buf[count++] = (byte) b;
            }

            @Override
            public void write(byte[] src, int off, int len) throws IOException {
                while (len > 0) {
                    if (count == buf.length) {
                        flushInternal();
                    }
                    int take = Math.min(buf.length - count, len);
                    System.arraycopy(src, off, buf, count, take);
                    count += take;
                    off += take;
                    len -= take;
                }
            }

            @Override
            public void flush() throws IOException {
                if (count > 0) {
                    flushInternal();
                }
            }

            private void flushInternal() throws IOException {
                if (closed) {
                    throw new IOException("WebSocket は閉じています");
                }
                byte[] chunk = new byte[count];
                System.arraycopy(buf, 0, chunk, 0, count);
                count = 0;
                sendFrame(OPCODE_BIN, chunk);
            }
        }
    }

    // ------------------------------------------------------------------ 共通下請け

    /** HTTP ヘッダ部（\r\n\r\n まで）を読む。8KB 超 or 切断で null */
    static String readHttpHead(InputStream in) throws IOException {
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
    static String headerValue(String head, String name) {
        for (String line : head.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) {
                return line.substring(i + 1).trim();
            }
        }
        return null;
    }

    static void readFully(InputStream in, byte[] buf) throws IOException {
        int read = 0;
        while (read < buf.length) {
            int n = in.read(buf, read, buf.length - read);
            if (n < 0) {
                throw new IOException("ストリームの途中で切断されました");
            }
            read += n;
        }
    }

    static int readU16(InputStream in) throws IOException {
        int hi = in.read();
        int lo = in.read();
        if (hi < 0 || lo < 0) {
            throw new IOException("切断されました");
        }
        return (hi << 8) | lo;
    }

    static long readU64(InputStream in) throws IOException {
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

    static void applyMask(byte[] data, byte[] mask) {
        for (int i = 0; i < data.length; i++) {
            data[i] ^= mask[i & 3];
        }
    }
}
