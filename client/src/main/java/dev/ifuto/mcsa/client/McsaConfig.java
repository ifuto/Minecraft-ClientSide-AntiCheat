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
