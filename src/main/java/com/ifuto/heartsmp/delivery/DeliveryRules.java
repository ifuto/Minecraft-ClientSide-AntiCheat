package com.ifuto.heartsmp.delivery;

import java.util.Locale;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** いふと配送の規約チェック。 */
public final class DeliveryRules {

    /** デバフ系ポーションと判定するエフェクト ID。 */
    private static final Set<String> DEBUFF_KEYS = Set.of(
            "poison", "slowness", "weakness", "instant_damage", "harming", "mining_fatigue",
            "nausea", "blindness", "hunger", "levitation", "unluck", "bad_omen", "wither",
            "darkness", "infested", "raid_omen", "trial_omen", "oozing", "weaving", "wind_charged");

    private DeliveryRules() {
    }

    public static boolean isPotionLike(Material material) {
        return material == Material.POTION || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION || material == Material.TIPPED_ARROW;
    }

    /** デバフ系ポーション (またはデバフ効果を持つポーション) かどうか。 */
    public static boolean isDebuffPotion(ItemStack item) {
        if (item == null || !isPotionLike(item.getType())) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof PotionMeta potion)) {
            return false;
        }
        try {
            if (potion.hasCustomEffects()) {
                for (PotionEffect effect : potion.getCustomEffects()) {
                    if (isDebuff(effect.getType())) {
                        return true;
                    }
                }
            }
            PotionType base = potion.getBasePotionType();
            if (base != null) {
                for (PotionEffect effect : base.getPotionEffects()) {
                    if (isDebuff(effect.getType())) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException ex) {
            return false;
        }
        return false;
    }

    private static boolean isDebuff(PotionEffectType type) {
        if (type == null) {
            return false;
        }
        NamespacedKey key = type.getKey();
        String name = key == null ? type.toString() : key.getKey();
        return DEBUFF_KEYS.contains(name.toLowerCase(Locale.ROOT));
    }
}
