package dev.ifuto.mcsa.admin;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * サーバーから転送されてきた証拠（画面 / テキスト）を受け取って、OP が確認できるようにする。
 *
 * <p>断片を {@code transferId} ごとに束ね、揃ったら
 * <ol>
 *   <li>{@code .minecraft/mcsa-evidence/} に保存する</li>
 *   <li>チャットにパスを出す（クリックで OS のビューアが開く）</li>
 *   <li>ゲーム内のビューア画面を開く（{@link EvidenceScreen}）</li>
 * </ol>
 * を行う。
 *
 * <p>サーバー側の原本（{@code plugins/MCSA/evidence/}）と監査ログはそのまま残るので、
 * これは OP の手元コピー。
 */
public final class EvidenceReceiver {

    private static final Map<Integer, byte[][]> PENDING = new ConcurrentHashMap<>();
    private static final Map<Integer, String> NAMES = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> KINDS = new ConcurrentHashMap<>();
    private static final AtomicInteger TRANSFERS = new AtomicInteger();

    private EvidenceReceiver() {
    }

    public static Path folder() {
        return FabricLoader.getInstance().getGameDir().resolve(AdminConfig.get().folderName());
    }

    public static int transferCount() {
        return TRANSFERS.get();
    }

    public static void onChunk(ShotPayload payload) {
        if (payload.total() <= 0 || payload.total() > 1024
                || payload.seq() < 0 || payload.seq() >= payload.total()) {
            return;
        }
        byte[][] parts = PENDING.computeIfAbsent(payload.transferId(), id -> new byte[payload.total()][]);
        if (parts.length != payload.total()) {
            PENDING.remove(payload.transferId());
            return;
        }
        NAMES.put(payload.transferId(), payload.name());
        KINDS.put(payload.transferId(), payload.kind());
        parts[payload.seq()] = payload.data();

        int received = 0;
        int length = 0;
        for (byte[] part : parts) {
            if (part != null) {
                received++;
                length += part.length;
            }
        }
        if (received < payload.total()) {
            return;
        }
        PENDING.remove(payload.transferId());
        byte[] data = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, data, offset, part.length);
            offset += part.length;
        }
        TRANSFERS.incrementAndGet();
        finish(NAMES.remove(payload.transferId()), KINDS.remove(payload.transferId()), data);
    }

    private static void finish(String name, Integer kind, byte[] data) {
        boolean text = kind != null && kind == ShotPayload.KIND_NOTE;
        Path file = save(name, data, text);
        if (file == null) {
            notify("[MCSA] 証拠を保存できませんでした（" + data.length + " bytes）", null, 0xFF5555);
            return;
        }
        if (text) {
            String body = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            notify("[MCSA] テキスト証拠 " + file.getFileName() + ": "
                    + (body.length() > 300 ? body.substring(0, 300) + "…" : body), file, 0xFFFF55);
            return;
        }
        notify("[MCSA] 画面を受信しました " + file.getFileName() + " (" + data.length
                + " bytes) クリックで開く", file, 0x55FF55);
        if (AdminConfig.get().showScreen) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> client.setScreen(new EvidenceScreen(file, data.length)));
            }
        }
        if (AdminConfig.get().autoOpen) {
            openInOs(file);
        }
    }

    /** {@code .minecraft/<saveFolder>/} に保存する */
    public static Path save(String name, byte[] data, boolean text) {
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            Path file = dir.resolve(safeName(name, text));
            Files.write(file, data);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /** OS の既定ビューアで開く（Minecraft の Util 経由） */
    public static void openInOs(Path file) {
        try {
            Class<?> util = Class.forName("net.minecraft.util.Util");
            Object operatingSystem = util.getMethod("getOperatingSystem").invoke(null);
            for (Method method : operatingSystem.getClass().getMethods()) {
                if (!"open".equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> type = method.getParameterTypes()[0];
                if (type == File.class) {
                    method.invoke(operatingSystem, file.toFile());
                    return;
                }
                if (type == Path.class) {
                    method.invoke(operatingSystem, file);
                    return;
                }
                if (type == java.net.URI.class) {
                    method.invoke(operatingSystem, file.toUri());
                    return;
                }
            }
        } catch (Throwable ignored) {
            // 開けなくてもチャットにパスは出ている
        }
    }

    /**
     * 「クリックでファイルを開く」イベントを作る。
     *
     * <p>1.21.11 の {@code ClickEvent} は抽象クラスになっていて
     * {@code new ClickEvent(...)} できない（版によって生成方法が変わる）ため、
     * ファクトリメソッドをリフレクションで呼ぶ。取れなければリンク無しで出すだけ。
     */
    private static ClickEvent openFileEvent(String path) {
        try {
            java.lang.reflect.Method method = ClickEvent.class.getMethod("openFile", String.class);
            return (ClickEvent) method.invoke(null, path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** フォルダを開く */
    public static void openFolder() {
        try {
            Files.createDirectories(folder());
        } catch (Exception ignored) {
            // 作れなければそのまま開こうとする
        }
        openInOs(folder());
    }

    /** チャットに出す（{@code link} があればクリックで開ける） */
    public static void notify(String message, Path link, int color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        final Path linkPath = link;
        final int rgb = color;
        client.execute(() -> {
            try {
                if (client.inGameHud == null) {
                    return;
                }
                MutableText text = Text.literal(message);
                if (linkPath != null) {
                    ClickEvent click = openFileEvent(linkPath.toAbsolutePath().toString());
                    text = text.setStyle(text.getStyle()
                            .withClickEvent(click)
                            .withUnderline(true));
                } else {
                    text = text.setStyle(text.getStyle().withColor(TextColor.fromRgb(rgb)));
                }
                client.inGameHud.getChatHud().addMessage(text);
            } catch (Throwable ignored) {
                // HUD が無い状況
            }
        });
    }

    static String safeName(String name, boolean text) {
        String base = name == null ? "" : name.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.startsWith(".")) {
            base = "_" + base;
        }
        if (base.isEmpty()) {
            base = text ? "note.txt" : "shot.png";
        }
        if (base.length() > 90) {
            base = base.substring(base.length() - 90);
        }
        Path candidate = folder().resolve(base);
        int suffix = 1;
        while (Files.exists(candidate)) {
            int dot = base.lastIndexOf('.');
            String stem = dot < 0 ? base : base.substring(0, dot);
            String extension = dot < 0 ? "" : base.substring(dot);
            candidate = folder().resolve(stem + "-" + suffix + extension);
            suffix++;
        }
        return candidate.getFileName().toString();
    }
}
