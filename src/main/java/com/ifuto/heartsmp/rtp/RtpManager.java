package com.ifuto.heartsmp.rtp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import com.ifuto.heartsmp.HeartSMP;
import com.ifuto.heartsmp.player.Rank;

/** ランダムテレポートの探索とクールダウン管理。 */
public final class RtpManager {

    public enum Type {
        NORMAL,
        CAVE,
        BIOME
    }

    private final HeartSMP plugin;
    private final Random random = new Random();
    private final Map<UUID, Long> normalCooldown = new HashMap<>();
    private final Map<UUID, Long> sharedCooldown = new HashMap<>();

    public RtpManager(HeartSMP plugin) {
        this.plugin = plugin;
    }

    public Rank requiredRank(Type type) {
        return switch (type) {
            case NORMAL -> Rank.DEFAULT;
            case CAVE -> Rank.VIP;
            case BIOME -> Rank.VIP_PLUS;
        };
    }

    public long cooldownMillis(Type type) {
        return switch (type) {
            case NORMAL -> plugin.settings().rtpNormalCooldownMillis;
            case CAVE -> plugin.settings().rtpCaveCooldownMillis;
            case BIOME -> plugin.settings().rtpBiomeCooldownMillis;
        };
    }

    private Map<UUID, Long> cooldownMap(Type type) {
        return type == Type.NORMAL ? normalCooldown : sharedCooldown;
    }

    public long remaining(Player player, Type type) {
        Long until = cooldownMap(type).get(player.getUniqueId());
        if (until == null) {
            return 0L;
        }
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public void setCooldown(Player player, Type type) {
        if (player.hasPermission("heartsmp.rtp.bypass")) {
            return;
        }
        cooldownMap(type).put(player.getUniqueId(), System.currentTimeMillis() + cooldownMillis(type));
    }

    public boolean unlocked(Player player, Type type) {
        if (player.hasPermission("heartsmp.rtp.bypass")) {
            return true;
        }
        return plugin.playerData().rank(player.getUniqueId()).atLeast(requiredRank(type));
    }

    /** RTP を実行します。 */
    public void attempt(Player player, Type type, String biomeKey) {
        if (!unlocked(player, type)) {
            plugin.messages().error(player, "rtp-locked",
                    "rank", requiredRank(type).display(plugin));
            return;
        }
        long remaining = remaining(player, type);
        if (remaining > 0) {
            plugin.messages().error(player, "rtp-cooldown",
                    "seconds", String.valueOf((remaining + 999L) / 1000L));
            return;
        }
        World world = overworld();
        if (world == null) {
            plugin.messages().error(player, "rtp-failed");
            return;
        }
        plugin.messages().send(player, "rtp-searching");

        Location location = switch (type) {
            case NORMAL -> findSurface(world, null);
            case CAVE -> findCave(world);
            case BIOME -> findSurface(world, biomeKey);
        };
        if (location == null) {
            plugin.messages().send(player, "rtp-failed");
            return;
        }
        player.teleportAsync(location).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (Boolean.TRUE.equals(success)) {
                player.setFallDistance(0.0F);
                plugin.messages().send(player, "rtp-success",
                        "x", String.valueOf(location.getBlockX()),
                        "y", String.valueOf(location.getBlockY()),
                        "z", String.valueOf(location.getBlockZ()));
                setCooldown(player, type);
            } else {
                plugin.messages().send(player, "rtp-failed");
            }
        }));
    }

    private World overworld() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    // ------------------------------------------------------------------
    // 探索
    // ------------------------------------------------------------------

    private Location findSurface(World world, String biomeKey) {
        long budget = System.currentTimeMillis() + 200L;
        int[] radius = searchRadius(world);
        int min = radius[0];
        int max = radius[1];
        for (int attempt = 0; attempt < plugin.settings().rtpMaxAttempts; attempt++) {
            if (System.currentTimeMillis() > budget) {
                return null;
            }
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = min + random.nextDouble() * (max - min);
            int x = (int) Math.round(Math.cos(angle) * distance);
            int z = (int) Math.round(Math.sin(angle) * distance);
            if (!insideBorder(world, x, z)) {
                continue;
            }
            Location location = surfaceAt(world, x, z, biomeKey);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    private Location surfaceAt(World world, int x, int z, String biomeKey) {
        int y = world.getHighestBlockYAt(x, z);
        if (y <= world.getMinHeight() + 4 || y >= world.getMaxHeight() - 3) {
            return null;
        }
        Block ground = world.getBlockAt(x, y, z);
        Material material = ground.getType();
        if (!material.isSolid() || ground.isLiquid()) {
            return null;
        }
        switch (material) {
            case LAVA, MAGMA_BLOCK, CACTUS, POWDER_SNOW, SWEET_BERRY_BUSH, FIRE, SOUL_FIRE, BEDROCK -> {
                return null;
            }
            default -> {
            }
        }
        if (!clear(world.getBlockAt(x, y + 1, z)) || !clear(world.getBlockAt(x, y + 2, z))) {
            return null;
        }
        if (biomeKey != null) {
            String key = world.getBiome(x, y, z).getKey().getKey().toLowerCase(Locale.ROOT);
            if (!key.equals(biomeKey)) {
                return null;
            }
        }
        return new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
    }

    private Location findCave(World world) {
        long budget = System.currentTimeMillis() + 200L;
        int[] radius = searchRadius(world);
        int min = radius[0];
        int max = radius[1];
        int minY = world.getMinHeight() + Math.max(8, plugin.settings().rtpCaveMinYOffset);
        int maxY = Math.min(plugin.settings().rtpCaveMaxY, world.getMaxHeight() - 20);
        if (maxY <= minY) {
            maxY = minY + 16;
        }
        for (int attempt = 0; attempt < plugin.settings().rtpMaxAttempts; attempt++) {
            if (System.currentTimeMillis() > budget) {
                return null;
            }
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double distance = min + random.nextDouble() * (max - min);
            int x = (int) Math.round(Math.cos(angle) * distance);
            int z = (int) Math.round(Math.sin(angle) * distance);
            if (!insideBorder(world, x, z)) {
                continue;
            }
            int y = minY + random.nextInt(Math.max(1, maxY - minY));
            Block floor = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            if (!floor.getType().isSolid() || floor.isLiquid()) {
                continue;
            }
            Material floorType = floor.getType();
            if (floorType == Material.BEDROCK || floorType == Material.MAGMA_BLOCK) {
                continue;
            }
            if (feet.isLiquid() || head.isLiquid()) {
                continue;
            }
            if (!feet.getType().isAir() || !head.getType().isAir()) {
                continue;
            }
            boolean roofed = false;
            for (int dy = 2; dy <= 12; dy++) {
                Block above = world.getBlockAt(x, y + dy, z);
                if (above.getType().isSolid()) {
                    roofed = true;
                    break;
                }
            }
            if (!roofed) {
                continue;
            }
            return new Location(world, x + 0.5D, y, z + 0.5D);
        }
        return null;
    }

    /** ワールドボーダーに収まるように探索半径をクランプします。 */
    private int[] searchRadius(World world) {
        int min = Math.max(8, plugin.settings().rtpNormalMinRadius);
        int max = Math.max(min + 1, plugin.settings().rtpNormalMaxRadius);
        double borderRadius = Math.max(32.0D, world.getWorldBorder().getSize() / 2.0D - 48.0D);
        if (max > borderRadius) {
            max = (int) Math.floor(borderRadius);
        }
        if (min >= max) {
            min = Math.max(8, max / 3);
        }
        if (max <= min) {
            max = min + 1;
        }
        return new int[]{min, max};
    }

    private boolean insideBorder(World world, int x, int z) {
        var border = world.getWorldBorder();
        Location center = border.getCenter();
        double size = border.getSize() / 2.0D;
        return Math.abs(x - center.getX()) <= Math.max(16.0D, size - 32.0D)
                && Math.abs(z - center.getZ()) <= Math.max(16.0D, size - 32.0D);
    }

    private boolean clear(Block block) {
        Material type = block.getType();
        return type.isAir() || (block.isPassable() && !block.isLiquid());
    }
}
