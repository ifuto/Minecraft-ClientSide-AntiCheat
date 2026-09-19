package dev.ifuto.mcsa.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: レポートの封印。全断片を送り終えたあとに送り、
 * サーバは再構成した gzip バイト列の HMAC を検証してからレポートを受理する。
 *
 * @param hmac {@code HMAC-SHA256(key, nonce ‖ gzipBytes)} の 16 進文字列
 */
public record SealPayload(int sessionId, int total, int dataLength, String hmac) implements CustomPayload {

    public static final CustomPayload.Id<SealPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "seal"));

    public static final PacketCodec<PacketByteBuf, SealPayload> CODEC =
            new PacketCodec<PacketByteBuf, SealPayload>() {
                @Override
                public SealPayload decode(PacketByteBuf buf) {
                    return new SealPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readString(128));
                }

                @Override
                public void encode(PacketByteBuf buf, SealPayload value) {
                    buf.writeVarInt(value.sessionId());
                    buf.writeVarInt(value.total());
                    buf.writeVarInt(value.dataLength());
                    buf.writeString(value.hmac());
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
