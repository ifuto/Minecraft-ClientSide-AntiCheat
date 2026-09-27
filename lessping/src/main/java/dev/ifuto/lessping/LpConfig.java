package dev.ifuto.lessping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ifuto.lessping.tunnel.lpx.LpSecret;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * クライアント設定（{@code config/lessping/client.json}）。
 *
 * <p><b>hostMode</b>: サーバーを建てている人が true にする。
 * トンネルの受け口になり、届いた接続を {@code backend}（たいてい localhost の Paper）
 * へ流す。それ以外のプレイヤーは false のまま（{@code hostPlayer} に指定された
 * プレイヤーへのトンネルを張る側）。
 */
public final class LpConfig {

    public boolean enabled = true;

    /** サーバーを建てている側（トンネルの受け手）なら true */
    public boolean hostMode = false;

    /** この名前で接続したらトンネルへ差し替える（ドットを含まない名前を推奨） */
    public List<String> magicNames = new ArrayList<>(List.of("narena"));

    /** hostMode=false のとき、誰へのトンネルを張るか（ホストのプレイヤー名） */
    public String hostPlayer = "Ifuto_mitai";

    /** ローカルの受け口 TCP ポート（Minecraft はここへ接続し、中継されてトンネルへ） */
    public int localTcpPort = 25599;

    /** UDP ポート。0 = 空いているポートを自動選択 */
    public int udpPort = 0;

    /** hostMode のとき、届いた接続の転送先 */
    public String backend = "127.0.0.1:25565";

    /**
     * サーバーリスト ping でシグナリングする相手（本サーバーのアドレス）。
     * 中継プラグインがホスト端末の候補を version 名に載せて応答する。
     * 空文字にするとこの経路は無効（ゲーム内 INTRO のみになる）
     */
    public String signalServer = "n-arena.play.minekube.net";

    /** シグナリングのポーリング間隔（ミリ秒）。トンネル確立中はポーリングしない */
    public int signalPollMs = 15000;

    /**
     * サーバー側 host-endpoint.secret と揃える共有鍵。
     * 空 = 内蔵鍵（既定・設定不要）、"none" = 無認証（平文 LP1）、
     * それ以外 = 独自鍵。実効値は {@link #effectiveSecret()} で取得する
     */
    public String secret = "";

    /**
     * WebSocket 経由の URL（Cloudflare Tunnel 等。例: "wss://narena.dpdns.org"）。
     * 設定されていると:
     * <ul>
     *   <li>シグナリング（status ping）をこの経路で行う（優先）</li>
     *   <li>P2P トンネルを張れないとき、この経路で通常接続を続行する
     *       （サーバーPC の IP を公開しないフォールバック）</li>
     * </ul>
     * 空文字なら無効（直接 TCP のみ）
     */
    public String wsUrl = "wss://narena.dpdns.org";

    /** 自分のアドレス (ip:port) を調べる STUN サーバー */
    public List<String> stunServers = new ArrayList<>(List.of(
            "stun.cloudflare.com:3478",
            "stun.l.google.com:19302"));

    /** NAT 穴あけ (PUNCH) を送る間隔（ミリ秒） */
    public int punchIntervalMs = 250;

    /** keepalive の間隔（ミリ秒） */
    public int keepaliveMs = 5000;

    /** これ以上相手からの音信がなければトンネル断とみなす（ミリ秒） */
    public int idleTimeoutMs = 20000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static LpConfig load() {
        Path file = configFile();
        try {
            if (Files.exists(file)) {
                LpConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LpConfig.class);
                if (config != null) {
                    config.save();
                    return config;
                }
            }
        } catch (Exception e) {
            LessPing.LOGGER.warn("[LessPing] 設定の読み込みに失敗したので既定値を使います: {}", e.toString());
        }
        LpConfig config = new LpConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LessPing.LOGGER.warn("[LessPing] 設定の保存に失敗: {}", e.toString());
        }
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("lessping").resolve("client.json");
    }

    /** 実効鍵。空 = 内蔵鍵、"none" = 無認証、それ以外 = その値 */
    public String effectiveSecret() {
        return LpSecret.resolve(secret);
    }

    /** "host:port" を分解する。ポート省略時は fallbackPort */
    public static String[] parseAddress(String value, int fallbackPort) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            return new String[]{"127.0.0.1", String.valueOf(fallbackPort)};
        }
        int i = v.lastIndexOf(':');
        if (i > 0 && i < v.length() - 1) {
            return new String[]{v.substring(0, i), v.substring(i + 1)};
        }
        return new String[]{v, String.valueOf(fallbackPort)};
    }
}
