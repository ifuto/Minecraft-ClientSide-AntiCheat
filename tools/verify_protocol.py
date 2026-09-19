#!/usr/bin/env python3
"""MCSA のクライアント／サーバー間契約の整合性チェック。

Java をコンパイルしなくても、両側でずれたら壊れる「約束事」を機械的に確認するためのもの。
CI の実ビルド（.github/workflows/build-check.yml）と合わせて使う。

    python3 tools/verify_protocol.py

チェック内容:
  1. プラグインメッセージのチャンネル名（クライアント/OP用MOD の Identifier とサーバーの Wire 定数）
  2. レポート JSON のキー（サーバーが読むキーはクライアントが必ず書く）
  3. config.yml のキー（サーバーのコードが読むキーが全て定義されている）
  4. HMAC の鍵指紋ラベル（両側で一致）
  5. プロトコル版（クライアント定数 = config.yml = ドキュメント）
  6. レポート断片のサイズ（Bukkit のプラグインメッセージ上限以下）
  7. docs にチャンネル名・コマンドが載っているか
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENT = ROOT / "client" / "src" / "main" / "java"
ADMIN = ROOT / "admin" / "src" / "main" / "java"
SERVER = ROOT / "server" / "src" / "main"
RESOURCES = SERVER / "resources"

failures: list[str] = []
checks = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global checks
    checks += 1
    status = "OK  " if ok else "FAIL"
    line = f"[{status}] {name}"
    if detail:
        line += f" — {detail}"
    print(line)
    if not ok:
        failures.append(name)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def java_files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*.java"))


def all_java_text(root: Path) -> str:
    return "\n".join(read(p) for p in java_files(root))


# --------------------------------------------------------------------------
# 最小限の YAML 読み取り（依存を増やしたくないため、必要な範囲だけ）
# --------------------------------------------------------------------------
def yaml_paths(text: str) -> set[str]:
    paths: set[str] = set()
    stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        line = raw.strip()
        while stack and indent <= stack[-1][0]:
            stack.pop()
        prefix = ".".join(key for _, key in stack)
        if line.startswith("- "):
            continue
        match = re.match(r"^([A-Za-z0-9_.-]+):(.*)$", line)
        if not match:
            continue
        key, rest = match.group(1), match.group(2).strip()
        path = f"{prefix}.{key}" if prefix else key
        if rest and not rest.startswith("#"):
            paths.add(path)
        else:
            stack.append((indent, key))
            paths.add(path)
    return paths


# --------------------------------------------------------------------------
# 1. チャンネル名
# --------------------------------------------------------------------------
def channels() -> tuple[set[str], set[str]]:
    # OP 用 MOD（admin/）もクライアント側として数える
    client_text = all_java_text(CLIENT) + "\n" + all_java_text(ADMIN)
    server_text = all_java_text(SERVER / "java")
    client_channels = {
        f"mcsa:{name}"
        for name in re.findall(r'Identifier\.of\(\s*"mcsa"\s*,\s*"([a-z_]+)"\s*\)', client_text)
    }
    server_channels = set(re.findall(r'=\s*"(mcsa:[a-z_]+)"', server_text))
    return client_channels, server_channels


# --------------------------------------------------------------------------
# 2. レポート JSON のキー
# --------------------------------------------------------------------------
CLIENT_KEY_PATTERNS = [
    r'\.addProperty\(\s*"([A-Za-z0-9_]+)"',
    r'\.add\(\s*"([A-Za-z0-9_]+)"',
]
SERVER_KEY_PATTERNS = [
    r'\barray\(\s*"([A-Za-z0-9_]+)"\s*\)',
    r'\bobject\(\s*"([A-Za-z0-9_]+)"\s*\)',
    r'\bstring\(\s*"([A-Za-z0-9_]+)"',
    r'\btext\(\s*[A-Za-z_]+\s*,\s*"([A-Za-z0-9_]+)"\s*\)',
    r'\bbool\(\s*[A-Za-z_]+\s*,\s*"([A-Za-z0-9_]+)"\s*\)',
    r'json\.get\(\s*"([A-Za-z0-9_]+)"\s*\)',
    r'json\.has\(\s*"([A-Za-z0-9_]+)"\s*\)',
    r'self\.has\(\s*"([A-Za-z0-9_]+)"\s*\)',
    r'\.get\(\s*"([A-Za-z0-9_]+)"\s*\)',
    r'\bprobe(?:Int|Array|Object|Boolean)\(\s*[A-Za-z_]+\s*,\s*"([A-Za-z0-9_]+)"\s*\)',
]


def report_keys() -> tuple[set[str], set[str]]:
    client_keys: set[str] = set()
    for pattern in CLIENT_KEY_PATTERNS:
        client_keys |= set(re.findall(pattern, all_java_text(CLIENT)))
    server_keys: set[str] = set()
    for pattern in SERVER_KEY_PATTERNS:
        server_keys |= set(re.findall(pattern, read(SERVER / "java" / "dev/ifuto/mcsa/server/report/ClientReport.java")))
        server_keys |= set(re.findall(pattern, read(SERVER / "java" / "dev/ifuto/mcsa/server/policy/ModPolicy.java")))
        server_keys |= set(re.findall(pattern, read(SERVER / "java" / "dev/ifuto/mcsa/server/command/AcCommand.java")))
        server_keys |= set(re.findall(pattern, read(SERVER / "java" / "dev/ifuto/mcsa/server/policy/InjectionPolicy.java")))
    return client_keys, server_keys


# --------------------------------------------------------------------------
# 3. config.yml
# --------------------------------------------------------------------------
CONFIG_GETTERS = re.compile(
    r'config\.(?:get|reload)?'
    r'(?:getString|getBoolean|getInt|getDouble|getStringList|getConfigurationSection)\(\s*"([a-z0-9.\-]+)"',
)


def main() -> int:
    client_text = all_java_text(CLIENT) + "\n" + all_java_text(ADMIN)
    server_text = all_java_text(SERVER / "java")
    config_yml = read(RESOURCES / "config.yml")
    plugin_yml = read(RESOURCES / "plugin.yml")

    # 1. チャンネル名
    client_channels, server_channels = channels()
    check("クライアント/サーバーのチャンネル名が一致",
          client_channels == server_channels,
          f"client={sorted(client_channels)} server={sorted(server_channels)}")
    check("チャンネルが 10 本ある（申告3 + 証拠/監視3 + OP連携2 + チャレンジ/指示2）",
          client_channels == {"mcsa:challenge", "mcsa:task", "mcsa:hello", "mcsa:report", "mcsa:seal",
                              "mcsa:evidence", "mcsa:digest", "mcsa:admin", "mcsa:adminmsg", "mcsa:shot"},
          str(sorted(client_channels)))

    # 2. レポート JSON のキー
    client_keys, server_keys = report_keys()
    missing = sorted(key for key in server_keys if key not in client_keys)
    check("サーバーが読むレポートキーはクライアントが書いている", not missing,
          f"不足: {missing}" if missing else f"{len(server_keys)} キーを確認")

    # 3. config.yml
    yaml_keys = yaml_paths(config_yml)
    used = set(CONFIG_GETTERS.findall(server_text))
    # AcCommand 経由で触るキーも拾う
    used |= set(re.findall(r'getConfig\(\)\.(?:getStringList|set)\(\s*"([a-z0-9.\-]+)"', server_text))
    undefined = sorted(key for key in used if key not in yaml_keys)
    check("コードが読む config キーが config.yml に定義されている", not undefined,
          f"未定義: {undefined}" if undefined else f"{len(used)} キーを確認")

    # 4. HMAC のラベル
    client_label = re.search(r'KEY_ID_LABEL\s*=\s*"([^"]+)"', client_text)
    server_label = re.search(r'KEY_ID_LABEL\s*=\s*"([^"]+)"', server_text)
    check("HMAC 鍵指紋ラベルが一致",
          bool(client_label and server_label and client_label.group(1) == server_label.group(1)),
          f"client={client_label.group(1) if client_label else None} "
          f"server={server_label.group(1) if server_label else None}")

    # 5. プロトコル版
    proto_match = re.search(r"PROTOCOL\s*=\s*(\d+)", client_text)
    yaml_proto = re.search(r"^protocol:\s*(\d+)", config_yml, re.MULTILINE)
    docs = read(ROOT / "docs" / "PROTOCOL.md") if (ROOT / "docs" / "PROTOCOL.md").exists() else ""
    same = bool(proto_match and yaml_proto and proto_match.group(1) == yaml_proto.group(1))
    check("プロトコル版がクライアントとサーバーで一致", same,
          f"client={proto_match.group(1) if proto_match else None} "
          f"config={yaml_proto.group(1) if yaml_proto else None}")
    check("PROTOCOL.md にプロトコル版が書かれている",
          bool(proto_match) and f"protocol = {proto_match.group(1)}" in docs if proto_match else False)

    # 6. 断片サイズ
    chunks = [int(value) for value in re.findall(r"CHUNK_SIZE\s*=\s*(\d+)", client_text)]
    check("レポート/証拠の断片が Bukkit のプラグインメッセージ上限 (32767) 以下",
          bool(chunks) and max(chunks) <= 32767,
          f"CHUNK_SIZE={sorted(set(chunks))}")

    # 7. ドキュメント
    if docs:
        missing_docs = sorted(ch for ch in client_channels if f"`{ch}`" not in docs)
        check("PROTOCOL.md に全チャンネルが載っている", not missing_docs,
              f"不足: {missing_docs}" if missing_docs else "")
    commands_md = ROOT / "docs" / "COMMANDS.md"
    if commands_md.exists():
        commands_text = read(commands_md)
        declared = set(re.findall(r"^  ([a-z][a-z0-9_-]*):", plugin_yml, re.MULTILINE))
        missing_cmds = sorted(cmd for cmd in declared if f"/{cmd}" not in commands_text)
        notice = ROOT / "client" / "src" / "main" / "resources" / "privacy-notice.txt"
    notice_ok = notice.exists()
    notice_text = notice.read_text(encoding="utf-8") if notice_ok else ""
    check("プライバシィ告知の文面が同梱されている（起動時の同意に使う）",
          notice_ok and "Data Privacy" in notice_text
          and "Third-Party Disclosure" in notice_text
          and len(notice_text) > 400,
          "client/src/main/resources/privacy-notice.txt")

    consent = CLIENT / "java" / "dev/ifuto/mcsa/client/consent/ConsentScreen.java"
    if not consent.exists():
        consent = ROOT / "client/src/main/java/dev/ifuto/mcsa/client/consent/ConsentScreen.java"
    consent_text = consent.read_text(encoding="utf-8") if consent.exists() else ""
    check("拒否すると Minecraft を終了させる（同意しないとプレイできない）",
          "scheduleStop" in consent_text and "shouldCloseOnEsc" in consent_text
          and "I Agree" in consent_text and "Decline and Quit" in consent_text,
          "ConsentScreen.java")

    # admin 側は Identifier.of("mcsa", "shot")、サーバー側は "mcsa:shot" を書く
    check("証拠を OP のクライアントへ転送するチャンネルがある",
          'Identifier.of("mcsa", "shot")' in all_java_text(ADMIN)
          and '"mcsa:shot"' in server_text
          and "ShotPayload" in client_text,
          "admin の ShotPayload / server の CH_SHOT のいずれかが欠けています")

    check("COMMANDS.md に plugin.yml のコマンドが載っている", not missing_cmds,
              f"不足: {missing_cmds}" if missing_cmds else str(sorted(declared)))

    print()
    print(f"{checks - len(failures)}/{checks} 合格")
    if failures:
        print("失敗: " + ", ".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
