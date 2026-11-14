package com.ifuto.heartsmp;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import com.ifuto.heartsmp.admin.CombatCommand;
import com.ifuto.heartsmp.admin.RankCommand;
import com.ifuto.heartsmp.admin.RollbackCommand;
import com.ifuto.heartsmp.combat.CombatListener;
import com.ifuto.heartsmp.combat.CombatManager;
import com.ifuto.heartsmp.death.DeathListener;
import com.ifuto.heartsmp.death.DeathStore;
import com.ifuto.heartsmp.death.RollbackService;
import com.ifuto.heartsmp.delivery.DeliveryCommand;
import com.ifuto.heartsmp.delivery.DeliveryGui;
import com.ifuto.heartsmp.delivery.DeliveryManager;
import com.ifuto.heartsmp.heart.HeartManager;
import com.ifuto.heartsmp.listener.ConnectionListener;
import com.ifuto.heartsmp.listener.DamageTweaksListener;
import com.ifuto.heartsmp.player.PlayerDataStore;
import com.ifuto.heartsmp.rtp.RtpCommand;
import com.ifuto.heartsmp.rtp.RtpGui;
import com.ifuto.heartsmp.rtp.RtpManager;
import com.ifuto.heartsmp.tpa.TpaCommand;
import com.ifuto.heartsmp.tpa.TpaManager;
import com.ifuto.heartsmp.util.PdcKeys;

/**
 * HeartSMP メインクラス。
 *
 * <p>ハートの受け渡し / Combat / キルログ / TPA / いふと配送 / RTP / 運営コマンドをまとめた
 * Paper 1.21.x 用プラグインです。</p>
 */
public final class HeartSMP extends JavaPlugin {

    private Settings settings;
    private Messages messages;
    private PlayerDataStore playerData;
    private HeartManager hearts;
    private CombatManager combat;
    private DeathStore deaths;
    private RollbackService rollback;
    private TpaManager tpa;
    private DeliveryManager delivery;
    private DeliveryGui deliveryGui;
    private RtpManager rtp;
    private RtpGui rtpGui;
    private CombatCommand combatCommand;
    private RollbackCommand rollbackCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PdcKeys.init(this);

        this.settings = new Settings(getConfig());
        this.messages = new Messages(this);
        this.playerData = new PlayerDataStore(this);
        this.hearts = new HeartManager(this);
        this.combat = new CombatManager(this);
        this.deaths = new DeathStore(this);
        this.rollback = new RollbackService(this);
        this.tpa = new TpaManager(this);
        this.delivery = new DeliveryManager(this);
        this.deliveryGui = new DeliveryGui(this);
        this.rtp = new RtpManager(this);
        this.rtpGui = new RtpGui(this);
        this.combatCommand = new CombatCommand(this);
        this.rollbackCommand = new RollbackCommand(this);

        registerListeners();
        registerCommands();

        combat.start();
        tpa.start();
        delivery.resume();

        getServer().getScheduler().runTaskTimer(this, () -> playerData.saveIfDirty(), 20L * 60L, 20L * 60L);

        getLogger().info("HeartSMP を有効化しました (hearts: " + settings.heartsDefault + ", combat: "
                + (settings.combatMillis / 1000L) + "s)");
    }

    @Override
    public void onDisable() {
        if (combat != null) {
            combat.stop();
        }
        if (tpa != null) {
            tpa.stop();
        }
        if (playerData != null) {
            playerData.saveSync();
        }
        if (delivery != null) {
            delivery.save();
        }
        if (deaths != null) {
            deaths.save();
        }
        if (rollback != null) {
            rollback.save();
        }
        getLogger().info("HeartSMP を無効化しました。");
    }

    private void registerListeners() {
        Listener[] listeners = new Listener[]{
                hearts,
                new CombatListener(this),
                new DeathListener(this),
                new ConnectionListener(this),
                new DamageTweaksListener(this),
                deliveryGui,
                rtpGui,
                combatCommand,
                rollbackCommand
        };
        for (Listener listener : listeners) {
            getServer().getPluginManager().registerEvents(listener, this);
        }
    }

    private void registerCommands() {
        TpaCommand tpaCommand = new TpaCommand(this);
        for (String name : new String[]{"tpa", "tpahere", "tpaccept", "tpadeny", "tpacancel"}) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(tpaCommand);
                command.setTabCompleter(tpaCommand);
            }
        }

        DeliveryCommand deliveryCommand = new DeliveryCommand(this);
        PluginCommand send = getCommand("send");
        if (send != null) {
            send.setExecutor(deliveryCommand);
            send.setTabCompleter(deliveryCommand);
        }
        PluginCommand bagages = getCommand("bagages");
        if (bagages != null) {
            bagages.setExecutor(deliveryCommand);
        }

        PluginCommand rtpCommand = getCommand("rtp");
        if (rtpCommand != null) {
            rtpCommand.setExecutor(new RtpCommand(this));
        }

        PluginCommand combatCmd = getCommand("combat");
        if (combatCmd != null) {
            combatCmd.setExecutor(combatCommand);
            combatCmd.setTabCompleter(combatCommand);
        }

        PluginCommand invrollback = getCommand("inventoryrollback");
        if (invrollback != null) {
            invrollback.setExecutor(rollbackCommand);
            invrollback.setTabCompleter(rollbackCommand);
        }

        RankCommand rankCommand = new RankCommand(this);
        PluginCommand rank = getCommand("rank");
        if (rank != null) {
            rank.setExecutor(rankCommand);
            rank.setTabCompleter(rankCommand);
        }
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public PlayerDataStore playerData() {
        return playerData;
    }

    public HeartManager hearts() {
        return hearts;
    }

    public CombatManager combat() {
        return combat;
    }

    public DeathStore deaths() {
        return deaths;
    }

    public RollbackService rollback() {
        return rollback;
    }

    public TpaManager tpa() {
        return tpa;
    }

    public DeliveryManager delivery() {
        return delivery;
    }

    public DeliveryGui deliveryGui() {
        return deliveryGui;
    }

    public RtpManager rtp() {
        return rtp;
    }

    public RtpGui rtpGui() {
        return rtpGui;
    }

    public CombatCommand combatCommand() {
        return combatCommand;
    }
}
