package com.anticheat.client.mixin;

import com.anticheat.client.AntiCheatClientMod;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercept mouse clicks to record Java-level mouse events (fallback when native hooks not available).
 */
@Mixin(Mouse.class)
public class MouseMixin {

    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void onMouseButton(long window, int button, int action, int mods, CallbackInfo ci) {
        try {
            if (action == 1) { // press
                AntiCheatClientMod mod = AntiCheatClientMod.getInstance();
                if (mod != null) mod.onMouseClick();
            }
        } catch (Exception ignored) {}
    }
}
