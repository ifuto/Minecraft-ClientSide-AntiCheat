package dev.ifuto.mcsa.server;

import dev.ifuto.mcsa.server.alert.AlertService;
import dev.ifuto.mcsa.server.check.CheckManager;
import dev.ifuto.mcsa.server.command.AcCommand;
import dev.ifuto.mcsa.server.evidence.EvidenceStore;
import dev.ifuto.mcsa.server.net.ReportListener;
import dev.ifuto.mcsa.server.net.SessionManager;
import dev.ifuto.mcsa.server.net.Wire;
import dev.ifuto.mcsa.server.policy.InjectionPolicy;
import dev.ifuto.mcsa.server.policy.ModPolicy;
import dev.ifuto.mcsa.server.report.ReportStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * MCSA サーバープラグイン。
 *
 * <p>役割は 3 つ。
 * <ol>
 *   <li>クライアント MOD からの自己申告（MOD / リソースパック / シェーダー / 整合性）を受け取り、
 *       照合して OP がコマンドで確認できるようにする</li>
 *   <li>サーバー権限で観測できる行動（リーチ、自動クリック、高速破壊、浮遊、速度）を検知する</li>
 *   <li>注入（Mixin / javaagent / 改造ローダ）や実行時の状態変化の申告を受け取り、証拠として保存する</li>
 * </ol>
 * 1 と 3 は同一権限の相手には偽装可能なので、確定は 2 と突き合わせて行う。
 */
public final class McsaPlugin extends JavaPlugin {

    private McsaConfig config;
    private SessionManager sessions;
    private ReportStore reports;
    private EvidenceStore evidence;
    private ModPolicy modPolicy;
    private InjectionPolicy injectionPolicy;
    private AlertService alerts;
    private CheckManager checks;
    private BukkitTask challengeTask;
    private BukkitTask watchdogTask;

    /** サーバーが受信するチャンネル（{@link #onDisable()} で同じ一覧を unregister する） */
    public static List<String> incomingChannels() {
        return List.of(Wire.CH_HELLO, Wire.CH_REPORT, Wire.CH_SEAL, Wire.CH_EVIDENCE,
                Wire.CH_DIGEST, Wire.CH_ADMIN);
    }

    /** サーバーが送信するチャンネル */
    public static List<String> outgoingChannels() {
        return List.of(Wire.CH_CHALLENGE, Wire.CH_TASK, Wire.CH_ADMIN_MSG);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        config = new McsaConfig(this);
        config.reload();

        alerts = new AlertService(this);
        reports = new ReportStore(this);
        evidence = new EvidenceStore(this);
        modPolicy = new ModPolicy(this);
        injectionPolicy = new InjectionPolicy(this);
        sessions = new SessionManager(this);
        checks = new CheckManager(this);

        ReportListener listener = new ReportListener(this);
        for (String channel : incomingChannels()) {
            getServer().getMessenger().registerIncomingPluginChannel(this, channel, listener);
        }
        for (String channel : outgoingChannels()) {
            getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
        }

        getServer().getPluginManager().registerEvents(sessions, this);
        getServer().getPluginManager().registerEvents(checks, this);

        PluginCommand command = getCommand("ac");
        if (command != null) {
            AcCommand executor = new AcCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("plugin.yml に /ac が定義されていません");
        }

        scheduleChallenges();
        scheduleWatchdog();
        warnAboutSetup();
        purgeEvidence();

        getLogger().info("MCSA を有効化しました (protocol=" + config.protocol
                + ", enforce=" + config.enforceMode
                + ", action=" + config.enforceAction
                + ", checks=" + (config.checksEnabled ? "on" : "off")
                + ", capture=" + (config.captureEnabled ? "on" : "off") + ")");
    }

    @Override
    public void onDisable() {
        if (challengeTask != null) {
            challengeTask.cancel();
            challengeTask = null;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        for (String channel : incomingChannels()) {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, channel);
        }
        for (String channel : outgoingChannels()) {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, channel);
        }
    }

    /** /ac reload */
    public void reloadAll() {
        config.reload();
        scheduleChallenges();
        scheduleWatchdog();
        warnAboutSetup();
        purgeEvidence();
    }

    /** 保持期間を過ぎた証拠を整理する（evidence.retention-days） */
    private void purgeEvidence() {
        int days = config.evidenceRetentionDays;
        if (days <= 0) {
            return;
        }
        int removed = evidence.purgeOlderThan(days);
        if (removed > 0) {
            getLogger().info("保持期間を過ぎた証拠を " + removed + " 件削除しました (" + days + " 日)");
        }
    }

    private void scheduleChallenges() {
        if (challengeTask != null) {
            challengeTask.cancel();
            challengeTask = null;
        }
        int interval = config.challengeIntervalTicks;
        if (interval > 0) {
            challengeTask = getServer().getScheduler().runTaskTimer(this, sessions::rechallenges, interval, interval);
        }
    }

    /**
     * 定期監視（ウォッチドッグ）。
     *
     * <p>「起動時だけ正常な顔をする」タイプを取りこぼさないための監視。
     * クライアントは一定間隔で状態ダイジェストを送り続け、サーバーは
     * 間隔が開きすぎた相手と、ダイジェストが変わった相手を拾う。
     */
    private void scheduleWatchdog() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        int interval = config.watchdogCheckTicks;
        if (interval > 0 && config.watchdogEnabled) {
            watchdogTask = getServer().getScheduler().runTaskTimer(this, sessions::watchdogTick, interval, interval);
        }
    }

    private void warnAboutSetup() {
        if (config.hmacEnabled && config.hmacKey.length == 0) {
            getLogger().warning("hmac.key が未設定です。クライアントのビルドで生成された "
                    + "build/libs/mcsa-client-<version>-hmac-key.txt の値を config.yml に設定してください。"
                    + "未設定のままではレポートの改ざんを検知できません。");
        }
        if ("OFF".equalsIgnoreCase(config.enforceMode)) {
            getLogger().info("enforce.mode=OFF: 導入必須のチェックは行いません（レポートは受け取ります）");
        }
        if (!config.captureEnabled) {
            getLogger().info("evidence.capture.enabled=false: 画面取得は行いません。"
                    + "有効にする場合はサーバールール等での告知を行ってください (docs/PRIVACY.md)");
        }
    }

    public McsaConfig config() {
        return config;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public ReportStore reports() {
        return reports;
    }

    public EvidenceStore evidence() {
        return evidence;
    }

    public ModPolicy modPolicy() {
        return modPolicy;
    }

    public InjectionPolicy injectionPolicy() {
        return injectionPolicy;
    }

    public AlertService alerts() {
        return alerts;
    }

    public CheckManager checks() {
        return checks;
    }
}
