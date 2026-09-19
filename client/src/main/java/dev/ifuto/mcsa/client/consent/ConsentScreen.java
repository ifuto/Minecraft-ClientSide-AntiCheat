package dev.ifuto.mcsa.client.consent;

import dev.ifuto.mcsa.client.McsaClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * プライバシィ告知の画面（Minecraft 起動直後に出る）。
 *
 * <p>ESC では閉じられない。選べるのは <b>I Agree</b> か <b>Decline and Quit</b> だけで、
 * 拒否すると Minecraft 自体が終了する。同意する以外にプレイを続ける方法は
 * 「この MOD を抜く」しかない（抜けばサーバー側の導入必須チェックで弾かれる）。
 *
 * <p>文面は {@code config/mcsa/privacy-notice.txt}（初回起動時に jar から書き出す）。
 * 編集すれば指紋が変わるので、次に起動したときに同意を取り直す。
 */
public final class ConsentScreen extends Screen {

    private static final int MARGIN = 24;
    private static final int LINE_HEIGHT = 11;
    private static final int WRAP_WIDTH = 118;

    private final List<String> lines;
    private int scroll;
    private int maxScroll;

    public ConsentScreen() {
        super(Text.literal("MCSA - Data Privacy and Incident Prevention Guidelines"));
        this.lines = wrap(ConsentManager.text(), WRAP_WIDTH);
    }

    /** 起動時に一度だけ開く */
    public static void openIfRequired(MinecraftClient client) {
        if (client == null || !ConsentManager.needsConsent()) {
            return;
        }
        client.setScreen(new ConsentScreen());
    }

    @Override
    protected void init() {
        int buttonWidth = 170;
        int y = this.height - 30;
        int centerX = this.width / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("I Agree"), button -> agree())
                .dimensions(centerX - buttonWidth - 4, y, buttonWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Decline and Quit"), button -> decline())
                .dimensions(centerX + 4, y, buttonWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u25b2"), button -> scroll = clamp(scroll - 3))
                .dimensions(this.width - 40, 40, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u25bc"), button -> scroll = clamp(scroll + 3))
                .dimensions(this.width - 40, 64, 20, 20).build());
        int usable = Math.max(1, this.height - 60);
        int visible = usable / LINE_HEIGHT;
        maxScroll = Math.max(0, lines.size() - visible);
        scroll = Math.min(scroll, maxScroll);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xF0101018);
        int centerX = this.width / 2;

        context.drawCenteredTextWithShadow(this.textRenderer,
                "Data Privacy and Incident Prevention Guidelines", centerX, 8, 0xFFD060);
        context.drawCenteredTextWithShadow(this.textRenderer,
                "You must agree before you can play. Declining closes Minecraft.",
                centerX, 20, 0x909090);

        int y = 36 - scroll * LINE_HEIGHT;
        int color = 0xC8C8C8;
        for (String line : lines) {
            if (y >= 32 && y < this.height - 40) {
                if (line.isEmpty()) {
                    // 空行
                } else if (Character.isDigit(line.charAt(0)) && line.length() > 2 && line.charAt(1) == '.') {
                    context.drawTextWithShadow(this.textRenderer, line, MARGIN, y, 0xFFFFFF);
                } else {
                    context.drawTextWithShadow(this.textRenderer, line, MARGIN, y, color);
                }
            }
            y += LINE_HEIGHT;
        }

        if (maxScroll > 0) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    "(scroll: " + (scroll + 1) + "/" + (maxScroll + 1) + ")",
                    centerX, this.height - 52, 0x707070);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(maxScroll, value));
    }

    /** ESC で閉じられない（同意か拒否のどちらかを選ぶ） */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void close() {
        // 何もしない（閉じる＝同意なしでプレイ継続、にはさせない）
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void agree() {
        ConsentManager.accept();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.setScreen(null);
        }
    }

    private void decline() {
        ConsentManager.decline();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.setScreen(null);
        }
        shutdown();
    }

    /**
     * Minecraft を終了させる。
     *
     * <p>終了メソッド名はバージョンで変わることがあるので、
     * {@code scheduleStop} → {@code stop} → {@code System.exit} の順に試す。
     */
    private static void shutdown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            for (String name : new String[]{"scheduleStop", "stop"}) {
                try {
                    Method method = client.getClass().getMethod(name);
                    method.invoke(client);
                    return;
                } catch (Throwable ignored) {
                    // 次の候補
                }
            }
        }
        McsaClient.LOGGER.warn("[MCSA] 正常終了のメソッドが見つからないためプロセスを終了します");
        System.exit(0);
    }

    /** 素朴なワードラップ（MC の API に依存しない） */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (paragraph.length() <= width) {
                out.add(paragraph);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (current.length() + word.length() + 1 > width && current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(word);
                while (current.length() > width) {
                    out.add(current.substring(0, width));
                    current.delete(0, width);
                }
            }
            if (current.length() > 0) {
                out.add(current.toString());
            }
        }
        return out;
    }
}
