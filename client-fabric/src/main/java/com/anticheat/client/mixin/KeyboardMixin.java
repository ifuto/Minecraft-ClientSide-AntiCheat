package com.anticheat.client.mixin;

import com.anticheat.client.AntiCheatClientMod;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        try {
            if (action == 1) { // press
                AntiCheatClientMod mod = AntiCheatClientMod.getInstance();
                if (mod != null) mod.onKeyPress();
            }
        } catch (Exception ignored) {}
    }
}
