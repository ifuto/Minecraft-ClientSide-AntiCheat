package dev.ifuto.mcsa.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S2C: サーバからのチャレンジ。
 *
 * <p>このペイロードを受け取ったクライアントだけがレポートを返す。
 * {@code nonce} はレポートの HMAC に混ぜるため、リプレイ（同じレポートを使い回す）を防ぐ。
 * {@code probeClasses} はサーバが指定する「探してほしいクラス名」の一覧で、
 * クライアント MOD を更新せずに検知対象を増やせるようにするための仕組み。
 */
public record ChallengePayload(int protocol, int sessionId, String nonce, String serverId,
                               List<String> probeClasses, int collectFlags) implements CustomPayload {

    public static final CustomPayload.Id<ChallengePayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "challenge"));

    /** collectFlags のビット */
    public static final int FLAG_MODS = 1;
    public static final int FLAG_RESOURCE_PACKS = 1 << 1;
    public static final int FLAG_SHADER_PACKS = 1 << 2;
    public static final int FLAG_JVM_ARGS = 1 << 3;

    public static final PacketCodec<PacketByteBuf, ChallengePayload> CODEC =
            new PacketCodec<PacketByteBuf, ChallengePayload>() {
                @Override
                public ChallengePayload decode(PacketByteBuf buf) {
                    int protocol = buf.readVarInt();
                    int sessionId = buf.readVarInt();
                    String nonce = buf.readString(256);
                    String serverId = buf.readString(256);
                    int count = Math.min(buf.readVarInt(), 256);
                    List<String> probes = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        probes.add(buf.readString(512));
                    }
                    int collectFlags = buf.readVarInt();
                    return new ChallengePayload(protocol, sessionId, nonce, serverId,
                            Collections.unmodifiableList(probes), collectFlags);
                }

                @Override
                public void encode(PacketByteBuf buf, ChallengePayload value) {
                    buf.writeVarInt(value.protocol());
                    buf.writeVarInt(value.sessionId());
                    buf.writeString(value.nonce());
                    buf.writeString(value.serverId());
                    List<String> probes = value.probeClasses();
                    buf.writeVarInt(probes.size());
                    for (String probe : probes) {
                        buf.writeString(probe);
                    }
                    buf.writeVarInt(value.collectFlags());
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
