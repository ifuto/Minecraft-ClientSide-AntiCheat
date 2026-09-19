package dev.ifuto.mcsa.client.consent;

import dev.ifuto.mcsa.client.McsaClient;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.util.Hashing;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 同意（consent）の管理。
 *
 * <p>Minecraft 起動後にプライバシィ告知を出し、<b>同意するまでゲームを進められない</b>。
 * 拒否したら Minecraft 自体を終了させる（＝MOD を抜く以外に回避できない）。
 *
 * <p>同意は「文面の SHA-256」として記録する。文面が変わったら（MOD の更新でも
 * サーバー運営が {@code config/mcsa/privacy-notice.txt} を編集しても）<b>再度同意を取り直す</b>。
 * 同意の事実（ハッシュと時刻）はレポートに乗せてサーバー側にも残す。
 */
public final class ConsentManager {

    /** jar に同梱されている既定の文面 */
    private static final String BUNDLED = "/privacy-notice.txt";

    private static String text;
    private static String hash = "";

    private ConsentManager() {
    }

    /** 告知文面（{@code config/mcsa/privacy-notice.txt} があればそれを使う） */
    public static synchronized String text() {
        if (text != null) {
            return text;
        }
        Path file = configFile();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, bundled(), StandardCharsets.UTF_8);
            }
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            McsaClient.LOGGER.warn("[MCSA] 告知文面を読み込めませんでした: {}", e.toString());
            text = bundled();
        }
        if (text == null || text.isBlank()) {
            text = bundled();
        }
        hash = shortHash(text);
        return text;
    }

    /** 文面の指紋（先頭 12 文字）。同意の記録とレポートに載せる */
    public static String hash() {
        text();
        return hash;
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcsa").resolve("privacy-notice.txt");
    }

    /** 同意が必要か（未取得、または文面が変わった） */
    public static boolean needsConsent() {
        String recorded = McsaConfig.get().consentHash;
        return recorded == null || recorded.isBlank() || !recorded.equals(hash());
    }

    public static boolean accepted() {
        return !needsConsent();
    }

    public static void accept() {
        McsaConfig config = McsaConfig.get();
        config.consentHash = hash();
        config.consentAt = System.currentTimeMillis();
        config.save();
        McsaClient.LOGGER.info("[MCSA] プライバシィ告知に同意しました (hash={})", config.consentHash);
    }

    /** 拒否の記録（次の起動でも再度聞く。ログにも残す） */
    public static void decline() {
        McsaConfig config = McsaConfig.get();
        config.consentHash = "";
        config.consentAt = 0;
        config.save();
        McsaClient.LOGGER.warn("[MCSA] プライバシィ告知が拒否されました。Minecraft を終了します");
    }

    private static String bundled() {
        try (InputStream in = ConsentManager.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                return FALLBACK;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return FALLBACK;
        }
    }

    private static String shortHash(String value) {
        String full = Hashing.sha256Hex(value);
        return full == null ? "" : full.substring(0, 12);
    }

    /** リソースが somehow 読めなかったときの最低限の文面 */
    private static final String FALLBACK = """
            Data Privacy and Incident Prevention Guidelines

            This Mod reports the list of installed mods, resource packs and shader packs,
            file hashes, injection indicators and (only when a server operator requests it)
            a single screenshot of your game window to the server you join.
            The data is used solely to detect cheating and ToS violations.

            The full notice is in config/mcsa/privacy-notice.txt
            """;
}
