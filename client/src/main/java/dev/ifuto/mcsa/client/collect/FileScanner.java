package dev.ifuto.mcsa.client.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** ディレクトリの中身（名前・サイズ・SHA-256）を列挙する共通処理。 */
public final class FileScanner {

    private static final int MAX_ENTRIES = 256;
    private static final int MAX_FOLDER_ENTRIES = 4096;

    private FileScanner() {
    }

    public static JsonArray listDirectory(Path dir, McsaConfig config) {
        JsonArray array = new JsonArray();
        if (!Files.isDirectory(dir)) {
            return array;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> entries = stream.sorted().limit(MAX_ENTRIES).toList();
            for (Path path : entries) {
                array.add(describe(path, config));
            }
        } catch (IOException e) {
            // 読めなければ空で返す
        }
        return array;
    }

    private static JsonObject describe(Path path, McsaConfig config) {
        JsonObject o = new JsonObject();
        o.addProperty("name", path.getFileName().toString());
        if (Files.isDirectory(path)) {
            o.addProperty("type", "folder");
            o.addProperty("size", folderSize(path));
        } else {
            o.addProperty("type", "file");
            try {
                o.addProperty("size", Files.size(path));
            } catch (IOException e) {
                o.addProperty("size", -1);
            }
            if (config.hashFiles) {
                String hash = Hashing.sha256Hex(path, config.hashMaxBytes);
                if (hash != null) {
                    o.addProperty("sha256", hash);
                }
            }
        }
        return o;
    }

    private static long folderSize(Path dir) {
        long total = 0;
        try (Stream<Path> stream = Files.walk(dir, 4)) {
            List<Path> files = stream.filter(Files::isRegularFile).limit(MAX_FOLDER_ENTRIES).toList();
            for (Path file : files) {
                try {
                    total += Files.size(file);
                } catch (IOException ignored) {
                    // 数えられないファイルは無視
                }
            }
        } catch (IOException e) {
            return -1;
        }
        return total;
    }
}
