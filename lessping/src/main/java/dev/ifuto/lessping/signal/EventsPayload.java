package dev.ifuto.lessping.signal;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C: (中継プラグイン) → クライアント。
 *
 * <ul>
 *   <li>type 3 INTRO … 「このプレイヤーのエンドポイントはこれ」。要求者と対象の
 *       両方に送られる（双方が同時に穴あけを始められるように）。</li>
 * </ul>
 */
public record EventsPayload(int type, String player, String data) implements CustomPayload {

    public static final int TYPE_INTRO = 3;

    public static final CustomPayload.Id<EventsPayload> ID =
            new CustomPayload.Id<>(Identifier.of("lessping", "events"));

    public static final PacketCodec<PacketByteBuf, EventsPayload> CODEC =
            new PacketCodec<PacketByteBuf, EventsPayload>() {
                @Override
                public EventsPayload decode(PacketByteBuf buf) {
                    return new EventsPayload(buf.readVarInt(), buf.readString(64), buf.readString(1024));
                }

                @Override
                public void encode(PacketByteBuf buf, EventsPayload value) {
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
