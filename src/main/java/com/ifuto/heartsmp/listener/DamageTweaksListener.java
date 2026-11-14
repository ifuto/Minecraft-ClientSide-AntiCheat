package com.ifuto.heartsmp.listener;

import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MinecartTNT;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import com.ifuto.heartsmp.HeartSMP;

/**
 * ダメージ調整。
 * <ul>
 *   <li>TNT トロッコのダメージを 80% に</li>
 *   <li>メイスの防具貫通 (Breach) を 1 レベルごとに 4% 軽減</li>
 * </ul>
 */
public final class DamageTweaksListener implements Listener {

    private static final double VANILLA_BREACH_PER_LEVEL = 0.15D;

    private final HeartSMP plugin;

    public DamageTweaksListener(HeartSMP plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // TNT トロッコ
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && event.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }
        if (!(directEntity(event) instanceof MinecartTNT)) {
            return;
        }
        event.setDamage(event.getDamage() * plugin.settings().tntMinecartMultiplier);
    }

    private Entity directEntity(EntityDamageEvent event) {
        try {
            DamageSource source = event.getDamageSource();
            if (source != null) {
                Entity direct = source.getDirectEntity();
                if (direct != null) {
                    return direct;
                }
                Entity causing = source.getCausingEntity();
                if (causing != null) {
                    return causing;
                }
            }
        } catch (RuntimeException ignored) {
            // フォールバックへ
        }
        for (Entity nearby : event.getEntity().getNearbyEntities(6.0D, 6.0D, 6.0D)) {
            if (nearby instanceof MinecartTNT) {
                return nearby;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // メイス (Breach)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null) {
            return;
        }
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        int breachLevel = enchantLevel(weapon, "breach");
        if (breachLevel <= 0) {
            return;
        }
        double armor = attributeValue(target, Attribute.ARMOR);
        double toughness = attributeValue(target, Attribute.ARMOR_TOUGHNESS);
        if (armor <= 0.0D) {
            return;
        }
        double reduction = plugin.settings().maceBreachReductionPerLevel;
        double vanilla = Math.min(1.0D, VANILLA_BREACH_PER_LEVEL * breachLevel);
        double reduced = Math.max(0.0D, vanilla - reduction * breachLevel);
        if (reduced >= vanilla) {
            return;
        }
        double damage = event.getDamage();
        double withoutOurTweak = armorFactor(armor, toughness, damage, vanilla);
        if (withoutOurTweak <= 0.0D) {
            return;
        }
        double withTweak = armorFactor(armor, toughness, damage, reduced);
        event.setDamage(damage * (withTweak / withoutOurTweak));
    }

    /**
     * バニラの防具ダメージ軽減率。
     * {@code 1 - min(20, max(armor/5, armor - damage/(2+toughness/4))) / 25}
     */
    private double armorFactor(double armor, double toughness, double damage, double penetration) {
        double effectiveArmor = armor * (1.0D - penetration);
        double effectiveToughness = toughness * (1.0D - penetration);
        double value = Math.min(20.0D, Math.max(effectiveArmor / 5.0D,
                effectiveArmor - damage / (2.0D + effectiveToughness / 4.0D)));
        return 1.0D - Math.max(0.0D, value) / 25.0D;
    }

    private double attributeValue(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }

    private int enchantLevel(ItemStack item, String key) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            NamespacedKey namespacedKey = entry.getKey().getKey();
            if (namespacedKey != null && namespacedKey.getKey().equals(key)) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player shooter) {
                return shooter;
            }
        }
        return null;
    }
}
