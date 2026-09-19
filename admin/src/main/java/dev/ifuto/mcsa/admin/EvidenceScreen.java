package dev.ifuto.mcsa.admin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 受信した証拠（画面）を OP のゲーム内に表示するビューア。
 *
 * <p>受信した PNG/JPEG を {@link NativeImage} に読み込んで
 * {@link NativeImageBackedTexture} として登録し、そのまま描く。
 * 画面サイズに合わせて縮小するので、フル HD の画面でも収まる。
 *
 * <p>描画は「パイプライン指定の {@code drawTexture}」を優先し、
 * 見つからなければ {@code drawTexturedQuad} に落とす。
 * パイプライン定数（{@code RenderPipelines.GUI_TEXTURED}）はバージョンで
 * 名前や場所が変わるため、そこはリフレクションで探す
 * （どちらも見つからなければメタ情報だけ表示して OS のビューアに任せる）。
 */
public final class EvidenceScreen extends Screen {

    /** 一度見つけた描画メソッドを覚えておく（毎フレーム探し直さない） */
    private static Method drawMethod;
    private static Object pipeline;
    private static boolean drawLookupDone;

    private final Path file;
    private final int bytes;

    private Identifier textureId;
    private int imageWidth;
    private int imageHeight;
    private String error;

    public EvidenceScreen(Path file, int bytes) {
        super(Text.literal("Better NArena - Evidence"));
        this.file = file;
        this.bytes = bytes;
    }

    @Override
    protected void init() {
        loadTexture();
        int buttonWidth = 120;
        int gap = 8;
        int total = buttonWidth * 3 + gap * 2;
        int y = this.height - 26;
        int x = (this.width - total) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Open image"), button -> EvidenceReceiver.openInOs(file))
                .dimensions(x, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Open folder"), button -> EvidenceReceiver.openFolder())
                .dimensions(x + buttonWidth + gap, y, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(x + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
    }

    /** 保存済みの画像をテクスチャとして登録する */
    private void loadTexture() {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")
                && !name.endsWith(".bmp") && !name.endsWith(".tga")) {
            error = "画像ではありません: " + file.getFileName();
            return;
        }
        try {
            NativeImage image = NativeImage.read(Files.readAllBytes(file));
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
            String id = file.getFileName().toString().replaceAll("[^a-z0-9/._-]", "_").toLowerCase(Locale.ROOT);
            if (id.startsWith(".") || id.isEmpty()) {
                id = "shot.png";
            }
            textureId = Identifier.of(McsaAdmin.MOD_ID, "evidence/" + id);
            // 1.21.11 の NativeImageBackedTexture は (Supplier<String>, NativeImage)
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "mcsa-evidence", image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(textureId, texture);
        } catch (Throwable t) {
            textureId = null;
            error = "画像を表示できませんでした（" + t.getClass().getSimpleName() + "）";
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xF0101018);
        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, "Better NArena - Evidence", centerX, 6, 0xFFD060);

        if (textureId != null && imageWidth > 0 && imageHeight > 0) {
            int maxWidth = this.width - 24;
            int maxHeight = this.height - 62;
            double scale = Math.min((double) maxWidth / imageWidth, (double) maxHeight / imageHeight);
            int width = Math.max(1, (int) (imageWidth * scale));
            int height = Math.max(1, (int) (imageHeight * scale));
            int x = (this.width - width) / 2;
            int y = 18;
            draw(context, x, y, width, height);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    error == null ? "表示できません" : error, centerX, this.height / 2 - 24, 0xFF5555);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "Open image / Open folder で開いてください", centerX, this.height / 2 - 10, 0xC0C0C0);
        }

        int bottom = this.height - 34;
        context.drawCenteredTextWithShadow(this.textRenderer,
                file.getFileName() + "  (" + bytes + " bytes"
                        + (imageWidth > 0 ? ", " + imageWidth + "x" + imageHeight : "") + ")",
                centerX, bottom - 10, 0xC0C0C0);
        context.drawCenteredTextWithShadow(this.textRenderer, file.toAbsolutePath().toString(),
                centerX, bottom, 0x707070);
        super.render(context, mouseX, mouseY, delta);
    }

    /**
     * 画像を描く。
     *
     * <ol>
     *   <li>{@code drawTexture(RenderPipeline, Identifier, x, y, u, v, w, h, texW, texH)}
     *       … スキンなどの動的テクスチャと同じ経路。パイプラインはリフレクションで探す</li>
     *   <li>{@code drawTexturedQuad(Identifier, x1, y1, x2, y2, u1, v1, u2, v2)}
     *       … パイプライン不要の公開メソッド（1.21.11 で確認済み）</li>
     * </ol>
     */
    private void draw(DrawContext context, int x, int y, int width, int height) {
        Method method = drawMethod();
        if (method != null && pipeline != null) {
            try {
                method.invoke(context, pipeline, textureId, x, y, 0.0f, 0.0f,
                        width, height, imageWidth, imageHeight);
                return;
            } catch (Throwable ignored) {
                // 下の drawTexturedQuad に落とす
            }
        }
        try {
            context.drawTexturedQuad(textureId, x, y, x + width, y + height, 0.0f, 0.0f, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 描けなければ枠だけ（メタ情報は下に出る）
            context.fill(x, y, x + width, y + height, 0x40FFFFFF);
        }
    }

    /** パイプライン定数と drawTexture を一度だけ探す */
    private static synchronized Method drawMethod() {
        if (drawLookupDone) {
            return drawMethod;
        }
        drawLookupDone = true;
        try {
            Class<?> pipelines = Class.forName("net.minecraft.client.render.RenderPipelines");
            pipeline = pipelines.getField("GUI_TEXTURED").get(null);
        } catch (Throwable ignored) {
            pipeline = null;
        }
        if (pipeline == null) {
            return null;
        }
        try {
            for (Method method : DrawContext.class.getMethods()) {
                if (!"drawTexture".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 10
                        || !types[0].isInstance(pipeline)
                        || types[1] != Identifier.class
                        || types[2] != int.class || types[3] != int.class
                        || types[4] != float.class || types[5] != float.class
                        || types[6] != int.class || types[7] != int.class
                        || types[8] != int.class || types[9] != int.class) {
                    continue;
                }
                drawMethod = method;
                return drawMethod;
            }
        } catch (Throwable ignored) {
            drawMethod = null;
        }
        return null;
    }

    @Override
    public void close() {
        if (textureId != null) {
            try {
                // 閉じるときにテクスチャを解放する（NativeImage も一緒に閉じられる）
                MinecraftClient.getInstance().getTextureManager().destroyTexture(textureId);
            } catch (Throwable ignored) {
                // 解放に失敗しても実害はない
            }
            textureId = null;
        }
        super.close();
    }
}
