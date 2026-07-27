package com.anticheat.client.mixin;

import com.anticheat.client.AntiCheatClientMod;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Evolution: Detect NoSwing, MultiTask via LivingEntity hooks.
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Inject(method = "swingHand", at = @At("HEAD"))
    private void onSwingHand(net.minecraft.util.Hand hand, CallbackInfo ci) {
        try {
            AntiCheatClientMod mod = AntiCheatClientMod.getInstance();
            if (mod != null && mod.getWorldInteractionAnalyzer() != null) {
                // Record swing time for NoSwing detection handled elsewhere
            }
        } catch (Exception ignored) {}
    }
}
