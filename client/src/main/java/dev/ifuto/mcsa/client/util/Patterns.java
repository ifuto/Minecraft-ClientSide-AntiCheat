package dev.ifuto.mcsa.client.util;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 検知パターンの照合。
 *
 * <p>サーバーは {@code /ac probe add} で「探してほしい名前」をいつでも追加できる。
 * クライアント MOD を配り直さずに検知対象を増やすための仕組みで、書式は 3 つ。
 *
 * <ul>
 *   <li>{@code 完全一致} … {@code meteordevelopment.meteorclient.MeteorClient}</li>
 *   <li>{@code prefix:} … {@code prefix:me.rhys}（前方一致）</li>
 *   <li>{@code contains:} … {@code contains:baritone}（部分一致）</li>
 *   <li>{@code regex:} … {@code regex:^net\.wurst.*Client$}（正規表現）</li>
 * </ul>
 *
 * <p>大文字小文字は区別しない（チート側が名前を小文字化して逃げるのを防ぐ）。
 */
public final class Patterns {

    public static final String PREFIX = "prefix:";
    public static final String CONTAINS = "contains:";
    public static final String REGEX = "regex:";

    private Patterns() {
    }

    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        String p = pattern.trim();
        String v = value.trim();
        if (p.isEmpty() || v.isEmpty()) {
            return false;
        }
        String pl = p.toLowerCase(Locale.ROOT);
        String vl = v.toLowerCase(Locale.ROOT);
        if (pl.startsWith(PREFIX)) {
            return vl.startsWith(pl.substring(PREFIX.length()));
        }
        if (pl.startsWith(CONTAINS)) {
            return vl.contains(pl.substring(CONTAINS.length()));
        }
        if (pl.startsWith(REGEX)) {
            try {
                return Pattern.compile(p.substring(REGEX.length()), Pattern.CASE_INSENSITIVE).matcher(v).find();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        return pl.equals(vl);
    }

    /** パターンとして妥当な書式かどうか（サーバー側の入力チェックと同じ規則） */
    public static boolean isValid(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String p = pattern.trim();
        if (p.startsWith(REGEX)) {
            try {
                Pattern.compile(p.substring(REGEX.length()));
                return true;
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        return p.length() <= 512;
    }
}
