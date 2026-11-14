package com.ifuto.heartsmp.player;

import java.util.Locale;

import net.kyori.adventure.text.format.TextColor;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.util.Msg;

/** プレイヤーランク。 */
public enum Rank {

    DEFAULT("default", 0),
    VIP("vip", 1),
    VIP_PLUS("vip-plus", 2);

    private final String configKey;
    private final int weight;

    Rank(String configKey, int weight) {
        this.configKey = configKey;
        this.weight = weight;
    }

    public String configKey() {
        return configKey;
    }

    public int weight() {
        return weight;
    }

    public boolean atLeast(Rank other) {
        return weight >= other.weight;
    }

    public static Rank parse(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT).replace("_", "-").replace(" ", "");
        return switch (normalized) {
            case "default", "none", "player" -> DEFAULT;
            case "vip" -> VIP;
            case "vip+", "vip-plus", "vipplus" -> VIP_PLUS;
            default -> null;
        };
    }

    public String display(HeartSMP plugin) {
        return plugin.getConfig().getString("ranks." + configKey + ".display", name());
    }

    public TextColor nameColor(HeartSMP plugin) {
        return Msg.hex(plugin.getConfig().getString("ranks." + configKey + ".name-color", "<#ffffff>"));
    }

    /** テンプレートに埋め込むための {@code <#rrggbb>} 形式の色。 */
    public String nameColorTag(HeartSMP plugin) {
        String raw = plugin.getConfig().getString("ranks." + configKey + ".name-color", "<#ffffff>");
        if (raw == null) {
            return "<#ffffff>";
        }
        return raw.startsWith("<") ? raw : "<" + raw + ">";
    }
}
