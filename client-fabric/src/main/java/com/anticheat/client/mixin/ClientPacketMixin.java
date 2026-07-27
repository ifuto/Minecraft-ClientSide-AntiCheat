package com.anticheat.client.mixin;

import com.anticheat.client.AntiCheatClientMod;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Evolution: Packet rate / attack packet analysis.
 * Intercepts outgoing packets to detect spam, reach is validated elsewhere.
 */
@Mixin(ClientConnection.class)
public class ClientPacketMixin {

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        try {
            AntiCheatClientMod mod = AntiCheatClientMod.getInstance();
            if (mod == null || mod.getPacketAnalyzer() == null) return;

            if (packet instanceof PlayerMoveC2SPacket) {
                mod.getPacketAnalyzer().onPacketSend();
            } else if (packet instanceof PlayerInteractEntityC2SPacket) {
                mod.getPacketAnalyzer().onAttackPacket();
            }
        } catch (Exception ignored) {}
    }
}
