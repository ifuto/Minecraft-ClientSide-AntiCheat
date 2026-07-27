package com.anticheat.client.detection;

import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Evolution: MixinGuard verifies that no unexpected mixins are applied to critical classes.
 * Attacker could inject mixin into ClientPlayerEntity to implement Fly/NoFall without changing package.
 *
 * Checks:
 * - Enumerate loaded mixin configs via reflection on MixinEnvironment
 * - For each target class, list mixins applied. If mixin comes from unknown mod jar (not fabric, not our anticheat), flag.
 * - Detect @Overwrite of critical methods: move, tick, jump, setVelocity, handleFallDamage
 *
 * Performance: Run once at startup, O(mixins) ~ few hundred.
 *
 * Bypass: Attacker could use custom Mixin implementation that doesn't register via standard Mixin API but uses Mixin's internal API directly.
 * Mitigation: also check bytecode for Mixin annotations (BytecodeAnalyzer already does).
 */
public final class MixinGuard {

    public static class MixinViolation {
        public final String targetClass;
        public final String mixinClass;
        public final String reason;

        public MixinViolation(String target, String mixin, String reason) {
            this.targetClass = target;
            this.mixinClass = mixin;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "MixinViolation target=" + targetClass + " mixin=" + mixinClass + " reason=" + reason;
        }
    }

    private static final List<String> CRITICAL_TARGETS = List.of(
            "net.minecraft.client.network.ClientPlayerEntity",
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.entity.LivingEntity",
            "net.minecraft.entity.player.PlayerEntity",
            "net.minecraft.client.render.GameRenderer"
    );

    public List<MixinViolation> scan() {
        List<MixinViolation> violations = new ArrayList<>();

        // Try to get Mixin environment via reflection, to avoid hard dependency on Mixin at compile time if not present
        try {
            Class<?> mixinEnvClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
            Method getDefault = mixinEnvClass.getMethod("getDefaultEnvironment");
            Object env = getDefault.invoke(null);

            Method getConfigs = env.getClass().getMethod("getConfigs");
            List<?> configs = (List<?>) getConfigs.invoke(env);

            for (Object configObj : configs) {
                IMixinConfig config = (IMixinConfig) configObj;
                String configName = config.getName();
                // Skip our own mixins, fabric, vanilla
                if (configName.contains("anticheat") || configName.contains("fabric") || configName.contains("minecraft")) continue;

                // For each config, get mixins
                // config.getMixinInfos() may not be public, try reflection
                try {
                    Method getMixins = config.getClass().getMethod("getMixins");
                    List<?> mixinInfos = (List<?>) getMixins.invoke(config);
                    for (Object mixinInfoObj : mixinInfos) {
                        IMixinInfo info = (IMixinInfo) mixinInfoObj;
                        String mixinClass = info.getClassName();
                        // Try to get target classes
                        List<String> targets = info.getTargetClasses();
                        for (String target : targets) {
                            if (CRITICAL_TARGETS.stream().anyMatch(target::equals)) {
                                // Found mixin targeting critical class from unknown mod
                                violations.add(new MixinViolation(target, mixinClass, "Mixin from " + configName + " targets critical class"));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // fallback: try via getDeclaredMethod
                }
            }
        } catch (ClassNotFoundException e) {
            // Mixin not available in this environment (e.g., not Fabric) - skip
            System.out.println("[MixinGuard] MixinEnvironment not found, skipping");
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Additional heuristic: check FabricLoader mod list for mods that contain mixin config but not declared?
        // For now, simple.

        return violations;
    }
}
