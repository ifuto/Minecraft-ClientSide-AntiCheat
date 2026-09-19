#!/usr/bin/env python3
"""Java ソースの粗い整合チェック（コンパイラの代わりにはならない）。

このサンドボックスには JDK も Maven リポジトリへのアクセスも無いため、
実際のコンパイルは GitHub Actions（Multi Build / Multi Build (Paper)）で行う。
その前に、機械的に潰せるtypoを潰しておくためのもの。

    python3 tools/check_java_syntax.py

チェック内容:
  1. 括弧（{} () []）の対応。文字列・文字・コメント・テキストブロックは無視する
  2. dev.ifuto.mcsa.* への import が実在するファイルを指しているか
  3. plugin.xxx() で呼んでいるメソッドが McsaPlugin に定義されているか
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [ROOT / "client" / "src" / "main" / "java", ROOT / "server" / "src" / "main" / "java"]

failures: list[str] = []
checks = 0


def check(name: str, ok: bool, detail: str = "") -> None:
    global checks
    checks += 1
    print(f"[{'OK  ' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def strip_code(text: str) -> str:
    """文字列・文字リテラル・コメントを空白に置き換える（テキストブロック対応）。"""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end < 0 else end + 3
            out.append(' ')
            continue
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            out.append(' ')
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
            out.append(' ')
            continue
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def java_files() -> list[Path]:
    files: list[Path] = []
    for source in SOURCES:
        files.extend(sorted(source.rglob("*.java")))
    return files


def main() -> int:
    files = java_files()
    check("Java ソースが見つかる", len(files) > 0, f"{len(files)} ファイル")

    # 1. 括弧の対応
    unbalanced: list[str] = []
    for path in files:
        code = strip_code(path.read_text(encoding="utf-8"))
        stack: list[str] = []
        pairs = {"}": "{", ")": "(", "]": "["}
        for ch in code:
            if ch in "{([":
                stack.append(ch)
            elif ch in "})]":
                if not stack or stack.pop() != pairs[ch]:
                    unbalanced.append(f"{path.relative_to(ROOT)} (余分な {ch})")
                    break
        else:
            if stack:
                unbalanced.append(f"{path.relative_to(ROOT)} (閉じられていない {''.join(stack)})")
    check("括弧の対応", not unbalanced, "; ".join(unbalanced) if unbalanced else f"{len(files)} ファイル")

    # 2. プロジェクト内 import の実在
    available = {
        ".".join(path.relative_to(source).with_suffix("").parts)
        for source in SOURCES
        for path in source.rglob("*.java")
    }
    # ビルド時に生成されるクラス
    available.add("dev.ifuto.mcsa.client.gen.KeyMaterial")
    missing: list[str] = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        for target in re.findall(r"^import\s+(dev\.ifuto\.mcsa\.[A-Za-z0-9_.]+);", text, re.MULTILINE):
            if target not in available:
                missing.append(f"{path.name}: {target}")
    check("プロジェクト内 import が実在する", not missing, "; ".join(missing) if missing else "")

    # 3. plugin.xxx() の呼び出し先
    plugin_src = (ROOT / "server/src/main/java/dev/ifuto/mcsa/server/McsaPlugin.java").read_text(encoding="utf-8")
    defined = set(re.findall(r"public\s+[\w<>,.\[\] ]+\s+(\w+)\s*\(", plugin_src))
    used: set[str] = set()
    for path in (ROOT / "server/src/main/java").rglob("*.java"):
        used |= set(re.findall(r"\bplugin\.(\w+)\(", path.read_text(encoding="utf-8")))
    unknown = sorted(used - defined - {"getServer", "getLogger", "getConfig", "saveConfig", "reloadConfig",
                                       "getDataFolder", "saveDefaultConfig", "saveResource", "getCommand",
                                       "getDescription", "isEnabled", "getPluginLoader"})
    check("plugin.xxx() が McsaPlugin か JavaPlugin のメソッド", not unknown,
          f"未定義: {unknown}" if unknown else f"{len(used)} 種類の呼び出しを確認")

    print()
    print(f"{checks - len(failures)}/{checks} 合格")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
