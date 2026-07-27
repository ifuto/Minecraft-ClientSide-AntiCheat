package com.anticheat.client.mixin;

import com.anticheat.client.AntiCheatClientMod;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Evolution: Movement physics validation hook.
 * Intercepts ClientPlayerEntity tickMovement to feed into MovementAnalyzer.
 */
@Mixin(ClientPlayerEntity.class)
public class PlayerMovementMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        try {
            AntiCheatClientMod mod = AntiCheatClientMod.getInstance();
            if (mod != null && mod.getMovementAnalyzer() != null) {
                ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
                mod.getMovementAnalyzer().onTick(player);
                if (mod.getTimingAnalyzer() != null) mod.getTimingAnalyzer().onTick();
            }
        } catch (Exception ignored) {}
    }
}
