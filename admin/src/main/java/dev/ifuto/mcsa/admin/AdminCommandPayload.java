package dev.ifuto.mcsa.admin;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: OP のクライアントからサーバーへ送るコマンド。
 *
 * <p>中身は {@code /ac} の引数そのもの（例: {@code "shot Steve reason"}）。
 * 権限判定はサーバー側（{@code mcsa.admin}）で行うので、この MOD を入れていても
 * 権限がなければ何もできない。
 *
 * <p>サーバー側の対応: {@code dev.ifuto.mcsa.server.net.SessionManager#onAdmin}
 */
public record AdminCommandPayload(String command) implements CustomPayload {

    public static final CustomPayload.Id<AdminCommandPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "admin"));

    public static final PacketCodec<PacketByteBuf, AdminCommandPayload> CODEC =
            new PacketCodec<PacketByteBuf, AdminCommandPayload>() {
                @Override
                public AdminCommandPayload decode(PacketByteBuf buf) {
                    return new AdminCommandPayload(buf.readString(4096));
                }

                @Override
                public void encode(PacketByteBuf buf, AdminCommandPayload value) {
                    buf.writeString(value.command());
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
