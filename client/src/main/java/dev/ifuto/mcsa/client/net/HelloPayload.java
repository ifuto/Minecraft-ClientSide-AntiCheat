package dev.ifuto.mcsa.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: 最小限の自己申告。「MOD が入っている」ことの一次判定に使う。
 * 重い処理（ファイルハッシュ）を待たずに返すので、入室直後の判定が速い。
 *
 * @param flags bit0: 難読化ビルド, bit1: 自己整合性チェック OK, bit2: 前回レポートのキャッシュを利用
 */
public record HelloPayload(int protocol, String modVersion, String selfJarSha256,
                           String keyId, int flags) implements CustomPayload {

    public static final int FLAG_OBFUSCATED = 1;
    public static final int FLAG_SELF_CHECK_OK = 1 << 1;
    public static final int FLAG_CACHED_REPORT = 1 << 2;

    public static final CustomPayload.Id<HelloPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "hello"));

    public static final PacketCodec<PacketByteBuf, HelloPayload> CODEC =
            new PacketCodec<PacketByteBuf, HelloPayload>() {
                @Override
                public HelloPayload decode(PacketByteBuf buf) {
                    return new HelloPayload(buf.readVarInt(), buf.readString(64), buf.readString(128),
                            buf.readString(64), buf.readVarInt());
                }

                @Override
                public void encode(PacketByteBuf buf, HelloPayload value) {
                    buf.writeVarInt(value.protocol());
                    buf.writeString(value.modVersion());
                    buf.writeString(value.selfJarSha256());
                    buf.writeString(value.keyId());
                    buf.writeVarInt(value.flags());
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
