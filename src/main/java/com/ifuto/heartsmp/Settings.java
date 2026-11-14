package com.ifuto.heartsmp;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** config.yml の設定値を型付きで保持します。 */
public final class Settings {

    public final long combatMillis;
    public final long actionbarIntervalTicks;
    public final boolean combatBlockElytra;
    public final int heartsDefault;
    public final int heartsMin;
    public final int heartsMax;
    public final double logoutScatterRadius;
    public final boolean trackDrops;
    public final double tntMinecartMultiplier;
    public final double maceBreachReductionPerLevel;

    public final long tpaLifetimeMillis;
    public final long tpaResendCooldownMillis;
    public final long tpaPostTeleportCooldownMillis;
    public final long tpaAbortCooldownMillis;
    public final int tpaCountdownSeconds;
    public final double tpaMoveThreshold;

    public final int deliveryBaseCost;
    public final int deliveryFastCost;
    public final long deliveryTravelMillis;
    public final int deliveryMaxPending;
    public final int deliveryMaxBagages;
    public final Set<Material> deliveryBanned;

    public final long rtpNormalCooldownMillis;
    public final long rtpCaveCooldownMillis;
    public final long rtpBiomeCooldownMillis;
    public final int rtpMaxAttempts;
    public final int rtpNormalMinRadius;
    public final int rtpNormalMaxRadius;
    public final int rtpCaveMinYOffset;
    public final int rtpCaveMaxY;

    public final int rollbackMaxRecordsPerPlayer;

    public Settings(FileConfiguration config) {
        this.combatMillis = seconds(config, "settings.combat.duration-seconds", 30);
        this.actionbarIntervalTicks = Math.max(1L, config.getLong("settings.combat.actionbar-interval-ticks", 10L));
        this.combatBlockElytra = config.getBoolean("settings.combat.block-elytra", true);
        this.heartsDefault = config.getInt("settings.hearts.default", 10);
        this.heartsMin = config.getInt("settings.hearts.min", 1);
        this.heartsMax = config.getInt("settings.hearts.max", 30);
        this.logoutScatterRadius = config.getDouble("settings.death.logout-scatter-radius", 4.0D);
        this.trackDrops = config.getBoolean("settings.death.track-drops", true);
        this.tntMinecartMultiplier = config.getDouble("settings.damage.tnt-minecart-multiplier", 0.8D);
        this.maceBreachReductionPerLevel = config.getDouble("settings.damage.mace-breach-reduction-per-level", 0.04D);

        this.tpaLifetimeMillis = seconds(config, "settings.tpa.request-lifetime-seconds", 50);
        this.tpaResendCooldownMillis = seconds(config, "settings.tpa.resend-cooldown-seconds", 30);
        this.tpaPostTeleportCooldownMillis = seconds(config, "settings.tpa.post-teleport-cooldown-seconds", 300);
        this.tpaAbortCooldownMillis = seconds(config, "settings.tpa.abort-cooldown-seconds", 15);
        this.tpaCountdownSeconds = Math.max(1, config.getInt("settings.tpa.countdown-seconds", 3));
        this.tpaMoveThreshold = config.getDouble("settings.tpa.move-threshold", 0.3D);

        this.deliveryBaseCost = config.getInt("settings.delivery.base-cost", 3);
        this.deliveryFastCost = config.getInt("settings.delivery.fast-cost", 6);
        this.deliveryTravelMillis = seconds(config, "settings.delivery.travel-seconds", 120);
        this.deliveryMaxPending = config.getInt("settings.delivery.max-pending-per-sender", 3);
        this.deliveryMaxBagages = config.getInt("settings.delivery.max-bagages", 21);
        this.deliveryBanned = readMaterials(config, "settings.delivery.banned-materials");

        this.rtpNormalCooldownMillis = seconds(config, "settings.rtp.normal-cooldown-seconds", 40);
        this.rtpCaveCooldownMillis = seconds(config, "settings.rtp.cave-cooldown-seconds", 300);
        this.rtpBiomeCooldownMillis = seconds(config, "settings.rtp.biome-cooldown-seconds", 180);
        this.rtpMaxAttempts = config.getInt("settings.rtp.max-attempts", 60);
        this.rtpNormalMinRadius = config.getInt("settings.rtp.normal-min-radius", 300);
        this.rtpNormalMaxRadius = config.getInt("settings.rtp.normal-max-radius", 3000);
        this.rtpCaveMinYOffset = config.getInt("settings.rtp.cave-min-y-offset", 0);
        this.rtpCaveMaxY = config.getInt("settings.rtp.cave-max-y", 40);

        this.rollbackMaxRecordsPerPlayer = config.getInt("settings.rollback.max-records-per-player", 40);
    }

    private static long seconds(FileConfiguration config, String path, long fallbackSeconds) {
        return Math.max(0L, config.getLong(path, fallbackSeconds)) * 1000L;
    }

    private static Set<Material> readMaterials(FileConfiguration config, String path) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        List<String> list = config.getStringList(path);
        for (String raw : list) {
            if (raw == null) {
                continue;
            }
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material != null) {
                set.add(material);
            }
        }
        return set;
    }
}
