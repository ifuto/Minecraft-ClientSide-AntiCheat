package dev.ifuto.mcsa.server;

import dev.ifuto.mcsa.server.alert.AlertService;
import dev.ifuto.mcsa.server.check.CheckManager;
import dev.ifuto.mcsa.server.command.AcCommand;
import dev.ifuto.mcsa.server.net.ReportListener;
import dev.ifuto.mcsa.server.net.SessionManager;
import dev.ifuto.mcsa.server.net.Wire;
import dev.ifuto.mcsa.server.policy.ModPolicy;
import dev.ifuto.mcsa.server.report.ReportStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * MCSA サーバープラグイン。
 *
 * <p>役割は 2 つ。
 * <ol>
 *   <li>クライアント MOD からの自己申告（MOD / リソースパック / シェーダー / 整合性）を受け取り、
 *       照合して OP がコマンドで確認できるようにする</li>
 *   <li>サーバー権限で観測できる行動（リーチ、自動クリック、高速破壊、浮遊、速度）を検知する</li>
 * </ol>
 * 1 は同一権限の相手には偽装可能なので、確定は 2 と突き合わせて行う。
 */
public final class McsaPlugin extends JavaPlugin {

    private McsaConfig config;
    private SessionManager sessions;
    private ReportStore reports;
    private ModPolicy modPolicy;
    private AlertService alerts;
    private CheckManager checks;
    private BukkitTask challengeTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        config = new McsaConfig(this);
        config.reload();

        alerts = new AlertService(this);
        reports = new ReportStore(this);
        modPolicy = new ModPolicy(this);
        sessions = new SessionManager(this);
        checks = new CheckManager(this);

        ReportListener listener = new ReportListener(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, Wire.CH_HELLO, listener);
        getServer().getMessenger().registerIncomingPluginChannel(this, Wire.CH_REPORT, listener);
        getServer().getMessenger().registerIncomingPluginChannel(this, Wire.CH_SEAL, listener);
        getServer().getMessenger().registerOutgoingPluginChannel(this, Wire.CH_CHALLENGE);

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
        warnAboutSetup();

        getLogger().info("MCSA を有効化しました (protocol=" + config.protocol
                + ", enforce=" + config.enforceMode
                + ", action=" + config.enforceAction
                + ", checks=" + (config.checksEnabled ? "on" : "off") + ")");
    }

    @Override
    public void onDisable() {
        if (challengeTask != null) {
            challengeTask.cancel();
            challengeTask = null;
        }
        getServer().getMessenger().unregisterIncomingChannels(this);
        getServer().getMessenger().unregisterOutgoingChannels(this);
    }

    /** /ac reload */
    public void reloadAll() {
        config.reload();
        scheduleChallenges();
        warnAboutSetup();
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

    private void warnAboutSetup() {
        if (config.hmacEnabled && config.hmacKey.length == 0) {
            getLogger().warning("hmac.key が未設定です。クライアントのビルドで生成された "
                    + "build/libs/mcsa-client-<version>-hmac-key.txt の値を config.yml に設定してください。"
                    + "未設定のままではレポートの改ざんを検知できません。");
        }
        if ("OFF".equalsIgnoreCase(config.enforceMode)) {
            getLogger().info("enforce.mode=OFF: 導入必須のチェックは行いません（レポートは受け取ります）");
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

    public ModPolicy modPolicy() {
        return modPolicy;
    }

    public AlertService alerts() {
        return alerts;
    }

    public CheckManager checks() {
        return checks;
    }
}
