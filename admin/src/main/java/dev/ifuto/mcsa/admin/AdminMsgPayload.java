package dev.ifuto.mcsa.admin;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C: サーバーから OP のクライアントへの応答。
 *
 * <p>{@code /ac} の実行結果や、証拠が届いた旨の通知をチャットに出す。
 * サーバー側の対応: {@code dev.ifuto.mcsa.server.net.Wire#encodeAdminMessage}
 */
public record AdminMsgPayload(String message) implements CustomPayload {

    public static final CustomPayload.Id<AdminMsgPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "adminmsg"));

    public static final PacketCodec<PacketByteBuf, AdminMsgPayload> CODEC =
            new PacketCodec<PacketByteBuf, AdminMsgPayload>() {
                @Override
                public AdminMsgPayload decode(PacketByteBuf buf) {
                    return new AdminMsgPayload(buf.readString(4096));
                }

                @Override
                public void encode(PacketByteBuf buf, AdminMsgPayload value) {
                    buf.writeString(value.message());
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
