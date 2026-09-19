package dev.ifuto.mcsa.admin;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C: サーバーが保存した証拠（画面）を OP のクライアントへ転送するペイロード。
 *
 * <p>並びはサーバー側 {@code dev.ifuto.mcsa.server.net.Wire#encodeShot} と
 * <b>1 バイト単位で同じ</b>。
 *
 * <p>このチャンネルを登録しているのは OP 用 MOD だけなので、
 * サーバーは {@code Player#hasListeningPluginChannel} で相手が受け取れるか確認してから送る。
 */
public record ShotPayload(int transferId, int kind, int seq, int total,
                          String name, byte[] data) implements CustomPayload {

    /** 1 断片あたりの最大バイト数（サーバー側 {@code SessionManager.SHOT_CHUNK} と一致） */
    public static final int CHUNK_SIZE = 16384;

    public static final int KIND_SHOT = 1;
    public static final int KIND_NOTE = 3;

    public static final CustomPayload.Id<ShotPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "shot"));

    public static final PacketCodec<PacketByteBuf, ShotPayload> CODEC =
            new PacketCodec<PacketByteBuf, ShotPayload>() {
                @Override
                public ShotPayload decode(PacketByteBuf buf) {
                    int transferId = buf.readVarInt();
                    int kind = buf.readVarInt();
                    int seq = buf.readVarInt();
                    int total = buf.readVarInt();
                    String name = buf.readString(128);
                    int length = buf.readVarInt();
                    if (length < 0 || length > 1 << 20) {
                        throw new IllegalArgumentException("証拠断片が大きすぎます: " + length);
                    }
                    byte[] data = new byte[length];
                    buf.readBytes(data);
                    return new ShotPayload(transferId, kind, seq, total, name, data);
                }

                @Override
                public void encode(PacketByteBuf buf, ShotPayload value) {
                    buf.writeVarInt(value.transferId());
                    buf.writeVarInt(value.kind());
                    buf.writeVarInt(value.seq());
                    buf.writeVarInt(value.total());
                    buf.writeString(value.name());
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
