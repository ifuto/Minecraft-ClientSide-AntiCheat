package dev.ifuto.mcsa.server.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ifuto.mcsa.server.McsaConfig;
import dev.ifuto.mcsa.server.McsaPlugin;
import dev.ifuto.mcsa.server.alert.AlertService;
import dev.ifuto.mcsa.server.net.Session;
import dev.ifuto.mcsa.server.net.Wire;
import dev.ifuto.mcsa.server.report.ClientReport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code /ac} — OP が導入状況・MOD・リソースパック・シェーダーを確認するためのコマンド。 */
public final class AcCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 10;

    private final McsaPlugin plugin;

    public AcCommand(McsaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mcsa.admin")) {
            sender.sendMessage(text("権限がありません (mcsa.admin)", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "info" -> info(sender, args);
            case "mods" -> mods(sender, args);
            case "packs" -> packs(sender, args);
            case "shaders" -> shaders(sender, args);
            case "flags" -> flags(sender, args);
            case "refresh" -> refresh(sender, args);
            case "scan" -> scan(sender, args);
            case "shot" -> shot(sender, args);
            case "watch" -> watch(sender, args);
            case "evidence" -> evidence(sender, args);
            case "kick" -> kick(sender, args);
            case "policy" -> policy(sender, args);
            case "reload" -> reload(sender);
            default -> help(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------------ 一覧

    private void status(CommandSender sender) {
        McsaConfig config = plugin.config();
        sender.sendMessage(header("導入状況 (mode=" + config.enforceMode + ", action=" + config.enforceAction + ")"));
        boolean any = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            any = true;
            Session session = plugin.sessions().session(player);
            ClientReport report = plugin.reports().get(player);
            boolean installed = session != null && session.hasMod();
            String modInfo = report != null ? report.modVersion() : "-";
            String flagInfo = report == null ? "-" : (report.flags().isEmpty() ? "clean" : report.flags().size() + "件");
            int vl = plugin.checks().violations(player).values().stream().mapToInt(Integer::intValue).sum();
            Component line = Component.text(" " + player.getName(), installed ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .append(text("  導入=" + (installed ? "YES" : "NO"), installed ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .append(text("  ver=" + modInfo, NamedTextColor.GRAY))
                    .append(text("  flags=" + flagInfo,
                            report != null && report.hasCritical() ? NamedTextColor.RED : NamedTextColor.GRAY))
                    .append(text("  VL=" + vl, vl > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY))
                    .append(text("  [詳細]", NamedTextColor.AQUA, ClickEvent.runCommand("/ac info " + player.getName())));
            sender.sendMessage(line);
        }
        if (!any) {
            sender.sendMessage(text(" オンラインのプレイヤーはいません", NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ 詳細

    private void info(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        Session session = plugin.sessions().session(target);
        ClientReport report = plugin.reports().get(target);
        McsaConfig config = plugin.config();

        sender.sendMessage(header(target.getName() + " の状態"));
        sender.sendMessage(kv("導入", session != null && session.hasMod() ? "YES" : "NO",
                session != null && session.hasMod() ? NamedTextColor.GREEN : NamedTextColor.RED));
        sender.sendMessage(kv("導入必須", config.isRequired(target) ? "YES" : "no", NamedTextColor.GRAY));
        sender.sendMessage(kv("チャレンジ送信", session == null ? "-" : session.challengeCount() + " 回", NamedTextColor.GRAY));
        if (session != null && session.lastError() != null) {
            sender.sendMessage(kv("最終エラー", session.lastError(), NamedTextColor.YELLOW));
        }
        if (session != null && session.hello() != null) {
            sender.sendMessage(kv("クライアント", session.hello().modVersion()
                    + "  jar=" + abbreviate(session.hello().selfJarSha256())
                    + "  keyId=" + session.hello().keyId(), NamedTextColor.GRAY));
        }
        if (report == null) {
            sender.sendMessage(text(" レポートはまだ届いていません。/ac " + "refresh " + target.getName()
                    + " で再要求できます。", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(kv("レポート", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(report.receivedAt())), NamedTextColor.GRAY));
        sender.sendMessage(kv("HMAC", report.hmacValid() ? "OK" : "NG (改ざん or 鍵不一致)",
                report.hmacValid() ? NamedTextColor.GREEN : NamedTextColor.RED));
        sender.sendMessage(kv("難読化", report.obfuscated() ? "YES" : "NO",
                report.obfuscated() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        sender.sendMessage(kv("クライアント jar", abbreviate(report.selfJarSha256()) + " (" + report.selfJarName() + ")",
                NamedTextColor.GRAY));
        sender.sendMessage(kv("MOD / パック / シェーダー", report.modCount() + " / "
                + report.resourcePacks().size() + " / " + shaderLabel(report), NamedTextColor.GRAY));
        sender.sendMessage(kv("注入観測", plugin.injectionPolicy().summarize(report),
                report.findings().isEmpty() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        sender.sendMessage(kv("フラグ", report.flagSummary(),
                report.hasCritical() ? NamedTextColor.RED : (report.flags().isEmpty() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        if (!report.redacted().isEmpty()) {
            sender.sendMessage(kv("送信拒否", String.join(", ", report.redacted()), NamedTextColor.RED));
        }
        if (session != null) {
            long age = session.lastDigestAt() == 0 ? -1
                    : (System.currentTimeMillis() - session.lastDigestAt()) / 1000;
            sender.sendMessage(kv("常時監視", age < 0 ? "ダイジェスト未受信（MOD が古い可能性）"
                            : age + " 秒前 (seq=" + session.digestSeq() + ", state=" + session.lastStateHex() + ")",
                    age < 0 ? NamedTextColor.YELLOW : (age > plugin.config().watchdogTimeoutSeconds
                            ? NamedTextColor.RED : NamedTextColor.GREEN)));
            sender.sendMessage(kv("画面取得", (plugin.config().captureEnabled ? "サーバー許可" : "サーバー禁止")
                            + " / " + (session.captureSupported() ? "クライアント対応" : "クライアント未対応"),
                    plugin.config().captureEnabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            if (!session.runtimeFlags().isEmpty()) {
                sender.sendMessage(kv("実行時変化", String.join(", ", session.runtimeFlags()), NamedTextColor.RED));
            }
        }
        Map<String, Integer> violations = plugin.checks().violations(target);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            violations.forEach((key, value) -> sb.append(key).append('=').append(value).append(' '));
            sender.sendMessage(kv("行動検知 VL", sb.toString().trim(), NamedTextColor.YELLOW));
        }
    }

    private void mods(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        ClientReport report = report(sender, target);
        if (report == null) {
            return;
        }
        JsonArray mods = report.mods();
        int pages = Math.max(1, (mods.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = page(args, 2, pages);
        sender.sendMessage(header(target.getName() + " の MOD (" + mods.size() + " 件) " + page + "/" + pages));

        int index = 0;
        for (JsonElement element : mods) {
            int current = index++;
            if (current < (page - 1) * PAGE_SIZE || current >= page * PAGE_SIZE) {
                continue;
            }
            JsonObject mod = element.getAsJsonObject();
            String id = text(mod, "id");
            String sha = text(mod, "sha256");
            String verdict = plugin.modPolicy().verdict(id, sha);
            NamedTextColor color = switch (verdict) {
                case "BANNED", "ID_SPOOF" -> NamedTextColor.RED;
                case "SUSPICIOUS" -> NamedTextColor.YELLOW;
                case "PINNED", "ALLOWED" -> NamedTextColor.GREEN;
                default -> NamedTextColor.GRAY;
            };
            sender.sendMessage(Component.text(" " + id, color)
                    .append(text("  " + text(mod, "version"), NamedTextColor.DARK_GRAY))
                    .append(text("  [" + verdict + "]", color))
                    .append(text("  " + abbreviate(sha), NamedTextColor.DARK_GRAY)));
        }

        JsonArray jars = report.modJars();
        int problems = 0;
        for (JsonElement element : jars) {
            JsonObject jar = element.getAsJsonObject();
            boolean noManifest = jar.has("manifest") && !jar.get("manifest").getAsBoolean();
            boolean notLoaded = jar.has("loaded") && !jar.get("loaded").getAsBoolean()
                    && jar.has("manifest") && jar.get("manifest").getAsBoolean();
            if (noManifest || notLoaded) {
                if (problems++ == 0) {
                    sender.sendMessage(text(" mods/ の気になるファイル:", NamedTextColor.RED));
                }
                sender.sendMessage(text("  " + text(jar, "file")
                        + (noManifest ? " (fabric.mod.json なし＝MODとして読み込まれない注入物)" : " (読み込まれていない)"),
                        NamedTextColor.RED));
            }
        }
        if (pages > 1) {
            sender.sendMessage(text(" 続き: /ac mods " + target.getName() + " " + (page + 1), NamedTextColor.DARK_GRAY));
        }
    }

    private void packs(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        ClientReport report = report(sender, target);
        if (report == null) {
            return;
        }
        JsonArray packs = report.resourcePacks();
        sender.sendMessage(header(target.getName() + " のリソースパック (有効 " + packs.size() + " 件)"));
        for (JsonElement element : packs) {
            sender.sendMessage(text(" " + element.getAsString(), NamedTextColor.GRAY));
        }
        JsonArray files = report.resourcePackFiles();
        if (files.size() > 0) {
            sender.sendMessage(text(" resourcepacks/ (" + files.size() + " 件)", NamedTextColor.DARK_GRAY));
            int index = 0;
            for (JsonElement element : files) {
                if (index++ >= PAGE_SIZE) {
                    sender.sendMessage(text("  …ほか " + (files.size() - PAGE_SIZE) + " 件", NamedTextColor.DARK_GRAY));
                    break;
                }
                JsonObject file = element.getAsJsonObject();
                sender.sendMessage(text("  " + text(file, "name") + "  " + bytes(file), NamedTextColor.GRAY)
                        .append(text("  " + abbreviate(text(file, "sha256")), NamedTextColor.DARK_GRAY)));
            }
        }
    }

    private void shaders(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        ClientReport report = report(sender, target);
        if (report == null) {
            return;
        }
        JsonObject shaders = report.shaders();
        sender.sendMessage(header(target.getName() + " のシェーダー"));
        sender.sendMessage(kv("ローダー", text(shaders, "loader").isEmpty() ? "-" : text(shaders, "loader"),
                NamedTextColor.GRAY));
        sender.sendMessage(kv("パック", text(shaders, "pack").isEmpty() ? "-" : text(shaders, "pack"), NamedTextColor.GRAY));
        if (shaders.has("enabled")) {
            sender.sendMessage(kv("適用中", shaders.get("enabled").getAsBoolean() ? "YES" : "no", NamedTextColor.GRAY));
        }
        JsonArray files = report.shaderPackFiles();
        if (files.size() > 0) {
            sender.sendMessage(text(" shaderpacks/ (" + files.size() + " 件)", NamedTextColor.DARK_GRAY));
            int index = 0;
            for (JsonElement element : files) {
                if (index++ >= PAGE_SIZE) {
                    sender.sendMessage(text("  …ほか " + (files.size() - PAGE_SIZE) + " 件", NamedTextColor.DARK_GRAY));
                    break;
                }
                JsonObject file = element.getAsJsonObject();
                sender.sendMessage(text("  " + text(file, "name") + "  " + bytes(file), NamedTextColor.GRAY)
                        .append(text("  " + abbreviate(text(file, "sha256")), NamedTextColor.DARK_GRAY)));
            }
        }
    }

    private void flags(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        sender.sendMessage(header(target.getName() + " の検知履歴"));
        ClientReport report = plugin.reports().get(target);
        sender.sendMessage(kv("レポート", report == null ? "なし" : report.flagSummary(),
                report != null && report.hasCritical() ? NamedTextColor.RED : NamedTextColor.GRAY));
        Map<String, Integer> violations = plugin.checks().violations(target);
        if (violations.isEmpty()) {
            sender.sendMessage(kv("行動検知", "なし", NamedTextColor.GREEN));
        } else {
            violations.forEach((check, level) -> sender.sendMessage(kv("行動検知 " + check, "VL=" + level,
                    level >= plugin.config().alertVl ? NamedTextColor.RED : NamedTextColor.YELLOW)));
        }
    }

    // ------------------------------------------------------------------ 操作

    private void refresh(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        if (plugin.sessions().sendChallenge(target)) {
            sender.sendMessage(text(" " + target.getName() + " にレポートを再要求しました。", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(text(" 送信に失敗しました（プレイヤーがオフラインです）", NamedTextColor.RED));
        }
    }

    /** 新しい nonce で環境を申告し直させる（起動時だけ正常な顔をする対策） */
    private void scan(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        boolean sent = plugin.sessions().sendTask(target, Wire.TASK_RESCAN, "rescan", 0, sender.getName());
        sender.sendMessage(sent
                ? text(" " + target.getName() + " に再申告を指示しました（新しい nonce で再収集します）", NamedTextColor.GREEN)
                : text(" 指示を送れませんでした（オフラインです）", NamedTextColor.RED));
    }

    /** 画面を取得する（対象には何も表示されない） */
    private void shot(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
        String message = plugin.sessions().requestCapture(target, reason, sender.getName());
        boolean ok = message.startsWith("要求しました");
        sender.sendMessage(text(" " + message, ok ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    /** 監視モード（高頻度のダイジェスト＋定期的な画面取得） */
    private void watch(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(text(" /ac watch <player> <seconds|off>", NamedTextColor.RED));
            return;
        }
        if (args[2].equalsIgnoreCase("off")) {
            sender.sendMessage(text(" " + plugin.sessions().stopWatch(target, sender.getName()), NamedTextColor.GREEN));
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(text(" 秒数を指定してください: /ac watch <player> <seconds|off>", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(text(" " + plugin.sessions().startWatch(target, seconds, sender.getName()),
                NamedTextColor.GREEN));
    }

    /** 保存済みの証拠を一覧する */
    private void evidence(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        List<Path> files = plugin.evidence().list(target, 20);
        sender.sendMessage(header(target.getName() + " の証拠 (" + files.size() + " 件 / 最大20件表示)"));
        if (files.isEmpty()) {
            sender.sendMessage(text(" 保存された証拠はありません。/ac shot " + target.getName()
                    + " で画面を取得できます（evidence.capture.enabled=true が必要）。", NamedTextColor.GRAY));
            return;
        }
        for (Path file : files) {
            sender.sendMessage(text(" " + file, NamedTextColor.AQUA));
        }
        sender.sendMessage(text(" 保存先: " + plugin.evidence().root(), NamedTextColor.GRAY));
        sender.sendMessage(text(" 監査ログ: " + plugin.evidence().root().resolve("evidence-log.txt"),
                NamedTextColor.GRAY));

        // 自分が OP 用 MOD を入れていたら、指定の 1 件（既定は最新）を自分のクライアントへ転送する
        int index = 0;
        if (args.length > 2) {
            try {
                index = Math.max(0, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                index = 0;
            }
        }
        if (sender instanceof Player admin && files.size() > index) {
            Path file = files.get(index);
            try {
                byte[] data = Files.readAllBytes(file);
                int kind = file.getFileName().toString().endsWith(".txt")
                        ? Wire.EVIDENCE_NOTE : Wire.EVIDENCE_SHOT;
                if (plugin.sessions().forwardEvidence(admin, kind, file.getFileName().toString(), data)) {
                    sender.sendMessage(text(" " + file.getFileName() + " をあなたのクライアントに転送しました",
                            NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(text(" OP 用 MOD (mcsa-admin) が入っていないため転送できません。"
                            + "サーバー上のファイルを直接開いてください。", NamedTextColor.YELLOW));
                }
            } catch (java.io.IOException e) {
                sender.sendMessage(text(" ファイルを読めませんでした: " + e, NamedTextColor.RED));
            }
        }
    }

    private void kick(CommandSender sender, String[] args) {
        Player target = target(sender, args, 1);
        if (target == null) {
            return;
        }
        String reason = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : plugin.config().bannedKickMessage;
        target.kick(AlertService.legacy(reason));
        sender.sendMessage(text(" " + target.getName() + " をキックしました。", NamedTextColor.GREEN));
    }

    private void policy(CommandSender sender, String[] args) {
        McsaConfig config = plugin.config();
        if (args.length < 2) {
            sender.sendMessage(header("ポリシー (mode=" + config.enforceMode + ")"));
            sender.sendMessage(text(" /ac policy mode <OFF|LISTED|EVERYONE>", NamedTextColor.GRAY));
            sender.sendMessage(text(" /ac policy require add|remove|list <player>", NamedTextColor.GRAY));
            sender.sendMessage(text(" /ac policy pin <player> <modId> … 信頼できるクライアントのハッシュを固定", NamedTextColor.GRAY));
            sender.sendMessage(text(" /ac policy pin-client <player> … 配布クライアント jar のハッシュを固定", NamedTextColor.GRAY));
            sender.sendMessage(text(" /ac policy probe list|add|remove <class> … 探索クラス名", NamedTextColor.GRAY));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "mode" -> {
                if (args.length < 3) {
                    sender.sendMessage(text(" 現在: " + config.enforceMode, NamedTextColor.GRAY));
                    return;
                }
                String mode = args[2].toUpperCase(Locale.ROOT);
                if (!mode.equals("OFF") && !mode.equals("LISTED") && !mode.equals("EVERYONE")) {
                    sender.sendMessage(text(" OFF / LISTED / EVERYONE のいずれかを指定してください", NamedTextColor.RED));
                    return;
                }
                plugin.getConfig().set("enforce.mode", mode);
                plugin.saveConfig();
                config.reload();
                sender.sendMessage(text(" enforce.mode = " + mode, NamedTextColor.GREEN));
            }
            case "require" -> {
                String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
                switch (action) {
                    case "add" -> {
                        if (args.length < 4) {
                            sender.sendMessage(text(" /ac policy require add <player>", NamedTextColor.RED));
                            return;
                        }
                        config.addRequired(args[3]);
                        sender.sendMessage(text(" 導入必須に追加: " + args[3], NamedTextColor.GREEN));
                    }
                    case "remove" -> {
                        if (args.length < 4) {
                            sender.sendMessage(text(" /ac policy require remove <player>", NamedTextColor.RED));
                            return;
                        }
                        boolean removed = config.removeRequired(args[3]);
                        sender.sendMessage(text(removed ? " 削除しました: " + args[3] : " 見つかりません: " + args[3],
                                removed ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                    }
                    default -> {
                        sender.sendMessage(header("導入必須リスト (" + config.requiredPlayers.size() + ")"));
                        config.requiredPlayers.forEach(entry ->
                                sender.sendMessage(text(" " + entry, NamedTextColor.GRAY)));
                    }
                }
            }
            case "pin" -> {
                if (args.length < 4) {
                    sender.sendMessage(text(" /ac policy pin <player> <modId>", NamedTextColor.RED));
                    return;
                }
                Player source = Bukkit.getPlayerExact(args[2]);
                if (source == null) {
                    sender.sendMessage(text(" プレイヤーが見つかりません: " + args[2], NamedTextColor.RED));
                    return;
                }
                ClientReport report = plugin.reports().get(source);
                if (report == null) {
                    sender.sendMessage(text(" " + args[2] + " のレポートがまだありません", NamedTextColor.RED));
                    return;
                }
                String modId = args[3].toLowerCase(Locale.ROOT);
                String sha = null;
                for (JsonElement element : report.mods()) {
                    JsonObject mod = element.getAsJsonObject();
                    if (modId.equals(text(mod, "id").toLowerCase(Locale.ROOT)) && !text(mod, "sha256").isEmpty()) {
                        sha = text(mod, "sha256");
                        break;
                    }
                }
                if (sha == null) {
                    sender.sendMessage(text(" ハッシュが見つかりません: " + args[3], NamedTextColor.RED));
                    return;
                }
                config.pins.computeIfAbsent(modId, key -> new java.util.LinkedHashSet<>()).add(sha.toLowerCase(Locale.ROOT));
                config.savePins();
                sender.sendMessage(text(" 固定: " + modId + " = " + abbreviate(sha), NamedTextColor.GREEN));
            }
            case "pin-client" -> {
                if (args.length < 3) {
                    sender.sendMessage(text(" /ac policy pin-client <player>", NamedTextColor.RED));
                    return;
                }
                Player source = Bukkit.getPlayerExact(args[2]);
                ClientReport report = source == null ? null : plugin.reports().get(source);
                if (report == null) {
                    sender.sendMessage(text(" レポートが見つかりません: " + args[2], NamedTextColor.RED));
                    return;
                }
                String sha = report.selfJarSha256();
                if (sha.isEmpty()) {
                    sender.sendMessage(text(" クライアント jar のハッシュがありません", NamedTextColor.RED));
                    return;
                }
                config.pinnedClientJars.add(sha.toLowerCase(Locale.ROOT));
                config.savePins();
                sender.sendMessage(text(" クライアント jar を固定: " + abbreviate(sha), NamedTextColor.GREEN));
            }
            case "probe" -> {
                String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "list";
                List<String> probes = new ArrayList<>(plugin.getConfig().getStringList("challenge.probe-classes"));
                switch (action) {
                    case "add" -> {
                        if (args.length < 4) {
                            sender.sendMessage(text(" /ac policy probe add <className>", NamedTextColor.RED));
                            return;
                        }
                        if (!probes.contains(args[3])) {
                            probes.add(args[3]);
                        }
                        plugin.getConfig().set("challenge.probe-classes", probes);
                        plugin.saveConfig();
                        config.reload();
                        sender.sendMessage(text(" 追加: " + args[3], NamedTextColor.GREEN));
                    }
                    case "remove" -> {
                        if (args.length < 4) {
                            sender.sendMessage(text(" /ac policy probe remove <className>", NamedTextColor.RED));
                            return;
                        }
                        probes.remove(args[3]);
                        plugin.getConfig().set("challenge.probe-classes", probes);
                        plugin.saveConfig();
                        config.reload();
                        sender.sendMessage(text(" 削除: " + args[3], NamedTextColor.GREEN));
                    }
                    default -> {
                        sender.sendMessage(header("探索クラス (" + probes.size() + ")"));
                        probes.forEach(entry -> sender.sendMessage(text(" " + entry, NamedTextColor.GRAY)));
                    }
                }
            }
            default -> sender.sendMessage(text(" 不明なサブコマンド: " + args[1], NamedTextColor.RED));
        }
    }

    private void reload(CommandSender sender) {
        plugin.reloadAll();
        sender.sendMessage(text(" 設定を読み直しました (mode=" + plugin.config().enforceMode
                + ", hmac=" + (plugin.config().hmacKey.length > 0 ? "設定済み" : "未設定") + ")", NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ 補助

    private void help(CommandSender sender, String label) {
        sender.sendMessage(header("MCSA — クライアント連携アンチチート"));
        sender.sendMessage(text(" /" + label + " status … オンラインの導入状況", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " info <player> … 詳細（整合性・フラグ）", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " mods <player> [page] … MOD 一覧", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " packs <player> … リソースパック一覧", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " shaders <player> … シェーダー一覧", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " flags <player> … 検知履歴", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " refresh <player> … レポート再要求", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " scan <player> … 新しい nonce で再申告させる", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " shot <player> [reason] … 画面を取得（対象には表示されない）",
                NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " watch <player> <seconds|off> … 高頻度監視", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " evidence <player> … 保存済みの証拠", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " kick <player> [reason]", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " policy … 導入必須・ピン留め・探索クラス", NamedTextColor.GRAY));
        sender.sendMessage(text(" /" + label + " reload", NamedTextColor.GRAY));
    }

    private Player target(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage(text(" プレイヤー名を指定してください", NamedTextColor.RED));
            return null;
        }
        Player player = Bukkit.getPlayerExact(args[index]);
        if (player == null) {
            sender.sendMessage(text(" オンラインのプレイヤーが見つかりません: " + args[index], NamedTextColor.RED));
        }
        return player;
    }

    private ClientReport report(CommandSender sender, Player target) {
        ClientReport report = plugin.reports().get(target);
        if (report == null) {
            sender.sendMessage(text(" " + target.getName() + " のレポートがありません。/ac refresh "
                    + target.getName() + " で要求してください。", NamedTextColor.YELLOW));
        }
        return report;
    }

    private static int page(String[] args, int index, int pages) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Math.clamp(Integer.parseInt(args[index]), 1, pages);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static String shaderLabel(ClientReport report) {
        JsonObject shaders = report.shaders();
        String loader = text(shaders, "loader");
        String pack = text(shaders, "pack");
        if (loader.isEmpty() || "none".equalsIgnoreCase(loader)) {
            return "none";
        }
        return loader + (pack.isEmpty() ? "" : ":" + pack);
    }

    private static String bytes(JsonObject file) {
        long size = file.has("size") ? file.get("size").getAsLong() : -1;
        if (size < 0) {
            return "";
        }
        if (size > 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1fMB", size / (1024.0 * 1024.0));
        }
        if (size > 1024) {
            return String.format(Locale.ROOT, "%.1fkB", size / 1024.0);
        }
        return size + "B";
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String abbreviate(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static Component header(String title) {
        return Component.text("── " + title + " ──", NamedTextColor.GOLD, TextDecoration.BOLD);
    }

    private static Component kv(String key, String value, NamedTextColor color) {
        return Component.text(" " + key + ": ", NamedTextColor.GRAY)
                .append(text(value, color));
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color);
    }

    private static Component text(String value, NamedTextColor color, ClickEvent click) {
        return Component.text(value, color).clickEvent(click);
    }

    // ------------------------------------------------------------------ 補完

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("mcsa.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(List.of("status", "info", "mods", "packs", "shaders", "flags", "refresh", "scan",
                    "shot", "watch", "evidence", "kick", "policy", "reload"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            if (sub.equals("policy")) {
                return filter(List.of("mode", "require", "pin", "pin-client", "probe"), args[1]);
            }
            if (needsPlayer(sub)) {
                return players(args[1]);
            }
            return Collections.emptyList();
        }
        if (args.length == 3) {
            if (sub.equals("policy")) {
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "mode" -> {
                        return filter(List.of("OFF", "LISTED", "EVERYONE"), args[2]);
                    }
                    case "require" -> {
                        return filter(List.of("add", "remove", "list"), args[2]);
                    }
                    case "probe" -> {
                        return filter(List.of("list", "add", "remove"), args[2]);
                    }
                    default -> {
                        return players(args[2]);
                    }
                }
            }
            if (needsPlayer(sub)) {
                return players(args[2]);
            }
        }
        if (args.length == 3 && sub.equals("watch")) {
            return filter(List.of("off", "60", "300", "600"), args[2]);
        }
        if (args.length == 4 && sub.equals("policy") && args[1].equalsIgnoreCase("require")) {
            return players(args[3]);
        }
        return Collections.emptyList();
    }

    private static boolean needsPlayer(String sub) {
        return switch (sub) {
            case "info", "mods", "packs", "shaders", "flags", "refresh", "scan", "shot", "watch",
                    "evidence", "kick" -> true;
            default -> false;
        };
    }

    private static List<String> players(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return filter(names, prefix);
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
