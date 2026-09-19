package dev.ifuto.mcsa.admin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.Button;
import net.minecraft.client.render.RenderPipelines;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 受信した証拠（画面）を OP のゲーム内に表示する画面。
 *
 * <p>受信した PNG をそのままテクスチャにして描くだけ。
 * 表示に失敗した場合はメタ情報だけを出し、OS のビューアで開けるようにする
 * （描画 API はバージョンで変わるので、そこは必ず動くようにしてある）。
 */
public final class EvidenceScreen extends Screen {

    private final Path file;
    private final int bytes;

    private Identifier textureId;
    private int imageWidth;
    private int imageHeight;
    private String error;

    public EvidenceScreen(Path file, int bytes) {
        super(Text.literal("MCSA Evidence"));
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
        addDrawableChild(Button.builder(Text.literal("Open image"), button -> EvidenceReceiver.openInOs(file))
                .dimensions(x, y, buttonWidth, 20).build());
        addDrawableChild(Button.builder(Text.literal("Open folder"), button -> EvidenceReceiver.openFolder())
                .dimensions(x + buttonWidth + gap, y, buttonWidth, 20).build());
        addDrawableChild(Button.builder(Text.literal("Close"), button -> close())
                .dimensions(x + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
    }

    private void loadTexture() {
        if (!file.getFileName().toString().toLowerCase().endsWith(".png")
                && !file.getFileName().toString().toLowerCase().endsWith(".jpg")
                && !file.getFileName().toString().toLowerCase().endsWith(".jpeg")) {
            error = "画像ではありません: " + file.getFileName();
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
            NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
            textureId = Identifier.of(McsaAdmin.MOD_ID,
                    "evidence/" + file.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]", "_"));
            MinecraftClient.getInstance().getTextureManager().registerTexture(textureId, texture);
        } catch (Throwable t) {
            textureId = null;
            error = "画像を表示できませんでした（" + t.getClass().getSimpleName() + "）";
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xF0101018);
        context.drawCenteredTextWithShadow(this.textRenderer, "MCSA Evidence", this.width / 2, 6, 0xFFD060);

        if (textureId != null && imageWidth > 0 && imageHeight > 0) {
            int maxWidth = this.width - 24;
            int maxHeight = this.height - 60;
            double scale = Math.min((double) maxWidth / imageWidth, (double) maxHeight / imageHeight);
            int width = Math.max(1, (int) (imageWidth * scale));
            int height = Math.max(1, (int) (imageHeight * scale));
            int x = (this.width - width) / 2;
            int y = 20;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, textureId, x, y, 0.0f, 0.0f,
                    width, height, imageWidth, imageHeight);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    error == null ? "表示できません" : error, this.width / 2, this.height / 2 - 20, 0xFF5555);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "Open image / Open folder で開いてください", this.width / 2, this.height / 2, 0xC0C0C0);
        }

        context.drawCenteredTextWithShadow(this.textRenderer,
                file.getFileName() + "  (" + bytes + " bytes)", this.width / 2, this.height - 42, 0xC0C0C0);
        context.drawCenteredTextWithShadow(this.textRenderer, file.toString(), this.width / 2, this.height - 32,
                0x808080);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (textureId != null) {
            try {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(textureId);
            } catch (Throwable ignored) {
                // 閉じるときに失敗しても実害はない
            }
            textureId = null;
        }
        super.close();
    }
}
