package com.ifuto.heartsmp.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PersistentDataContainer のキー定義。 */
public final class PdcKeys {

    /** 死亡ログの ID (その戦闘で落ちたアイテムに付与) */
    public static NamespacedKey DEATH_ID;
    /** 落としたプレイヤーの UUID */
    public static NamespacedKey DROP_OWNER;
    /** 落とした時刻 (epoch millis) */
    public static NamespacedKey DROP_TIME;
    /** ハートのネザースター */
    public static NamespacedKey HEART_STAR;
    /** 配送 GUI 内の目印 */
    public static NamespacedKey GUI_TAG;

    private PdcKeys() {
    }

    public static void init(Plugin plugin) {
        DEATH_ID = new NamespacedKey(plugin, "death_id");
        DROP_OWNER = new NamespacedKey(plugin, "drop_owner");
        DROP_TIME = new NamespacedKey(plugin, "drop_time");
        HEART_STAR = new NamespacedKey(plugin, "heart_star");
        GUI_TAG = new NamespacedKey(plugin, "gui_tag");
    }
}
