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
 * <p><b>実装上の注意（build ≤31 の事故の本質）</b>:
 * <ul>
 * <li>1.21.11 から {@code Screen} の {@code client} / {@code textRenderer} / {@code executor} は
 *     <b>final</b> になり、新コンストラクタ {@code Screen(MinecraftClient, TextRenderer, Text)}
 *     で渡すのが正しい作法（バニラと移行済み OSS は全部こちら）。
 *     旧 {@code Screen(Text)} で作るとこれらが <b>null のまま</b> になり、
 *     {@code renderBackground} が {@code this.client} 参照で NPE → 描画が毎フレーム中断、
 *     「背景だけで何も出ない」状態になる（build 26〜31 の実態）。
 * <li>1.21.2+ のテキスト描画は色を完全な ARGB として解釈する。アルファ字节のない色は
 *     完全透明になる（build ≤30 で文字が見えなかったもう一つの原因）。
 * <li>背景の描画と文字の描画は別々に try/catch する（背景で例外が出ても文字は出す）。
 * </ul>
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
        // 1.21.11: Screen は client / textRenderer / executor をコンストラクタで受け取る。
        // 旧 super(Text) だと null のまま残り、renderBackground が NPE で何も描けない。
        // この画面はタイトル画面表示後（ロード完了後）にしか作らないので、
        // getInstance() と textRenderer は必ず初期化済み。
        super(MinecraftClient.getInstance(), MinecraftClient.getInstance().textRenderer,
                Text.literal("Better NArena - Data Privacy and Incident Prevention Guidelines"));
        McsaClient.LOGGER.info("[MCSA] ConsentScreen を生成しました（新 ctor 使用）");
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
     * 画面のフォント。新 ctor を使っていれば null にならないが、
     * 念のためクライアントのフォントへフォールバックする。
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
        // 背景と文字は別々に守る（背景の失敗で文字まで消えたのが build 31 までの事故）
        try {
            this.renderBackground(context, mouseX, mouseY, delta);
        } catch (Throwable t) {
            logRenderErrorOnce("背景", t);
        }
        try {
            renderText(context);
        } catch (Throwable t) {
            logRenderErrorOnce("文字", t);
        }
        try {
            super.render(context, mouseX, mouseY, delta);
        } catch (Throwable t) {
            logRenderErrorOnce("ボタン", t);
        }
    }

    private void logRenderErrorOnce(String part, Throwable t) {
        if (!renderErrorLogged) {
            renderErrorLogged = true;
            McsaClient.LOGGER.error("[MCSA] 同意画面の描画でエラー（{}）: {}", part, t.toString(), t);
        }
    }

    private void renderText(DrawContext context) {
        TextRenderer fonts = fonts();
        if (fonts == null) {
            // フォントが全く取れない。ここは諦めるしかない（背景とボタンは出る）
            McsaClient.LOGGER.error("[MCSA] フォントが取得できないため告知文を描画できません");
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
