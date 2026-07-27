package com.anticheat.client.detection;

import com.anticheat.client.network.ViolationReporter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Evolution: Physics-based movement validation.
 * Server-authoritative anticheat usually validates movement, but client can pre-detect cheats like Timer, Fly, Speed, NoFall, Jesus, Step, LongJump.
 *
 * How it works:
 * - Track player position, velocity, onGround, tick time.
 * - Compute expected physics: gravity=0.08, drag=0.98, etc.
 * - Detect anomalies:
 *   Timer: client ticks faster than real time. Measure System.nanoTime between ticks vs expected 50ms. If consistently <45ms while moving, flag Timer.
 *   Fly: player y increases without block support and without creative/spectator. Detect sustained upward motion not explainable by jump.
 *   Speed: horizontal distance per tick > max sprint speed + epsilon (0.3 blocks/tick sprinting). Loosely: speed >0.35 without status effects.
 *   NoFall: fall distance >3 but no damage and onGround toggled.
 *   Step: instant step up >0.5 blocks without jump.
 *   Jesus: walking on water (y constant over water)
 *
 * Performance: per tick O(1), only if player != null.
 *
 * Math:
 * - Speed threshold derived from Minecraft's movement: walk 0.1, sprint 0.13, sprint+speed II ~0.2. So 0.35 is safe margin for flag.
 * - Timer detection: measure tick delta via System.nanoTime. If average tick <45ms over window 100 ticks while moving, flag.
 */
public final class MovementAnalyzer {

    private Vec3d lastPos = null;
    private Vec3d lastVelocity = null;
    private long lastTickNano = 0;
    private boolean lastOnGround = false;

    private final double[] tickDeltas = new double[100];
    private int tickIndex = 0;
    private int tickCount = 0;

    private int flyStreak = 0;
    private int speedStreak = 0;
    private int timerStreak = 0;

    private static final double MAX_LEGIT_HORIZONTAL_SPEED = 0.35; // blocks per tick
    private static final double MAX_LEGIT_VERTICAL_UP = 0.42; // jump motion
    private static final int STREAK_TO_FLAG = 10;

    public void onTick(PlayerEntity player) {
        long nowNano = System.nanoTime();
        Vec3d pos = player.getPos();
        Vec3d vel = player.getVelocity();
        boolean onGround = player.isOnGround();

        if (lastPos != null) {
            double deltaX = pos.x - lastPos.x;
            double deltaZ = pos.z - lastPos.z;
            double horizDist = Math.sqrt(deltaX*deltaX + deltaZ*deltaZ);
            double deltaY = pos.y - lastPos.y;

            // Speed check: horizontal distance per tick
            if (horizDist > MAX_LEGIT_HORIZONTAL_SPEED && !player.isSprinting() && !player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SPEED)) {
                // But check if player is in vehicle or has elytra? Simplified
                if (!player.isFallFlying() && !player.isInFluid() && !player.hasVehicle()) {
                    speedStreak++;
                    if (speedStreak >= STREAK_TO_FLAG) {
                        ViolationReporter.reportTamper("SPEED", "horizDist=" + String.format("%.3f", horizDist) + " > " + MAX_LEGIT_HORIZONTAL_SPEED);
                        speedStreak = 0;
                    }
                }
            } else {
                speedStreak = Math.max(0, speedStreak-1);
            }

            // Fly check: sustained upward motion without ground, not jumping, not in water, not creative
            if (!player.getAbilities().allowFlying && !player.isCreative() && !player.isSpectator() && !player.isInFluid() && !player.isFallFlying()) {
                if (deltaY > 0 && !onGround && !lastOnGround && vel.y > 0.1) {
                    // Check if block below is air
                    // Simplified: if y increasing for 10 ticks without falling
                    flyStreak++;
                    if (flyStreak >= 20) {
                        ViolationReporter.reportTamper("FLY", "sustained upward motion deltaY=" + String.format("%.3f", deltaY) + " velY=" + String.format("%.3f", vel.y));
                        flyStreak = 0;
                    }
                } else if (onGround) {
                    flyStreak = 0;
                }
            }

            // Timer check: measure tick delta
            if (lastTickNano != 0) {
                double deltaMs = (nowNano - lastTickNano) / 1_000_000.0;
                tickDeltas[tickIndex] = deltaMs;
                tickIndex = (tickIndex + 1) % tickDeltas.length;
                if (tickCount < tickDeltas.length) tickCount++;

                if (tickCount >= 50) {
                    double avg = 0;
                    for (int i=0;i<tickCount;i++) avg += tickDeltas[i];
                    avg /= tickCount;

                    // If average tick time <45ms (20 TPS expected 50ms) and player is moving, indicates Timer
                    if (avg < 45.0 && horizDist > 0.05) {
                        timerStreak++;
                        if (timerStreak >= 30) {
                            ViolationReporter.reportTamper("TIMER", "avgTick=" + String.format("%.2f", avg) + "ms expected 50ms");
                            timerStreak = 0;
                        }
                    } else {
                        timerStreak = Math.max(0, timerStreak-1);
                    }
                }
            }

            // Step check: instant y increase >0.6 without jump
            if (onGround && lastOnGround && deltaY > 0.6 && deltaY < 2.0 && horizDist > 0.1) {
                ViolationReporter.reportTamper("STEP", "instant step up deltaY=" + deltaY);
            }

            // NoFall: fall distance high but no damage and onGround true
            // Minecraft tracks fallDistance field
            float fallDist = player.fallDistance;
            if (fallDist > 3.5f && onGround && !player.isInFluid()) {
                // If player just landed but didn't take damage and fallDist not reset? Actually fallDistance resets on landing
                // If we see fallDist >3.5 and onGround, it means they avoided fall damage logic (NoFall)
                // Need to check previous tick fallDist: if it was high and now onGround but health unchanged, flag
                // Simplified: if fallDist>5 and onGround for 1 tick, flag
                ViolationReporter.reportTamper("NOFALL", "fallDistance=" + fallDist + " onGround=" + onGround);
            }
        }

        lastPos = pos;
        lastVelocity = vel;
        lastOnGround = onGround;
        lastTickNano = nowNano;
    }

    public void reset() {
        lastPos = null;
        lastVelocity = null;
        lastTickNano = 0;
        flyStreak = 0;
        speedStreak = 0;
        timerStreak = 0;
        tickCount = 0;
        tickIndex = 0;
    }
}
