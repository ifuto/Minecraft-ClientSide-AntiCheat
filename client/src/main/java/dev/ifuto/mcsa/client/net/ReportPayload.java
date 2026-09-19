package dev.ifuto.mcsa.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: レポート本体（gzip した JSON）の断片。
 *
 * <p>Bukkit 側のプラグインメッセージには歴史的なサイズ制約があるため、
 * 大きなレポートは {@link #CHUNK_SIZE} 単位に分割して送る。
 * 全断片が揃い、{@code mcsa:seal} の HMAC が一致して初めてサーバはレポートを受理する。
 */
public record ReportPayload(int sessionId, int seq, int total, byte[] data) implements CustomPayload {

    /** 1 断片あたりの最大バイト数（Bukkit の 32767 バイト制限に余裕を持たせる） */
    public static final int CHUNK_SIZE = 16384;

    public static final CustomPayload.Id<ReportPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "report"));

    public static final PacketCodec<PacketByteBuf, ReportPayload> CODEC =
            new PacketCodec<PacketByteBuf, ReportPayload>() {
                @Override
                public ReportPayload decode(PacketByteBuf buf) {
                    int sessionId = buf.readVarInt();
                    int seq = buf.readVarInt();
                    int total = buf.readVarInt();
                    int length = buf.readVarInt();
                    if (length < 0 || length > 1 << 20) {
                        throw new IllegalArgumentException("レポート断片が大きすぎます: " + length);
                    }
                    byte[] data = new byte[length];
                    buf.readBytes(data);
                    return new ReportPayload(sessionId, seq, total, data);
                }

                @Override
                public void encode(PacketByteBuf buf, ReportPayload value) {
                    buf.writeVarInt(value.sessionId());
                    buf.writeVarInt(value.seq());
                    buf.writeVarInt(value.total());
                    byte[] data = value.data();
                    buf.writeVarInt(data.length);
                    buf.writeBytes(data);
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
