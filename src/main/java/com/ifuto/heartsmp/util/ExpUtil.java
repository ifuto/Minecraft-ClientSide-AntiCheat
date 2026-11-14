package com.ifuto.heartsmp.util;

import org.bukkit.entity.Player;

/** 経験値操作のヘルパ。 */
public final class ExpUtil {

    private ExpUtil() {
    }

    /** 総経験値を指定値に合わせます (レベル / バーも再計算)。 */
    public static void setTotal(Player player, int total) {
        int safe = Math.max(0, total);
        player.setLevel(0);
        player.setExp(0.0F);
        player.setTotalExperience(0);
        if (safe > 0) {
            player.giveExp(safe);
        }
    }
}
