package dev.ifuto.lessping.signal;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: クライアント → (中継プラグイン)。
 *
 * <ul>
 *   <li>type 1 HELLO … 自分のエンドポイント情報を登録する</li>
 *   <li>type 2 INTRO_REQUEST … 「このプレイヤーのエンドポイントを教えて」</li>
 * </ul>
 *
 * <p>data は中継プラグインに対して不透明（opaqe）な文字列:
 * {@code pub=1.2.3.4:50000|priv=192.168.1.5:50000;...|host=1|v=0.1.0}
 */
public record SignalPayload(int type, String player, String data) implements CustomPayload {

    public static final int TYPE_HELLO = 1;
    public static final int TYPE_INTRO_REQUEST = 2;

    public static final CustomPayload.Id<SignalPayload> ID =
            new CustomPayload.Id<>(Identifier.of("lessping", "signal"));

    public static final PacketCodec<PacketByteBuf, SignalPayload> CODEC =
            new PacketCodec<PacketByteBuf, SignalPayload>() {
                @Override
                public SignalPayload decode(PacketByteBuf buf) {
                    return new SignalPayload(buf.readVarInt(), buf.readString(64), buf.readString(1024));
                }

                @Override
                public void encode(PacketByteBuf buf, SignalPayload value) {
                    buf.writeVarInt(value.type());
                    buf.writeString(value.player(), 64);
                    buf.writeString(value.data(), 1024);
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
