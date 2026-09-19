package dev.ifuto.mcsa.client.integrity;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 検知結果（finding）の入れ物。
 *
 * <p>各プローブは「何が引っかかったか」を {@code CODE:詳細} の 1 行文字列として積むだけで、
 * 判定（黒か白か、Kick するか）は行わない。判定はサーバー側
 * {@code dev.ifuto.mcsa.server.policy.InjectionPolicy} が行う。
 *
 * <p>同じことをクライアントで判定してしまうと、MOD を書き換えた相手に
 * 「検知しなかった」という偽の申告をされるだけなので、生の観測結果を送る。
 */
public final class Findings {

    /** 1 レポートあたりの上限（意図的に溢れさせてレポートを肥大化させないため） */
    public static final int MAX_ITEMS = 128;
    /** 1 項目の最大長 */
    public static final int MAX_ITEM_LENGTH = 256;

    private final List<String> items = new ArrayList<>();
    private int dropped;

    public void add(String code, String detail) {
        if (code == null || code.isEmpty()) {
            return;
        }
        String line = detail == null || detail.isEmpty() ? code : code + ":" + detail;
        add(line);
    }

    public void add(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.length() > MAX_ITEM_LENGTH ? line.substring(0, MAX_ITEM_LENGTH) : line;
        synchronized (items) {
            if (items.size() >= MAX_ITEMS) {
                dropped++;
                return;
            }
            if (!items.contains(trimmed)) {
                items.add(trimmed);
            }
        }
    }

    public int size() {
        synchronized (items) {
            return items.size();
        }
    }

    /** 上限に達して捨てた件数（＝観測が溢れたことの証拠） */
    public int dropped() {
        synchronized (items) {
            return dropped;
        }
    }

    public List<String> list() {
        synchronized (items) {
            return Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    public JsonArray toJson() {
        JsonArray array = new JsonArray();
        synchronized (items) {
            for (String item : items) {
                array.add(item);
            }
        }
        return array;
    }

    /** {@code CODE:detail} の先頭部分だけを取り出す（集計用） */
    public static String code(String line) {
        if (line == null) {
            return "";
        }
        int at = line.indexOf(':');
        return at < 0 ? line : line.substring(0, at);
    }

    public static String detail(String line) {
        if (line == null) {
            return "";
        }
        int at = line.indexOf(':');
        return at < 0 ? "" : line.substring(at + 1);
    }
}
