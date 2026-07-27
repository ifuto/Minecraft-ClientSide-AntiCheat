package com.anticheat.client.mixin;

import com.anticheat.client.AntiCheatClientMod;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept player actions (attack, use) for input authenticity verification.
 *
 * These injection points cover:
 * - doAttack -> left click / attack entity
 * - doItemUse -> right click / use item
 *
 * For each action, we notify AntiCheatClientMod to check if hardware input recently occurred.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "doAttack", at = @At("HEAD"))
    private void onDoAttack(CallbackInfoReturnable<Boolean> cir) {
        try {
            AntiCheatClientMod mod = AntiCheatClientMod.getInstance();
            if (mod != null) mod.onPlayerAttack();
        } catch (Exception ignored) {}
    }

    @Inject(method = "doItemUse", at = @At("HEAD"))
    private void onDoItemUse(CallbackInfo ci) {
        try {
            AntiCheatClientMod mod = AntiCheatClientMod.getInstance();
            if (mod != null) mod.onPlayerUseItem();
        } catch (Exception ignored) {}
    }

    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void onHandleInputEvents(CallbackInfo ci) {
        // This method is called each tick handling input, but we can use it to record that input handling happened
        // For Java fallback tracking
    }
}
