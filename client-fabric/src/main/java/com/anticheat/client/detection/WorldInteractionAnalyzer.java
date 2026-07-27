package com.anticheat.client.detection;

import com.anticheat.client.network.ViolationReporter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

/**
 * Evolution: Detect impossible world interactions: Reach, FastBreak, FastPlace, NoSwing, AirPlace, MultiTask
 *
 * Reach: maximum interaction distance in survival is 4.5 blocks (creative 5). Some cheats extend to 6+.
 * We compute distance from eye pos to hit result. If >4.8, flag.
 *
 * FastBreak: block break time should match tool + hardness. If player breaks 5 blocks in <1 second, impossible.
 *
 * FastPlace: placing blocks faster than 4 per second with no pixel-perfect aim is suspicious.
 *
 * NoSwing: attack without arm swing animation. Vanilla always swings when attacking. Cheat may cancel swing.
 *
 * AirPlace: placing block where no supporting face? Check BlockHitResult type.
 *
 * MultiTask: eating + attacking simultaneously (use item + attack in same tick)
 *
 * Performance: event-driven, not per tick heavy.
 *
 * Math:
 * - Reach: Euclidean distance eye->hit, threshold 4.5+0.3 epsilon for lag.
 * - Break speed: track last N break times, compute rate, if rate > 8 blocks/sec flagged.
 */
public final class WorldInteractionAnalyzer {

    private long lastBreakTime = 0;
    private int breakCountWindow = 0;
    private long breakWindowStart = 0;

    private long lastPlaceTime = 0;
    private int placeCountWindow = 0;
    private long placeWindowStart = 0;

    private boolean wasUsingItemLastTick = false;
    private long lastSwingTime = 0;

    private static final double MAX_REACH_SURVIVAL = 4.5;
    private static final double MAX_REACH_CREATIVE = 5.0;
    private static final double REACH_EPSILON = 0.3;

    private static final int MAX_BREAKS_PER_SECOND = 6;
    private static final int MAX_PLACES_PER_SECOND = 5;

    public void onBlockBreak(PlayerEntity player, Vec3d hitPos) {
        long now = System.currentTimeMillis();

        // Reach
        Vec3d eyePos = player.getEyePos();
        double dist = eyePos.distanceTo(hitPos);
        double maxReach = player.isCreative() ? MAX_REACH_CREATIVE : MAX_REACH_SURVIVAL;
        if (dist > maxReach + REACH_EPSILON) {
            ViolationReporter.reportTamper("REACH_BREAK", "dist=" + String.format("%.2f", dist) + " max=" + maxReach);
        }

        // FastBreak rate
        if (now - breakWindowStart > 1000) {
            breakWindowStart = now;
            breakCountWindow = 0;
        }
        breakCountWindow++;
        if (breakCountWindow > MAX_BREAKS_PER_SECOND) {
            ViolationReporter.reportTamper("FAST_BREAK", "breaks=" + breakCountWindow + " in 1s");
        }
        lastBreakTime = now;
    }

    public void onBlockPlace(PlayerEntity player, BlockHitResult hitResult) {
        long now = System.currentTimeMillis();
        if (hitResult.getType() == HitResult.Type.MISS) {
            ViolationReporter.reportTamper("AIR_PLACE", "place with MISS hit result");
            return;
        }

        // Reach for place: eye to block pos
        Vec3d eyePos = player.getEyePos();
        double dist = eyePos.distanceTo(Vec3d.ofCenter(hitResult.getBlockPos()));
        double maxReach = player.isCreative() ? MAX_REACH_CREATIVE : MAX_REACH_SURVIVAL;
        if (dist > maxReach + REACH_EPSILON) {
            ViolationReporter.reportTamper("REACH_PLACE", "dist=" + String.format("%.2f", dist));
        }

        // FastPlace
        if (now - placeWindowStart > 1000) {
            placeWindowStart = now;
            placeCountWindow = 0;
        }
        placeCountWindow++;
        if (placeCountWindow > MAX_PLACES_PER_SECOND) {
            ViolationReporter.reportTamper("FAST_PLACE", "places=" + placeCountWindow + " in 1s");
        }
        lastPlaceTime = now;
    }

    public void onAttack(boolean swung) {
        if (!swung) {
            ViolationReporter.reportTamper("NO_SWING", "attack without swing animation");
        }
        lastSwingTime = System.currentTimeMillis();
    }

    public void onTick(PlayerEntity player) {
        boolean isUsing = player.isUsingItem();
        if (isUsing && wasUsingItemLastTick) {
            // If player attacks while using item (eating, bow, etc), MultiTask cheat
            // Need to check if attack happened this tick - would be flagged via onAttack + isUsing
            // We'll track externally
        }
        wasUsingItemLastTick = isUsing;
    }

    public void onAttackWhileUsing() {
        ViolationReporter.reportTamper("MULTITASK", "attack while using item (eating/bow)");
    }
}
