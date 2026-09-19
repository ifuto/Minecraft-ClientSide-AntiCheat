package dev.ifuto.mcsa.admin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;

/**
 * 受信した証拠のビューア画面（OP 用）。
 *
 * <p>ゲーム内に画像そのものを描画しない。理由は単純で、テクスチャ描画 API は
 * バージョンごとにころころ変わり、壊れたら MOD ごと動かないから。
 * ここでは証拠の所在（パス・サイズ）を出して、
 * <b>OS の既定ビューアで開く</b>／<b>フォルダを開く</b>ボタンを並べる。
 *
 * <p>保存先は {@code .minecraft/mcsa-evidence/}（設定 {@code saveFolder} で変更できる）。
 * サーバー側の原本（{@code plugins/MCSA/evidence/}）と監査ログはそのまま残る。
 */
public final class EvidenceScreen extends Screen {

    private final Path file;
    private final int bytes;

    public EvidenceScreen(Path file, int bytes) {
        super(Text.literal("MCSA Evidence"));
        this.file = file;
        this.bytes = bytes;
    }

    @Override
    protected void init() {
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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xF0101018);
        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, "MCSA Evidence", centerX, 20, 0xFFD060);
        context.drawCenteredTextWithShadow(this.textRenderer, file.getFileName().toString(),
                centerX, this.height / 2 - 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, bytes + " bytes",
                centerX, this.height / 2 - 8, 0xA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, file.toAbsolutePath().toString(),
                centerX, this.height / 2 + 8, 0x70A0FF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                "Open image で OS のビューアが開きます", centerX, this.height / 2 + 24, 0x808080);
        super.render(context, mouseX, mouseY, delta);
    }
}
