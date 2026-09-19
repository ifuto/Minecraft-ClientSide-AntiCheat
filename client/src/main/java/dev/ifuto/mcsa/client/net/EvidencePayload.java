package dev.ifuto.mcsa.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: 証拠（画面 / テキスト）の断片。
 *
 * <p>レポート（{@link ReportPayload}）と同じ理由で分割して送る。
 * {@code hmac} は全断片に同じ値（送信前の全データに対する HMAC-SHA256）を入れる。
 * 断片 0 にだけ入れる方式だと、断片 0 を落としたときに検証できなくなるため。
 *
 * <p>サーバー側は全断片が揃ってから HMAC を検証し、一致したものだけを
 * {@code plugins/MCSA/evidence/} に保存する。
 */
public record EvidencePayload(int sessionId, int kind, int seq, int total,
                              String name, String hmac, byte[] data) implements CustomPayload {

    public static final int KIND_SHOT = 1;
    public static final int KIND_NOTE = 3;

    /** 1 断片あたりの最大バイト数 */
    public static final int CHUNK_SIZE = 16384;

    public static final CustomPayload.Id<EvidencePayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "evidence"));

    public static final PacketCodec<PacketByteBuf, EvidencePayload> CODEC =
            new PacketCodec<PacketByteBuf, EvidencePayload>() {
                @Override
                public EvidencePayload decode(PacketByteBuf buf) {
                    int sessionId = buf.readVarInt();
                    int kind = buf.readVarInt();
                    int seq = buf.readVarInt();
                    int total = buf.readVarInt();
                    String name = buf.readString(128);
                    String hmac = buf.readString(128);
                    int length = buf.readVarInt();
                    if (length < 0 || length > 1 << 20) {
                        throw new IllegalArgumentException("証拠断片が大きすぎます: " + length);
                    }
                    byte[] data = new byte[length];
                    buf.readBytes(data);
                    return new EvidencePayload(sessionId, kind, seq, total, name, hmac, data);
                }

                @Override
                public void encode(PacketByteBuf buf, EvidencePayload value) {
                    buf.writeVarInt(value.sessionId());
                    buf.writeVarInt(value.kind());
                    buf.writeVarInt(value.seq());
                    buf.writeVarInt(value.total());
                    buf.writeString(value.name());
                    buf.writeString(value.hmac());
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
