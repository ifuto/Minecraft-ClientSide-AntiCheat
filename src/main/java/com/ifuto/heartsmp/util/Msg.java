package com.ifuto.heartsmp.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.StyleBuilderApplicable;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage ベースのメッセージユーティリティ。
 *
 * <p>テンプレート中の {@code %key%} は内部的に {@code <ph:key>} に変換され、
 * タグリゾルバ経由で差し込まれます。文字列プレースホルダは周囲のタグ
 * (色・太字など)を継承し、Component プレースホルダは自身のスタイルで描画されます。</p>
 */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    public static MiniMessage miniMessage() {
        return MM;
    }

    /** 素の MiniMessage 文字列を Component に変換します。 */
    public static Component parse(String template) {
        if (template == null) {
            return Component.empty();
        }
        return MM.deserialize(template);
    }

    /** 文字列ペア (key, value, key, value, ...) を差し込んでパースします。 */
    public static Component build(String template, String... pairs) {
        return build(template, java.util.Collections.emptyMap(), pairs);
    }

    /** Component プレースホルダを差し込んでパースします。 */
    public static Component build(String template, Map<String, Component> components) {
        return build(template, components, new String[0]);
    }

    /** 文字列プレースホルダと Component プレースホルダをまとめて差し込みます。 */
    public static Component build(String template, Map<String, Component> components, String... pairs) {
        if (template == null) {
            return Component.empty();
        }
        Map<String, String> strings = new LinkedHashMap<>();
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                strings.put(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
            }
        }
        Map<String, Component> comps = new LinkedHashMap<>();
        if (components != null) {
            for (Map.Entry<String, Component> e : components.entrySet()) {
                if (e.getValue() != null) {
                    comps.put(e.getKey(), e.getValue());
                }
            }
        }

        String prepared = template;
        for (String key : strings.keySet()) {
            prepared = replaceToken(prepared, key);
        }
        for (String key : comps.keySet()) {
            prepared = replaceToken(prepared, key);
        }

        TagResolver resolver = TagResolver.resolver(PH_NAMESPACE, (args, context) -> {
            String raw;
            try {
                raw = args.popOr("name").value();
            } catch (RuntimeException ex) {
                return Tag.selfClosingInserting(Component.empty());
            }
            String name = raw.toLowerCase(Locale.ROOT);
            Component component = comps.get(name);
            if (component != null) {
                return Tag.selfClosingInserting(component);
            }
            for (Map.Entry<String, Component> e : comps.entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                    return Tag.selfClosingInserting(e.getValue());
                }
            }
            String text = null;
            for (Map.Entry<String, String> e : strings.entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                    text = e.getValue();
                    break;
                }
            }
            if (text == null) {
                return Tag.selfClosingInserting(Component.text("%" + raw + "%"));
            }
            return Tag.preProcessParsed(escape(text));
        });

        return MM.deserialize(prepared, resolver);
    }

    private static final String PH_NAMESPACE = "ph";

    private static String replaceToken(String template, String key) {
        String token = "%" + key + "%";
        if (!template.contains(token)) {
            return template;
        }
        return template.replace(token, "<" + PH_NAMESPACE + ":" + key.toLowerCase(Locale.ROOT) + ">");
    }

    /** MiniMessage のタグとして解釈されないようにエスケープします。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** {@code #rrggbb} / {@code <#rrggbb>} を TextColor に変換します。失敗時は白。 */
    public static TextColor hex(String value) {
        if (value == null) {
            return NamedTextColor.WHITE;
        }
        TextColor color = TextColor.fromHexString(value.trim().replace("<", "").replace(">", ""));
        return color == null ? NamedTextColor.WHITE : color;
    }

    /** 任意個のスタイルをまとめたスタイルタグを作ります。 */
    public static Tag styleTag(StyleBuilderApplicable... style) {
        return Tag.styling(style);
    }
}
