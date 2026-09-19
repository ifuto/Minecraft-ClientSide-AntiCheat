package dev.ifuto.mcsa.server.check;

import dev.ifuto.mcsa.server.McsaConfig;
import dev.ifuto.mcsa.server.McsaPlugin;
import dev.ifuto.mcsa.server.alert.AlertService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * サーバー権限で観測できる行動の検知。
 *
 * <p>クライアントの自己申告は偽装できるが、ここで見ている移動・攻撃・破壊は
 * サーバーに届いたパケットそのものなので偽装できない（チートは「ありえない値」を送るしかない）。
 * だから MCSA の確定判定は always こちらが本丸で、レポートは状況証拠。
 *
 * <p>誤検知を避けるため、初期値はかなり緩い。まずアラートだけ出して様子を見てから
 * {@code checks.cancel} や {@code checks.kick-vl} を有効化すること。
 */
public final class CheckManager implements Listener {

    private static final long ALERT_INTERVAL_MS = 2000;

    private final McsaPlugin plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();

    public CheckManager(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    public PlayerState state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new PlayerState());
    }

    public Map<String, Integer> violations(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        return state == null ? Map.of() : new HashMap<>(state.allViolations());
    }

    public void reset(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        if (state != null) {
            state.reset();
        }
    }

    // ------------------------------------------------------------------ 状態

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        state(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        state(event.getPlayer()).exempt(1500);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        state(event.getPlayer()).exempt(1000);
    }

    // ------------------------------------------------------------------ 移動

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        McsaConfig config = plugin.config();
        if (!config.checksEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("mcsa.bypass")) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            state(player).exempt(1000);
            return;
        }
        PlayerState state = state(player);
        checkFly(player, state, from, to, config);
        checkSpeed(player, state, from, to, config);
    }

    private void checkFly(Player player, PlayerState state, Location from, Location to, McsaConfig config) {
        if (!config.flyEnabled || state.exempt() || isMovementExempt(player) || isInFluid(player)
                || isClimbing(player, to) || player.isOnGround()) {
            state.airTicks = 0;
            return;
        }
        double dy = to.getY() - from.getY();
        if (dy >= -0.05) {
            state.airTicks++;
        } else {
            state.airTicks = 0;
            return;
        }
        if (state.airTicks >= config.flyMinAirTicks) {
            // 船やトロッコの上に立っているだけのケースを除外する
            if (standingOnEntity(player)) {
                state.airTicks = 0;
                return;
            }
            flag(player, "FLY", state.airTicks + "tick 滞空 (dy=" + format(dy) + ")");
            state.airTicks = 0;
        }
    }

    private void checkSpeed(Player player, PlayerState state, Location from, Location to, McsaConfig config) {
        if (!config.speedEnabled || state.exempt() || isMovementExempt(player) || !player.isOnGround()) {
            state.speedTicks = 0;
            return;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double limit = config.speedMaxPerTick + speedBonus(player);
        if (horizontal > limit) {
            state.speedTicks++;
            if (state.speedTicks >= config.speedMaxTicks) {
                flag(player, "SPEED", format(horizontal) + " blocks/tick (limit " + format(limit) + ")");
                state.speedTicks = 0;
            }
        } else {
            state.speedTicks = 0;
        }
    }

    // ------------------------------------------------------------------ 戦闘

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        McsaConfig config = plugin.config();
        if (!config.checksEnabled) {
            return;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (player.hasPermission("mcsa.bypass")) {
            return;
        }
        Entity victim = event.getEntity();
        PlayerState state = state(player);
        if (state.exempt()) {
            return;
        }

        if (config.reachEnabled) {
            double distance = distanceToBoundingBox(player.getEyeLocation(), victim.getBoundingBox());
            double limit = config.maxReach;
            if (config.pingCompensation) {
                limit += Math.min(1.5, player.getPing() / 1000.0 * 1.5);
            }
            if (player.getGameMode() == GameMode.CREATIVE) {
                limit += 2.0;
            }
            if (distance > limit) {
                flag(player, "REACH", format(distance) + " blocks (limit " + format(limit) + ")");
                if (config.checksCancel) {
                    event.setCancelled(true);
                }
            }
        }

        if (config.killAuraEnabled) {
            long now = System.currentTimeMillis();
            PlayerState.trim(state.attacks, now, 1000);
            state.attacks.addLast(now);
            if (state.attacks.size() > config.killAuraMaxHitsPerSecond) {
                flag(player, "KILLAURA", "1秒間の攻撃 " + state.attacks.size() + " 回");
            }

            Location eye = player.getEyeLocation();
            Vector look = eye.getDirection();
            Location victimLocation = victim.getLocation();
            Vector toVictim = victimLocation.toVector()
                    .setY(victimLocation.getY() + victim.getHeight() / 2.0)
                    .subtract(eye.toVector());
            if (toVictim.lengthSquared() > 1.0E-4) {
                double angle = Math.toDegrees(look.angle(toVictim.normalize()));
                if (angle > config.killAuraMaxAngle) {
                    flag(player, "KILLAURA", "視線との角度 " + format(angle) + "°");
                }
            }
        }
    }

    // ------------------------------------------------------------------ クリック / 破壊

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_AIR && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        McsaConfig config = plugin.config();
        if (!config.checksEnabled || !config.autoClickerEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("mcsa.bypass")) {
            return;
        }
        PlayerState state = state(player);
        if (state.exempt()) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerState.trim(state.clicks, now, 1000);
        state.clicks.addLast(now);

        if (state.clicks.size() > config.autoClickerMaxCps) {
            flag(player, "AUTOCLICKER", "CPS " + state.clicks.size());
        }
        if (state.clicks.size() >= config.autoClickerMinSamples) {
            double deviation = intervalDeviation(state.clicks);
            if (deviation >= 0 && deviation < config.autoClickerMinDeviation) {
                flag(player, "AUTOCLICKER", "クリック間隔のばらつき " + format(deviation) + "ms");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        McsaConfig config = plugin.config();
        if (!config.checksEnabled || !config.fastBreakEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("mcsa.bypass")) {
            return;
        }
        PlayerState state = state(player);
        if (state.exempt()) {
            return;
        }
        long now = System.currentTimeMillis();
        PlayerState.trim(state.breaks, now, 1000);
        state.breaks.addLast(now);
        if (state.breaks.size() > config.fastBreakMaxPerSecond) {
            flag(player, "FASTBREAK", "1秒間の破壊 " + state.breaks.size() + " 個");
        }
    }

    // ------------------------------------------------------------------ 共通

    private void flag(Player player, String checkId, String detail) {
        McsaConfig config = plugin.config();
        if (!config.checksEnabled) {
            return;
        }
        PlayerState state = state(player);
        int violationLevel = state.addViolation(checkId);
        long now = System.currentTimeMillis();
        if (violationLevel >= config.alertVl && now - state.lastAlertAt > ALERT_INTERVAL_MS) {
            state.lastAlertAt = now;
            plugin.alerts().check(player, checkId, detail, violationLevel);
        }
        if (config.kickVl > 0 && violationLevel >= config.kickVl) {
            player.kick(AlertService.legacy(config.checkKickMessage));
        }
    }

    private static boolean isMovementExempt(Player player) {
        return player.getAllowFlight() || player.isFlying() || player.isGliding()
                || player.isRiptiding() || player.isInsideVehicle()
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
    }

    private static boolean isInFluid(Player player) {
        return player.isInWater() || player.isInLava()
                || player.getLocation().getBlock().getType() == Material.BUBBLE_COLUMN;
    }

    /** はしご・つる・足場などは「浮遊」ではない。 */
    private static boolean isClimbing(Player player, Location to) {
        Material type = to.getBlock().getType();
        String name = type.name();
        return name.contains("LADDER") || name.contains("VINES") || name.contains("SCAFFOLDING")
                || name.contains("TWISTING_VINES") || name.contains("CAVE_VINES") || name.contains("WEEPING_VINES");
    }

    private static boolean standingOnEntity(Player player) {
        Location feet = player.getLocation();
        for (Entity entity : player.getWorld().getNearbyEntities(feet, 0.5, 1.0, 0.5)) {
            if (entity.equals(player)) {
                continue;
            }
            BoundingBox box = entity.getBoundingBox();
            if (box.getMaxY() >= feet.getY() - 0.35 && box.getMaxY() <= feet.getY() + 0.35
                    && feet.getX() >= box.getMinX() && feet.getX() <= box.getMaxX()
                    && feet.getZ() >= box.getMinZ() && feet.getZ() <= box.getMaxZ()) {
                return true;
            }
        }
        return false;
    }

    private static double speedBonus(Player player) {
        double bonus = 0.0;
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            bonus += 0.06 * (speed.getAmplifier() + 1);
        }
        Material below = player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType();
        if (below.name().contains("ICE")) {
            bonus += 0.25;
        }
        return bonus;
    }

    private static double distanceToBoundingBox(Location eye, BoundingBox box) {
        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        double dx = eye.getX() - x;
        double dy = eye.getY() - y;
        double dz = eye.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** クリック間隔（ミリ秒）の標準偏差。サンプルが足りなければ -1。 */
    private static double intervalDeviation(Deque<Long> timestamps) {
        if (timestamps.size() < 3) {
            return -1;
        }
        double sum = 0;
        double squared = 0;
        int count = 0;
        Iterator<Long> iterator = timestamps.iterator();
        long previous = iterator.next();
        while (iterator.hasNext()) {
            long current = iterator.next();
            double interval = current - previous;
            sum += interval;
            squared += interval * interval;
            count++;
            previous = current;
        }
        if (count == 0) {
            return -1;
        }
        double mean = sum / count;
        double variance = squared / count - mean * mean;
        return variance <= 0 ? 0 : Math.sqrt(variance);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
