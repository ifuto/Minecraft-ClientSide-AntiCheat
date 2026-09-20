package dev.ifuto.mcsa.client.consent;

import dev.ifuto.mcsa.client.McsaClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * プライバシィ告知の画面（Minecraft 起動直後、ロード完了後に出る）。
 *
 * <p>ESC では閉じられない。選べるのは <b>I Agree</b> か <b>Decline and Quit</b> だけで、
 * 拒否すると Minecraft 自体が終了する。同意する以外にプレイを続ける方法は
 * 「この MOD を抜く」しかない（抜けばサーバー側の導入必須チェックで弾かれる）。
 *
 * <p>文面は {@code config/mcsa/privacy-notice.txt}（初回起動時に jar から書き出す）。
 * 長いのでページ分割して表示する（スクロール状態を持たない分だけ壊れにくい）。
 *
 * <p>描画は 1 行も例外を外に漏らさない（描画例外でゲームが落ちないように）。
 * 失敗したら最初の 1 回だけログに出す。
 */
public final class ConsentScreen extends Screen {

    private static final int MARGIN = 24;
    private static final int LINE_HEIGHT = 11;
    private static final int WRAP_WIDTH = 110;

    private final List<String> lines;
    private int page;
    private int pageCount;

    /** 描画エラーは最初の 1 回だけログに出す（毎フレーム吐かないように） */
    private static boolean renderErrorLogged;

    public ConsentScreen() {
        super(Text.literal("Better NArena - Data Privacy and Incident Prevention Guidelines"));
        this.lines = wrap(ConsentManager.text(), WRAP_WIDTH);
        McsaClient.LOGGER.info("[MCSA] ConsentScreen を生成しました（行数={}）", lines.size());
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("I Agree"), button -> agree())
                .dimensions(centerX - 165, y, 110, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Decline and Quit"), button -> decline())
                .dimensions(centerX - 50, y, 130, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("< Prev"), button -> turnPage(-1))
                .dimensions(centerX + 85, y, 65, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), button -> turnPage(1))
                .dimensions(centerX + 153, y, 65, 20).build());
        int visible = visibleLines();
        pageCount = Math.max(1, (lines.size() + visible - 1) / visible);
        page = Math.min(page, pageCount - 1);
        McsaClient.LOGGER.info("[MCSA] ConsentScreen init（w={}, h={}, 行数={}, ページ数={}）",
                this.width, this.height, lines.size(), pageCount);
    }

    private int visibleLines() {
        return Math.max(1, (this.height - 76) / LINE_HEIGHT);
    }

    private void turnPage(int delta) {
        page = Math.max(0, Math.min(pageCount - 1, page + delta));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        try {
            renderBody(context, mouseX, mouseY, delta);
        } catch (Throwable t) {
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                McsaClient.LOGGER.error("[MCSA] 同意画面の描画でエラー: {}", t.toString(), t);
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderBody(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        TextRenderer fonts = this.getTextRenderer();
        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(fonts,
                "Data Privacy and Incident Prevention Guidelines", centerX, 8, 0xFFD060);
        context.drawCenteredTextWithShadow(fonts,
                "You must agree before you can play. Declining closes Minecraft.",
                centerX, 20, 0x909090);

        int visible = visibleLines();
        int start = page * visible;
        int y = 36;
        for (int i = start; i < lines.size() && i < start + visible; i++) {
            String line = lines.get(i);
            if (!line.isEmpty()) {
                context.drawTextWithShadow(fonts, line, MARGIN, y, 0xC8C8C8);
            }
            y += LINE_HEIGHT;
        }
        if (pageCount > 1) {
            context.drawCenteredTextWithShadow(fonts,
                    "(page " + (page + 1) + " / " + pageCount + ")",
                    centerX, this.height - 42, 0x707070);
        }
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
        McsaClient.LOGGER.info("[MCSA] 同意されました。タイトル画面に戻ります");
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            // null だとボタン無しのパノラマで止まってしまうので、タイトル画面へ戻す
            client.setScreen(new TitleScreen());
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
