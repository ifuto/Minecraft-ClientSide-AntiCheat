package dev.ifuto.mcsa.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * S2C: サーバーからの指示。
 *
 * <p>チャレンジ（{@link ChallengePayload}）が「申告しろ」なのに対し、
 * こちらは OP が都度出す指示。
 *
 * <ul>
 *   <li>{@link #KIND_RESCAN} … いまの環境をもう一度申告させる（起動時だけ正常な顔をする対策）</li>
 *   <li>{@link #KIND_CAPTURE} … 画面を取得して送らせる（対象には何も表示しない）</li>
 *   <li>{@link #KIND_NOTE} … 短いテキストだけ返させる（疎通確認）</li>
 *   <li>{@link #KIND_WATCH_ON} … ウォッチドッグの間隔を詰める（{@code intervalSeconds} 秒ごと）</li>
 *   <li>{@link #KIND_WATCH_OFF} … 間隔を既定に戻す</li>
 * </ul>
 *
 * <p>{@code nonce} は証拠（{@link EvidencePayload}）の HMAC に混ぜるため、
 * リクエストごとに新しい値にする。
 */
public record TaskPayload(int protocol, int sessionId, String nonce, int kind,
                          int intervalSeconds, String reason) implements CustomPayload {

    public static final int KIND_RESCAN = 1;
    public static final int KIND_CAPTURE = 2;
    public static final int KIND_NOTE = 3;
    public static final int KIND_WATCH_ON = 4;
    public static final int KIND_WATCH_OFF = 5;

    public static final CustomPayload.Id<TaskPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "task"));

    public static final PacketCodec<PacketByteBuf, TaskPayload> CODEC =
            new PacketCodec<PacketByteBuf, TaskPayload>() {
                @Override
                public TaskPayload decode(PacketByteBuf buf) {
                    return new TaskPayload(buf.readVarInt(), buf.readVarInt(), buf.readString(256),
                            buf.readVarInt(), buf.readVarInt(), buf.readString(256));
                }

                @Override
                public void encode(PacketByteBuf buf, TaskPayload value) {
                    buf.writeVarInt(value.protocol());
                    buf.writeVarInt(value.sessionId());
                    buf.writeString(value.nonce());
                    buf.writeVarInt(value.kind());
                    buf.writeVarInt(value.intervalSeconds());
                    buf.writeString(value.reason());
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
