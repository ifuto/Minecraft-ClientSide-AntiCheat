package dev.ifuto.mcsa.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OP 用 MOD の設定（{@code config/mcsa/admin.json}）。
 *
 * <p>証拠（画面）を受け取ったときの振る舞いだけを設定できる。
 */
public final class AdminConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 受信した画像を OS の既定ビューアで自動で開く */
    public boolean autoOpen = true;
    /** 受信したときにゲーム内の一覧画面を開く */
    public boolean showScreen = true;
    /** 保存先（.minecraft からの相対パス） */
    public String saveFolder = "mcsa-evidence";

    private static AdminConfig instance;

    private AdminConfig() {
    }

    public static AdminConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static AdminConfig load() {
        Path file = configFile();
        try {
            if (Files.exists(file)) {
                AdminConfig parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AdminConfig.class);
                if (parsed != null) {
                    save(parsed);
                    return parsed;
                }
            }
        } catch (Exception ignored) {
            // 壊れていたら既定値で作る
        }
        AdminConfig config = new AdminConfig();
        save(config);
        return config;
    }

    private static void save(AdminConfig config) {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 保存できなくても動作はする
        }
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcsa").resolve("admin.json");
    }

    public String folderName() {
        String value = saveFolder == null || saveFolder.isBlank() ? "mcsa-evidence" : saveFolder.trim();
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
