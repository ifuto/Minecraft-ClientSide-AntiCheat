package dev.ifuto.lessping.relay;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;


import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LessPing-NArena 中継プラグイン（シグナリング）。
 *
 * <p>やることは「アドレスの紹介」だけ:
 * <ol>
 *   <li>LessPing MOD 入りのクライアントが参加すると HELLO（自分の UDP アドレス）を送ってくる</li>
 *   <li>プレイヤーが INTRO_REQUEST で「このプレイヤーのアドレスを教えて」と聞く</li>
 *   <li>両者へ INTRO（相手のアドレス）を送る → 両者は UDP ホールパンチで直接つながる</li>
 * </ol>
 *
 * <p>ゲームの通信はこのサーバーを経由しない（P2P）。ホールパンチが失敗する
 * （対称 NAT 等）場合は通常接続にフォールバックするだけ。
 */
public final class LessPingRelayPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    /** クライアント → サーバー（HELLO / INTRO_REQUEST） */
    public static final String CHANNEL_IN = "lessping:signal";
    /** サーバー → クライアント（INTRO） */
    public static final String CHANNEL_OUT = "lessping:events";

    private static final int TYPE_HELLO = 1;
    private static final int TYPE_INTRO_REQUEST = 2;
    private static final int TYPE_INTRO = 3;

    /** 登録されたプレイヤー（小文字名 → 情報） */
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    /** まだオフラインの対象への紹介要求（対象の小文字名 → 要求者の小文字名） */
    private final Map<String, Set<String>> pendingRequests = new ConcurrentHashMap<>();

    private record PeerInfo(String name, String data, long at) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL_IN, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL_OUT);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LessPing-NArena 中継を開始（"
                + getDescription().getVersion() + "）。ゲーム通信は経由しません（P2P）。");
    }

    @Override
    public void onDisable() {
        peers.clear();
        pendingRequests.clear();
    }

    // ------------------------------------------------------------------ 受信

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!CHANNEL_IN.equals(channel)) {
            return;
        }
        Wire.Msg msg = Wire.parse(bytes);
        if (msg == null) {
            getLogger().warning(() -> player.getName() + " から解析できないメッセージ（"
                    + bytes.length + " bytes）");
            return;
        }
        switch (msg.type()) {
            case TYPE_HELLO -> onHello(player, msg);
            case TYPE_INTRO_REQUEST -> onIntroRequest(player, msg);
            default -> {
                // 知らないタイプは無視
            }
        }
    }

    private void onHello(Player player, Wire.Msg msg) {
        // 名前は偽装できないように実際の接続者を使う
        peers.put(player.getName().toLowerCase(Locale.ROOT),
                new PeerInfo(player.getName(), msg.data(), System.currentTimeMillis()));
        if (debug()) {
            getLogger().info(() -> player.getName() + " の HELLO: " + msg.data());
        }
        // このプレイヤーへの紹介要求が溜まっていたら応える
        Set<String> waiting = pendingRequests.remove(player.getName().toLowerCase(Locale.ROOT));
        if (waiting != null) {
            for (String requesterName : waiting) {
                Player requester = Bukkit.getPlayerExact(requesterName);
                if (requester != null && requester.isOnline()) {
                    introduce(requester, player);
                }
            }
        }
    }

    private void onIntroRequest(Player player, Wire.Msg msg) {
        String targetName = msg.player();
        PeerInfo target = peers.get(targetName.toLowerCase(Locale.ROOT));
        long freshness = getConfig().getLong("freshness-ms", 600_000L);
        if (target != null && Bukkit.getPlayerExact(target.name()) != null
                && System.currentTimeMillis() - target.at() < freshness) {
            Player targetPlayer = Bukkit.getPlayerExact(target.name());
            // 双方へ紹介（両者が同時に穴あけを始められるように）
            introduce(player, targetPlayer);
            introduce(targetPlayer, player);
        } else {
            // 対象がまだ来ていない → 対象の参加時に紹介する
            pendingRequests.computeIfAbsent(targetName.toLowerCase(Locale.ROOT),
                    k -> ConcurrentHashMap.newKeySet()).add(player.getName());
            send(player, TYPE_INTRO, targetName, ""); // 空データ = まだ居ない、の合図
            if (debug()) {
                getLogger().info(() -> player.getName() + " が " + targetName
                        + " を要求したが、まだ登録がない");
            }
        }
    }

    /** about のアドレスを to へ教える */
    private void introduce(Player to, Player about) {
        PeerInfo info = peers.get(about.getName().toLowerCase(Locale.ROOT));
        String data = info == null ? "" : info.data();
        send(to, TYPE_INTRO, about.getName(), data);
        if (debug()) {
            getLogger().info(() -> to.getName() + " へ " + about.getName() + " を紹介: " + data);
        }
    }

    private void send(Player to, int type, String player, String data) {
        try {
            to.sendPluginMessage(this, CHANNEL_OUT, Wire.build(type, player, data));
        } catch (Exception e) {
            getLogger().warning(() -> to.getName() + " への送信に失敗: " + e);
        }
    }

    // ------------------------------------------------------------------ イベント

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String key = event.getPlayer().getName().toLowerCase(Locale.ROOT);
        peers.remove(key);
        pendingRequests.values().forEach(set -> set.remove(event.getPlayer().getName()));
    }

    // ------------------------------------------------------------------ コマンド

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> names = peers.values().stream().map(PeerInfo::name).sorted().toList();
        sender.sendMessage("§6[LessPing-NArena] §7中継 v" + getDescription().getVersion());
        sender.sendMessage("§7登録済み: §f" + (names.isEmpty() ? "なし" : String.join(", ", names)));
        if (!pendingRequests.isEmpty()) {
            sender.sendMessage("§7待機中の紹介要求: " + pendingRequests.size() + " 件");
        }
        for (PeerInfo info : peers.values()) {
            sender.sendMessage("§8- " + info.name() + ": " + abbreviate(info.data(), 100));
        }
        sender.sendMessage("§7（ゲーム通信は経由しません。P2P の紹介だけです）");
        return true;
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private boolean debug() {
        return getConfig().getBoolean("debug", false);
    }
}
