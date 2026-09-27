package dev.ifuto.lessping.signal;

import com.google.gson.JsonParser;
import dev.ifuto.lessping.LpConfig;
import dev.ifuto.lessping.tunnel.lpx.LpSecret;
import net.minecraft.SharedConstants;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * サーバーリスト ping（status ping）を使ったシグナリング。
 *
 * <p>中継プラグインが Paper と同じ PC でトンネルの受け口を持ち、自分の
 * エンドポイント候補を status 応答の version 名（{@code LP1:...}）に載せている。
 * ここでは本物の Minecraft 接続を張らず、status ハンドシェイクだけで候補を取得する。
 *
 * <p>これにより「先に本サーバーに入って INTRO をもらう」必要がなく、
 * クライアント起動直後からトンネルを張れる。
 */
public final class SignalClient {

    private SignalClient() {
    }

    /**
     * サーバーに status ping を送り、ホストのエンドポイント候補を取得する。
     * どれかの段階で失敗したら空リストを返す（例外は投げない）。
     *
     * @param server サーバーアドレス（{@code host} または {@code host:port}）
     * @param secret サーバー側と揃えた共有鍵（無ければ空文字）
     */
    public static List<InetSocketAddress> fetchCandidates(String server, String secret) {
        String[] hp = LpConfig.parseAddress(server, 25565);
        try {
            String versionName = ping(hp[0], Integer.parseInt(hp[1]));
            return parseVersionName(versionName, secret);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** status ping を送って version 名（blob）を取り出す */
    private static String ping(String host, int port) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setSoTimeout(4000);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Handshake (id 0x00): protocol, host, port, nextState=1(status)
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarint(handshake, 0x00);
            writeVarint(handshake, SharedConstants.getProtocolVersion());
            writeString(handshake, host);
            handshake.write((port >>> 8) & 0xFF);
            handshake.write(port & 0xFF);
            writeVarint(handshake, 1);
            writePacket(out, handshake.toByteArray());

            // Status Request (id 0x00, 中身なし)
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            writeVarint(request, 0x00);
            writePacket(out, request.toByteArray());
            out.flush();

            // 応答: Status Response (id 0x00) + JSON 文字列
            int len = readVarint(in);
            if (len <= 0 || len > 65536) {
                throw new IllegalStateException("status 応答が大きすぎます: " + len);
            }
            int id = readVarint(in);
            if (id != 0x00) {
                throw new IllegalStateException("status 応答ではありません: " + id);
            }
            String json = readString(in);
            return JsonParser.parseString(json).getAsJsonObject()
                    .getAsJsonObject("version").get("name").getAsString();
        }
    }

    /** version 名（LP1:...）を候補アドレスへ分解。崩れていたら空リスト */
    public static List<InetSocketAddress> parseVersionName(String versionName, String secret) {
        List<InetSocketAddress> out = new ArrayList<>();
        String data = LpSecret.decode(versionName, secret == null ? "" : secret);
        if (data == null) {
            return out;
        }
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
                    InetAddress.getByName(hp[0]); // 不正なホスト名なら弾く
                    out.add(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
                } catch (Exception ignored) {
                    // 不正なエントリは無視
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ wire

    private static void writePacket(OutputStream out, byte[] payload) throws Exception {
        writeVarint(out, payload.length);
        out.write(payload);
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

    private static void writeString(OutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(out, bytes.length);
        out.write(bytes);
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

    private static String readString(InputStream in) throws Exception {
        int len = readVarint(in);
        if (len < 0 || len > 65536) {
            throw new IllegalStateException("文字列が長すぎます: " + len);
        }
        byte[] bytes = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(bytes, read, len - read);
            if (n < 0) {
                throw new IllegalStateException("文字列の途中で切れました");
            }
            read += n;
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
