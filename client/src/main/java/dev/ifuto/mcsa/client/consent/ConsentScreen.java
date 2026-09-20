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
 * <p><b>描画の注意（1.21.2+）</b>: テキストの色は完全な ARGB として解釈される。
 * {@code 0xC8C8C8} のようにアルファ字节を持たない色は<strong>完全透明</strong>になり、
 * 「背景とボタンは出るが本文が一切見えない」状態になる（build ≤30 の事故）。
 * 必ず {@code 0xFF……} とアルファ付きで指定すること。
 * ボタン文言が見えていたのは {@code ButtonWidget} が内部でアルファを補完しているため。
 *
 * <p>描画は 1 行も例外を外に漏らさない（描画例外でゲームが落ちないように）。
 * 失敗したら最初の 1 回だけログに出す。
 */
public final class ConsentScreen extends Screen {

    private static final int MARGIN = 24;
    private static final int LINE_HEIGHT = 11;
    /** textRenderer が取れないときのフォールバック（文字数ベース）の折り返し幅 */
    private static final int FALLBACK_WRAP_CHARS = 60;

    private final List<String> lines = new ArrayList<>();
    private int page;
    private int pageCount;

    /** 描画エラーは最初の 1 回だけログに出す（毎フレーム吐かないように） */
    private static boolean renderErrorLogged;

    public ConsentScreen() {
        super(Text.literal("Better NArena - Data Privacy and Incident Prevention Guidelines"));
        McsaClient.LOGGER.info("[MCSA] ConsentScreen を生成しました");
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
        rewrap();
    }

    /**
     * 画面幅が分かってから実際のピクセル幅で折り返し直す。
     * （文字数ベースだと GUI スケール 3〜4 で右端が切れる。
     * リサイズで init が呼び直されるので、そのたびにやり直すのが正しい）
     */
    private void rewrap() {
        TextRenderer fonts = fonts();
        if (fonts != null) {
            int maxWidth = Math.max(80, this.width - MARGIN * 2);
            lines.clear();
            lines.addAll(wrapPixels(ConsentManager.text(), fonts, maxWidth));
        } else if (lines.isEmpty()) {
            // textRenderer が取れない非常時のみ。多少切れても文面は出る。
            lines.addAll(wrap(ConsentManager.text(), FALLBACK_WRAP_CHARS));
        }
        int visible = visibleLines();
        pageCount = Math.max(1, (lines.size() + visible - 1) / visible);
        page = Math.min(page, pageCount - 1);
        McsaClient.LOGGER.info("[MCSA] ConsentScreen init（w={}, h={}, 行数={}, ページ数={}, fonts={}）",
                this.width, this.height, lines.size(), pageCount, fonts != null ? "OK" : "null");
    }

    private int visibleLines() {
        return Math.max(1, (this.height - 76) / LINE_HEIGHT);
    }

    private void turnPage(int delta) {
        page = Math.max(0, Math.min(pageCount - 1, page + delta));
    }

    /**
     * 1.21.11 の Screen はコンストラクタで TextRenderer を受け取る。
     * 旧形式の super(Text) で生成した場合に備えて、クライアントのフォントへフォールバックする。
     * （ここが null だと drawTextWithShadow が NPE → try/catch に握り潰されて
     * 「文字が一切出ない」事故になるので、絶対に null を返さないようにする）
     */
    private TextRenderer fonts() {
        try {
            TextRenderer f = this.getTextRenderer();
            if (f != null) {
                return f;
            }
        } catch (Throwable ignored) {
            // フォールバックへ
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null ? client.textRenderer : null;
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
        TextRenderer fonts = fonts();
        if (fonts == null) {
            // 背景とボタンだけでも出す（ここで例外を投げると画面全体が真っ暗になる）
            return;
        }
        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(fonts,
                "Data Privacy and Incident Prevention Guidelines", centerX, 8, 0xFFFFD060);
        context.drawCenteredTextWithShadow(fonts,
                "You must agree before you can play. Declining closes Minecraft.",
                centerX, 20, 0xFF909090);

        int visible = visibleLines();
        int start = page * visible;
        int y = 36;
        for (int i = start; i < lines.size() && i < start + visible; i++) {
            String line = lines.get(i);
            if (!line.isEmpty()) {
                context.drawTextWithShadow(fonts, line, MARGIN, y, 0xFFC8C8C8);
            }
            y += LINE_HEIGHT;
        }
        if (pageCount > 1) {
            context.drawCenteredTextWithShadow(fonts,
                    "(page " + (page + 1) + " / " + pageCount + ")",
                    centerX, this.height - 42, 0xFF707070);
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

    /**
     * ピクセル幅でワードラップする（GUI スケールが大きくても右端で切れない）。
     * 1 単語だけで行幅を超えるときは文字単位で折る。
     */
    static List<String> wrapPixels(String text, TextRenderer fonts, int maxWidth) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (paragraph.isBlank()) {
                out.add("");
                continue;
            }
            if (fonts.getWidth(paragraph) <= maxWidth) {
                out.add(paragraph);
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (current.length() == 0 || fonts.getWidth(candidate) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                } else {
                    out.add(current.toString());
                    current.setLength(0);
                    current.append(word);
                }
                // 1 単語が maxWidth を超えるなら文字単位で折る
                while (fonts.getWidth(current.toString()) > maxWidth && current.length() > 1) {
                    int cut = current.length() - 1;
                    while (cut > 1 && fonts.getWidth(current.substring(0, cut)) > maxWidth) {
                        cut--;
                    }
                    out.add(current.substring(0, cut));
                    current.delete(0, cut);
                }
            }
            if (current.length() > 0) {
                out.add(current.toString());
            }
        }
        return out;
    }

    /** 素朴なワードラップ（textRenderer が使えないときのフォールバック） */
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
