package dev.ifuto.mcsa.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * クライアント側の設定（{@code config/mcsa/client.json}）。
 *
 * <p>この MOD はプレイヤーの環境情報をサーバへ送るため、送る範囲をプレイヤー自身が
 * 絞れるようにしている。送信を拒否した場合はサーバ側のポリシーに従って
 * 入室を断られることがある（それがこの仕組みの設計）。
 */
public final class McsaConfig {

    /** すべてのサーバに自己申告する（既定）。初回接続時にチャットで告知する。 */
    public static final String POLICY_ALL = "ALL";
    /** {@link #allowedServers} に列挙したサーバにだけ自己申告する。 */
    public static final String POLICY_ALLOWLIST = "ALLOWLIST";
    /** どのサーバにも自己申告しない。 */
    public static final String POLICY_NONE = "NONE";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** ALL / ALLOWLIST / NONE */
    public String reportPolicy = POLICY_ALL;
    /** reportPolicy=ALL のとき、自己申告を拒否するサーバアドレス（例: "play.example.com:25565"） */
    public List<String> deniedServers = new ArrayList<>();
    /** reportPolicy=ALLOWLIST のとき、自己申告を許可するサーバアドレス */
    public List<String> allowedServers = new ArrayList<>();

    public boolean collectMods = true;
    public boolean collectResourcePacks = true;
    public boolean collectShaderPacks = true;
    /** 疑わしい JVM 引数（-javaagent 等）だけを報告する */
    public boolean collectJvmArgs = true;
    /** ファイルの SHA-256 を計算する（重いので大きいファイルはスキップ） */
    public boolean hashFiles = true;
    public long hashMaxBytes = 64L * 1024 * 1024;
    /** 初回申告時にチャットへ告知を出す */
    public boolean chatNotice = true;

    /** 同意したプライバシィ文面の指紋（空 = 未同意） */
    public String consentHash = "";
    /** 同意した時刻（ミリ秒） */
    public long consentAt = 0;

    /** 注入（Mixin / agent / ライブラリ名 / jar の中身）の観測をする */
    public boolean collectInjection = true;
    /** 出所が分かっている Mixin 設定名（誤検知を減らすための許可リスト） */
    public List<String> knownMixinConfigs = new ArrayList<>();
    /** 怪しいとみなすライブラリ名のパターン（{@code contains:} / {@code prefix:} / {@code regex:}） */
    public List<String> suspiciousLibraryPatterns = new ArrayList<>();
    /** 追加で探すクラス名パターン（サーバー指定と同じ書式） */
    public List<String> extraClassPatterns = new ArrayList<>();

    /** 常時監視（ウォッチドッグ）の間隔（秒）。0 で停止 */
    public int watchdogIntervalSeconds = 45;

    /** 画像形式: PNG / JPEG */
    public String captureFormat = "PNG";
    /** JPEG の品質（0.2〜1.0） */
    public float captureQuality = 0.8f;
    /** 横幅がこれを超えたら縮小する（0 で無効） */
    public int captureMaxWidth = 1600;
    /** これを超えたら縮小して JPEG で送り直す（0 で無効） */
    public int captureMaxBytes = 6 * 1024 * 1024;
    /** 送信したことをログに残す（既定は残さない＝対象に痕跡を見せない） */
    public boolean captureLog = false;

    private static McsaConfig instance;

    public static McsaConfig get() {
        if (instance == null) {
            instance = new McsaConfig();
        }
        return instance;
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcsa").resolve("client.json");
    }

    public static void load() {
        Path file = configFile();
        // 初回起動（config/mcsa/client.json がまだ無い）は既定値から始める。
        // ここで null のまま進むと下の instance.deniedServers で NPE になり、
        // Minecraft が起動できなくなる（実機で発生: mclo.gs/jcCga82）。
        if (instance == null) {
            instance = new McsaConfig();
        }
        try {
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                McsaConfig parsed = GSON.fromJson(json, McsaConfig.class);
                if (parsed != null) {
                    instance = parsed;
                }
            }
        } catch (Exception e) {
            McsaClient.LOGGER.warn("[MCSA] 設定の読み込みに失敗しました。既定値を使います: {}", e.toString());
            instance = new McsaConfig();
        }
        if (instance.deniedServers == null) {
            instance.deniedServers = new ArrayList<>();
        }
        if (instance.allowedServers == null) {
            instance.allowedServers = new ArrayList<>();
        }
        if (instance.knownMixinConfigs == null) {
            instance.knownMixinConfigs = new ArrayList<>();
        }
        if (instance.suspiciousLibraryPatterns == null) {
            instance.suspiciousLibraryPatterns = new ArrayList<>();
        }
        if (instance.extraClassPatterns == null) {
            instance.extraClassPatterns = new ArrayList<>();
        }
        if (instance.captureFormat == null || instance.captureFormat.isBlank()) {
            instance.captureFormat = "PNG";
        }
        instance.save();
    }

    public void save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            McsaClient.LOGGER.warn("[MCSA] 設定を保存できませんでした: {}", e.toString());
        }
    }

    /**
     * このサーバに自己申告してよいか。
     *
     * @param serverAddress 接続先（{@code host} または {@code host:port}）。null 可
     */
    public boolean mayReport(String serverAddress) {
        if (POLICY_NONE.equalsIgnoreCase(reportPolicy)) {
            return false;
        }
        if (POLICY_ALLOWLIST.equalsIgnoreCase(reportPolicy)) {
            return matches(allowedServers, serverAddress);
        }
        return !matches(deniedServers, serverAddress);
    }

    private static boolean matches(List<String> list, String serverAddress) {
        if (list == null || list.isEmpty() || serverAddress == null) {
            return false;
        }
        String lower = serverAddress.toLowerCase();
        for (String entry : list) {
            if (entry == null) {
                continue;
            }
            String e = entry.trim().toLowerCase();
            if (e.isEmpty()) {
                continue;
            }
            if (e.equals(lower) || lower.startsWith(e + ":") || lower.endsWith("." + e)) {
                return true;
            }
        }
        return false;
    }
}
