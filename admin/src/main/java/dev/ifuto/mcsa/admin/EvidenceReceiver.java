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
            notify("§c[MCSA] 証拠を保存できませんでした（" + data.length + " bytes）", null);
            return;
        }
        if (text) {
            String body = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            notify("§e[MCSA] テキスト証拠 §7" + file.getFileName() + ": §f"
                    + (body.length() > 300 ? body.substring(0, 300) + "…" : body), file);
            return;
        }
        notify("§a[MCSA] 画面を受信しました §7" + file.getFileName() + " (" + data.length
                + " bytes) §8クリックで開く", file);
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
    public static void notify(String message, Path link) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            try {
                if (client.inGameHud == null) {
                    return;
                }
                MutableText text = Text.literal(message);
                if (link != null) {
                    text = text.setStyle(text.getStyle()
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE,
                                    link.toAbsolutePath().toString()))
                            .withUnderline(true)
                            .withColor(TextColor.fromRgb(0x55FFFF)));
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
