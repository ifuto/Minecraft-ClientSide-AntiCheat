package dev.ifuto.lessping.mixin;

import dev.ifuto.lessping.LessPing;
import dev.ifuto.lessping.tunnel.TunnelManager;
import net.minecraft.client.network.Address;
import net.minecraft.client.network.AllowedAddressResolver;
import net.minecraft.client.network.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * 「narena」への接続をトンネルのローカル受け口へ差し替える。
 *
 * <p>{@link AllowedAddressResolver#resolve} はサーバーへの接続時とサーバーリストの
 * ping の両方で使われる唯一のアドレス解決ポイント。ここで HEAD を差し挟み、
 * トンネルが確立済みなら {@code 127.0.0.1:<localTcpPort>} を返す。
 * 確立前でもシグナリング先（本サーバー）が設定されていれば楽観的にローカル受け口を
 * 返す（接続時に待って、ダメなら通常経路へフォールバックする）。いずれでもなければ
 * 何もせず通常の解決（= unknown host）に戻す。
 *
 * <p>ハンドシェイクに載る hostname はユーザーが入力した「narena」のままなので、
 * サーバー側で「トンネル経由の接続」と区別できる。
 */
@Mixin(AllowedAddressResolver.class)
public abstract class AllowedAddressResolverMixin {

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true)
    private void lessping$resolveToTunnel(ServerAddress address, CallbackInfoReturnable<Optional<Address>> cir) {
        try {
            Optional<InetSocketAddress> tunnel = TunnelManager.get().redirect(address.getAddress());
            if (tunnel.isPresent()) {
                LessPing.LOGGER.info("[LessPing] {} への接続をトンネル {} へ差し替えます",
                        address.getAddress(), tunnel.get());
                cir.setReturnValue(Optional.of(Address.create(tunnel.get())));
            }
        } catch (Throwable t) {
            // この mixin が落ちても Minecraft 側の解決には影響させない
            LessPing.LOGGER.error("[LessPing] アドレス差し替えでエラー: {}", t.toString(), t);
        }
    }
}
