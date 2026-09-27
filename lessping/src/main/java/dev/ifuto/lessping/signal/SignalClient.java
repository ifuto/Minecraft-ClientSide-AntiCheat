package dev.ifuto.lessping.signal;

import com.google.gson.JsonParser;
import dev.ifuto.lessping.LpConfig;
import dev.ifuto.lessping.tunnel.WsLink;
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
 * <p>中継プラグインが Paper/Velocity と同じ PC でトンネルの受け口を持ち、自分の
 * エンドポイント候補を status 応答の version 名（{@code LP1:...} 平文 /
 * {@code LP2:...} 暗号化）に載せている。ここでは本物の Minecraft 接続を張らず、
 * status ハンドシェイクだけで候補を取得する。
 *
 * <p>経路は 2 つあり、設定されていれば WebSocket（{@code wsUrl}、Cloudflare Tunnel 等）
 * を優先する。どちらも本サーバーに入らずにトンネルを張れる:
 * <ol>
 *   <li>{@code wsUrl}（wss://...）→ WebSocket でプロキシへ到達し、その裏で status ping</li>
 *   <li>{@code signalServer} → 直接 TCP で status ping（従来経路）</li>
 * </ol>
 */
public final class SignalClient {

    private SignalClient() {
    }

    /** シグナリングの結果。reached = サーバー自体には応答した（候補が空でも） */
    public record SignalResult(boolean reached, List<InetSocketAddress> candidates) {
    }

    /**
     * サーバーに status ping を送り、ホストのエンドポイント候補を取得する。
     * 例外は投げない。
     *
     * <p>{@code reached=true で candidates が空}は「サーバーは生きているが P2P 候補を
     * 出していない」（= P2P 無効の運用）。この場合呼び出し側は待たずにフォールバック
     * 経路（WebSocket）へ進んでよい。
     *
     * @param server 直接 TCP 経路のサーバーアドレス（wsUrl が失敗したときの予備）
     * @param secret サーバー側と揃えた共有鍵（無ければ空文字）
     * @param wsUrl WebSocket 経路（例: wss://narena.dpdns.org）。空なら直接 TCP のみ
     */
    public static SignalResult fetchCandidates(String server, String secret, String wsUrl) {
        // 1) WebSocket 経由（Cloudflare Tunnel 等。設定されていれば優先）
        if (wsUrl != null && !wsUrl.isBlank()) {
            try (WsLink.WsConnection ws = WsLink.WsConnection.connect(wsUrl, 5000)) {
                String[] hp = wsTarget(wsUrl);
                List<InetSocketAddress> candidates =
                        parseVersionName(ping(ws.in(), ws.out(), hp[0]), secret);
                return new SignalResult(true, candidates);
            } catch (Exception ignored) {
                // 次の経路へ
            }
        }
        // 2) signalServer への直接 TCP
        String[] hp = LpConfig.parseAddress(server, 25565);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 4000);
            socket.setSoTimeout(4000);
            socket.setTcpNoDelay(true);
            return new SignalResult(true, parseVersionName(
                    ping(socket.getInputStream(), socket.getOutputStream(), hp[0]), secret));
        } catch (Exception e) {
            return new SignalResult(false, List.of());
        }
    }

    /** ws:// wss:// URL から host(:port) を取り出す（status ping の handshake 用） */
    private static String[] wsTarget(String url) {
        boolean tls = url.startsWith("wss://");
        String rest = url.substring(tls ? 6 : 5);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        return LpConfig.parseAddress(hostPort, tls ? 443 : 80);
    }

    /**
     * 指定したストリームの先（TCP でも WebSocket でもよい）に status ping を送り、
     * version 名（blob）を取り出す。
     */
    private static String ping(InputStream in, OutputStream out, String host) throws Exception {
        // Handshake (id 0x00): protocol, host, port, nextState=1(status)
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarint(handshake, 0x00);
        writeVarint(handshake, SharedConstants.getProtocolVersion());
        writeString(handshake, host);
        // port は実際の接続先と違っても ping 応答には影響しないが、それらしい値を入れる
        handshake.write(0x63);
        handshake.write(0x2D);
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

    /** version 名（LP1/LP2）を候補アドレスへ分解。崩れていたら空リスト */
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
