package dev.ifuto.mcsa.client.capture;

import dev.ifuto.mcsa.client.McsaClient;
import dev.ifuto.mcsa.client.McsaConfig;
import dev.ifuto.mcsa.client.crypto.Signer;
import dev.ifuto.mcsa.client.net.EvidencePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Iterator;

/**
 * 画面の取得（OP の指示でのみ動く）。
 *
 * <p><b>対象プレイヤーの画面には一切表示しない。</b> チャットメッセージ・トースト・
 * 撮影音・スクリーンショットの通知、いずれも出さない。理由は単純で、
 * 「撮られた」と分かると証拠が残る前に抜ける（＝逃走する）から。
 * バニラの {@code GameRenderer#takeScreenshot} は必ずチャットに出すので使わず、
 * フレームバッファを直接読んで PNG/JPEG を<b>メモリ上で</b>作る。
 * 一時ファイルも作らない。
 *
 * <p>送信は {@code mcsa:evidence} で分割し、HMAC を付ける。
 * 保存先はサーバー側 {@code plugins/MCSA/evidence/}（{@code docs/PRIVACY.md} 参照）。
 *
 * <p>描画スレッド（クライアントスレッド）から呼ぶこと。
 */
public final class SilentCapture {

    /** 画像（PNG / JPEG） */
    public static final int KIND_SHOT = 1;
    /** テキスト（取得失敗の理由など） */
    public static final int KIND_NOTE = 3;

    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;

    private static volatile Boolean supported;

    private SilentCapture() {
    }

    /**
     * この環境で画面取得ができそうか。
     *
     * <p>GL 呼び出しをしない（描画スレッド以外から呼ばれるため）。
     * 必要なメソッドが実在するかだけを見る。実際の成否は {@link #capture} の結果で分かる。
     */
    public static boolean supported() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            Class<?> gl = Class.forName("net.minecraft.client.gl.GlStateManager");
            for (Method method : gl.getMethods()) {
                if (method.getParameterCount() == 7
                        && ("_readPixels".equals(method.getName()) || "readPixels".equals(method.getName()))) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
                for (Method method : gl11.getMethods()) {
                    if ("glReadPixels".equals(method.getName()) && method.getParameterCount() == 7) {
                        ok = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            ok = false;
        }
        supported = ok;
        return ok;
    }

    /**
     * 画面を取得してサーバーへ送る。
     *
     * @return 送信できたか
     */
    public static boolean capture(int sessionId, String nonce, String reason) {
        McsaConfig config = McsaConfig.get();
        if (!config.allowCapture) {
            sendNote(sessionId, nonce, "capture-disabled-by-client-config");
            return false;
        }
        try {
            int[] size = framebufferSize();
            if (size == null) {
                sendNote(sessionId, nonce, "no-framebuffer");
                return false;
            }
            int width = Math.max(1, size[0]);
            int height = Math.max(1, size[1]);
            ByteBuffer pixels = readPixels(width, height);
            if (pixels == null) {
                sendNote(sessionId, nonce, "readPixels-failed");
                return false;
            }
            BufferedImage image = toImage(pixels, width, height);
            if (config.captureMaxWidth > 0 && image.getWidth() > config.captureMaxWidth) {
                image = downscale(image, config.captureMaxWidth);
            }
            byte[] data = encode(image, config);
            if (config.captureMaxBytes > 0 && data.length > config.captureMaxBytes) {
                data = encode(downscale(image, Math.max(320, image.getWidth() / 2)), config, true);
            }
            return send(sessionId, nonce, KIND_SHOT, nameFor(reason, config), data);
        } catch (Throwable t) {
            sendNote(sessionId, nonce, "error:" + t.getClass().getSimpleName());
            return false;
        }
    }

    // ------------------------------------------------------------------ 送信

    private static String nameFor(String reason, McsaConfig config) {
        String extension = "JPEG".equalsIgnoreCase(config.captureFormat) ? "jpg" : "png";
        String safe = reason == null || reason.isBlank() ? "shot" : reason.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.length() > 40) {
            safe = safe.substring(0, 40);
        }
        return safe + "-" + System.currentTimeMillis() + "." + extension;
    }

    private static boolean send(int sessionId, String nonce, int kind, String name, byte[] raw) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            return false;
        }
        if (!ClientPlayNetworking.canSend(EvidencePayload.ID)) {
            sendNote(sessionId, nonce, "server-has-no-evidence-channel");
            return false;
        }
        int total = Math.max(1, (raw.length + EvidencePayload.CHUNK_SIZE - 1) / EvidencePayload.CHUNK_SIZE);
        String hmac = Signer.hmacHex(nonce == null ? "" : nonce, raw);
        // 断片を作ってからクライアントスレッドでまとめて送る
        java.util.List<EvidencePayload> payloads = new java.util.ArrayList<>(total);
        for (int seq = 0; seq < total; seq++) {
            int from = seq * EvidencePayload.CHUNK_SIZE;
            int length = Math.min(EvidencePayload.CHUNK_SIZE, raw.length - from);
            byte[] chunk = new byte[Math.max(length, 0)];
            if (length > 0) {
                System.arraycopy(raw, from, chunk, 0, length);
            }
            payloads.add(new EvidencePayload(sessionId, kind, seq, total, name, hmac, chunk));
        }
        client.execute(() -> {
            for (EvidencePayload payload : payloads) {
                ClientPlayNetworking.send(payload);
            }
        });
        if (McsaConfig.get().captureLog) {
            McsaClient.LOGGER.info("[MCSA] 画面を送信しました ({} bytes, {} chunks)", raw.length, total);
        }
        return true;
    }

    /** 短いテキストを送る（失敗理由を OP に伝えるため） */
    public static void sendNote(int sessionId, String nonce, String message) {
        try {
            send(sessionId, nonce, KIND_NOTE, "note-" + System.currentTimeMillis() + ".txt",
                    (message == null ? "" : message).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // 送れなければ諦める（対象には何も出さない）
        }
    }

    // ------------------------------------------------------------------ 取得

    private static int[] framebufferSize() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            Object framebuffer = call(client, "getFramebuffer");
            if (framebuffer != null) {
                int width = intValue(framebuffer, "getTextureWidth", "textureWidth");
                int height = intValue(framebuffer, "getTextureHeight", "textureHeight");
                if (width > 0 && height > 0) {
                    return new int[]{width, height};
                }
            }
            Object window = call(client, "getWindow");
            if (window != null) {
                int width = intValue(window, "getFramebufferWidth", "getWidth");
                int height = intValue(window, "getFramebufferHeight", "getHeight");
                if (width > 0 && height > 0) {
                    return new int[]{width, height};
                }
            }
        } catch (Throwable ignored) {
            // 下記で null
        }
        return null;
    }

    /** glReadPixels でピクセルを読む。描画スレッドから呼ぶこと。 */
    private static ByteBuffer readPixels(int width, int height) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return null;
            }
            Object framebuffer = call(client, "getFramebuffer");
            if (framebuffer != null) {
                call(framebuffer, "beginRead");
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
            boolean read = false;
            try {
                Class<?> gl = Class.forName("net.minecraft.client.gl.GlStateManager");
                for (Method method : gl.getMethods()) {
                    if (!method.getName().equals("_readPixels") && !method.getName().equals("readPixels")) {
                        continue;
                    }
                    if (method.getParameterCount() != 7) {
                        continue;
                    }
                    method.invoke(null, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                    read = true;
                    break;
                }
            } catch (Throwable ignored) {
                // LWJGL にフォールバック
            }
            if (!read) {
                Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
                for (Method method : gl11.getMethods()) {
                    if (!"glReadPixels".equals(method.getName()) || method.getParameterCount() != 7) {
                        continue;
                    }
                    method.invoke(null, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
                    read = true;
                    break;
                }
            }
            if (framebuffer != null) {
                call(framebuffer, "endRead");
            }
            return read ? buffer : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** RGBA（下→上）を BufferedImage（上→下）に起こす */
    private static BufferedImage toImage(ByteBuffer pixels, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        byte[] row = new byte[width * 4];
        for (int y = 0; y < height; y++) {
            pixels.position((height - 1 - y) * width * 4);
            pixels.get(row);
            for (int x = 0; x < width; x++) {
                int r = row[x * 4] & 0xFF;
                int g = row[x * 4 + 1] & 0xFF;
                int b = row[x * 4 + 2] & 0xFF;
                image.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static BufferedImage downscale(BufferedImage source, int maxWidth) {
        int width = Math.max(1, maxWidth);
        int height = Math.max(1, source.getHeight() * width / source.getWidth());
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static byte[] encode(BufferedImage image, McsaConfig config) {
        return encode(image, config, false);
    }

    private static byte[] encode(BufferedImage image, McsaConfig config, boolean forceJpeg) {
        boolean jpeg = forceJpeg || "JPEG".equalsIgnoreCase(config.captureFormat);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
            if (!jpeg) {
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            }
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            }
            ImageWriter writer = writers.next();
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(Math.min(1.0f, Math.max(0.2f, config.captureQuality)));
                }
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    // ------------------------------------------------------------- リフレクション

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intValue(Object target, String... candidates) {
        for (String candidate : candidates) {
            try {
                Object value = target.getClass().getMethod(candidate).invoke(target);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable ignored) {
                // メソッドが無い → 次の候補
            }
            try {
                Field field = target.getClass().getField(candidate);
                Object value = field.get(target);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable ignored) {
                // フィールドも無い → 次の候補
            }
        }
        return -1;
    }
}
