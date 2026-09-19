package dev.ifuto.mcsa.server.evidence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.server.McsaPlugin;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 証拠の保存先。
 *
 * <pre>
 * plugins/MCSA/evidence/
 *   &lt;player&gt;-&lt;uuid8&gt;/
 *     2026-09-19T10-11-12_shot.png          … 本体
 *     2026-09-19T10-11-12_shot.png.json     … 誰が、いつ、なぜ取得したか
 *   evidence-log.txt                        … 監査ログ（1 行 1 件、追記のみ）
 * </pre>
 *
 * <p><b>画面は個人情報</b>なので、保存した事実・保存先・保持期間を
 * サーバーの利用規約で告知すること（{@code docs/PRIVACY.md}）。
 * 監査ログは「取得した側」も記録するため、OP 権限の悪用も追える。
 */
public final class EvidenceStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final McsaPlugin plugin;

    public EvidenceStore(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    public Path root() {
        return plugin.getDataFolder().toPath().resolve("evidence");
    }

    public Path playerDir(Player player) {
        return root().resolve(folderName(player));
    }

    /**
     * 証拠を保存する。
     *
     * @return 保存先のパス（失敗したら null）
     */
    public Path save(Player player, int kind, String name, byte[] data, String requestedBy,
                     String reason, String hmac, boolean hmacValid) {
        try {
            Path dir = playerDir(player);
            Files.createDirectories(dir);
            String stamp = Instant.now().toString().replace(':', '-').substring(0, 19);
            String safe = safeName(name, kind);
            Path file = dir.resolve(stamp + "_" + safe);
            Files.write(file, data);

            String sha256 = sha256Hex(data);
            JsonObject meta = new JsonObject();
            meta.addProperty("file", file.getFileName().toString());
            meta.addProperty("kind", kind);
            meta.addProperty("player", player.getName());
            meta.addProperty("uuid", player.getUniqueId().toString());
            meta.addProperty("requestedBy", requestedBy == null ? "?" : requestedBy);
            meta.addProperty("reason", reason == null ? "" : reason);
            meta.addProperty("at", Instant.now().toString());
            meta.addProperty("bytes", data.length);
            meta.addProperty("sha256", sha256);
            meta.addProperty("hmac", hmac == null ? "" : hmac);
            meta.addProperty("hmacValid", hmacValid);
            meta.addProperty("protocol", plugin.config().protocol);
            Files.writeString(dir.resolve(file.getFileName() + ".json"), GSON.toJson(meta),
                    StandardCharsets.UTF_8);

            appendLog(player, requestedBy, reason, file, data.length, sha256, hmacValid);
            return file;
        } catch (IOException e) {
            plugin.getLogger().warning("証拠を保存できませんでした: " + e);
            return null;
        }
    }

    /** 監査ログ（追記のみ） */
    private void appendLog(Player player, String requestedBy, String reason, Path file,
                           int bytes, String sha256, boolean hmacValid) {
        String line = String.format("%s\tplayer=%s\tuuid=%s\tby=%s\treason=%s\tfile=%s\tbytes=%d\tsha256=%s\thmac=%s%n",
                Instant.now(), player.getName(), player.getUniqueId(),
                requestedBy == null ? "?" : requestedBy,
                reason == null ? "" : reason,
                root().relativize(file), bytes, sha256, hmacValid ? "ok" : "INVALID");
        try {
            Files.createDirectories(root());
            Files.writeString(root().resolve("evidence-log.txt"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("監査ログを書けませんでした: " + e);
        }
    }

    /** そのプレイヤーの証拠一覧（新しい順） */
    public List<Path> list(Player player, int limit) {
        return list(playerDir(player), limit);
    }

    private List<Path> list(Path dir, int limit) {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .limit(Math.max(1, limit))
                    .forEach(files::add);
        } catch (IOException e) {
            plugin.getLogger().warning("証拠の一覧を作れませんでした: " + e);
        }
        return files;
    }

    /** 保持期間を過ぎた証拠を消す（0 以下で自動削除しない） */
    public int purgeOlderThan(int days) {
        if (days <= 0 || !Files.isDirectory(root())) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        int[] removed = {0};
        try (Stream<Path> stream = Files.walk(root())) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                        removed[0]++;
                    }
                } catch (IOException ignored) {
                    // 消せなくても続行
                }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("証拠の整理に失敗しました: " + e);
        }
        return removed[0];
    }

    private static String folderName(Player player) {
        String uuid = player.getUniqueId().toString().replace("-", "");
        return player.getName() + "-" + uuid.substring(0, Math.min(8, uuid.length()));
    }

    /** 送られてきたファイル名をそのまま使わない（パストラバーサル対策） */
    static String safeName(String name, int kind) {
        String base = name == null ? "" : name.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.startsWith(".")) {
            base = "_" + base;
        }
        if (base.length() > 80) {
            base = base.substring(base.length() - 80);
        }
        if (base.isEmpty()) {
            base = kind == 1 ? "shot.png" : "note.txt";
        }
        return base.toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** プレイヤーごとの UUID 一覧（フォルダ名から） */
    public List<String> folders() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(root())) {
            return names;
        }
        try (Stream<Path> stream = Files.list(root())) {
            stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .forEach(names::add);
        } catch (IOException ignored) {
            // 読めなければ空
        }
        return names;
    }
}
