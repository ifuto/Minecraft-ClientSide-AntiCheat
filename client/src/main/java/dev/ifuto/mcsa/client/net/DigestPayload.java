package dev.ifuto.mcsa.client.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * C2S: ウォッチドッグの状態ダイジェスト（ハートビート）。
 *
 * <p>「起動時だけ正常な顔をする」タイプへの対策。
 * クライアントは一定間隔で「自分の状態の要約」を送り続け、サーバーは
 * <ul>
 *   <li>ダイジェストが前回と変わった → 実行時になにか入れ替わった</li>
 *   <li>送ってこなくなった → MOD が止まった／殺された</li>
 * </ul>
 * を拾う。2 段階認証と同じで、<b>1 回通ったことよりも「通らなくなったこと」を証拠にする</b>。
 *
 * <p>{@code stateHex} は自己 jar のハッシュ・Mixin 設定の一覧・読み込み中ライブラリの
 * 一覧・クラスローダの連鎖などから作った FNV-1a の 16 進表記。中身は送らない
 * （毎回送るとレポートが肥大化するため）。変わったら次のレポートで詳細を送る。
 */
public record DigestPayload(int sessionId, int seq, String stateHex, int flags,
                            int intervalSeconds) implements CustomPayload {

    /** 自分の jar のハッシュが起動時と違う */
    public static final int FLAG_SELF_JAR_CHANGED = 1;
    /** Mixin の設定一覧が変わった（実行時に注入された） */
    public static final int FLAG_MIXIN_CHANGED = 1 << 1;
    /** 読み込み中のライブラリ一覧が変わった */
    public static final int FLAG_LIBRARY_CHANGED = 1 << 2;
    /** 前回は無かった検知対象クラスが現れた */
    public static final int FLAG_PROBE_APPEARED = 1 << 3;
    /** クラスローダの連鎖が変わった */
    public static final int FLAG_CLASSLOADER_CHANGED = 1 << 4;
    /** 画面取得が可能 */
    public static final int FLAG_CAPTURE_SUPPORTED = 1 << 5;

    public static final CustomPayload.Id<DigestPayload> ID =
            new CustomPayload.Id<>(Identifier.of("mcsa", "digest"));

    public static final PacketCodec<PacketByteBuf, DigestPayload> CODEC =
            new PacketCodec<PacketByteBuf, DigestPayload>() {
                @Override
                public DigestPayload decode(PacketByteBuf buf) {
                    return new DigestPayload(buf.readVarInt(), buf.readVarInt(), buf.readString(32),
                            buf.readVarInt(), buf.readVarInt());
                }

                @Override
                public void encode(PacketByteBuf buf, DigestPayload value) {
                    buf.writeVarInt(value.sessionId());
                    buf.writeVarInt(value.seq());
                    buf.writeString(value.stateHex());
                    buf.writeVarInt(value.flags());
                    buf.writeVarInt(value.intervalSeconds());
                }
            };

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
