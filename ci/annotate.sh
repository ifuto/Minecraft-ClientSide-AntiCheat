#!/usr/bin/env bash
# Gradle のビルドログを GitHub Actions のチェックアノテーションに変換する。
#
# 使い方: ci/annotate.sh <ログファイル>
#
# アノテーションは 1 ステップ 10 件までしか残らない（超えると古いものから消える）ので、
# 「一番効く行」を最後に出す。GitHub は後ろのものを残すため。
#   1) ログ末尾（保険）
#   2) ProGuard / Gradle / JVM のエラー本文
#   3) javac のエラー（ファイルと行が分かるので最優先で残したい）
set -uo pipefail

LOG="${1:-}"
if [[ ! -f "$LOG" ]]; then
  echo "::error::ログが見つかりません: $LOG"
  ls -lR */build/libs 2>/dev/null || true
  exit 0
fi

esc() { local s="$1"; s="${s//%/%25}"; s="${s//$'\r'/%0D}"; s="${s//$'\n'/%0A}"; printf '%s' "$s"; }

# 1) 保険: ログ末尾（短め）
tail -n 12 "$LOG" | while IFS= read -r line; do
  [[ -n "$line" ]] && echo "::error::$(esc "$line")"
done

# 2) ProGuard / Gradle / JVM のエラー本文
grep -aiE '(^|[^A-Za-z])(Error|FAILURE|FAILED|Caused by|Exception|Unexpected error|Can.t |Unable to|cannot (find|access)|does not (exist|override)|No such|OutOfMemory|Could not (find|resolve|download)|Execution failed)' "$LOG" \
  | grep -av '^\s*at ' | grep -av 'Note:' | tail -n 8 \
  | while IFS= read -r line; do echo "::error::$(esc "$line")"; done

# 3) javac: "path/to/File.java:123: error: message"
grep -aE '^[^ ]*\.(java|kt):[0-9]+: (error|warning): ' "$LOG" | tail -n 8 \
  | while IFS= read -r line; do
      path="${line%%:*}"
      rest="${line#*:}"
      lineno="${rest%%:*}"
      message="${line##*: }"
      echo "::error file=${path},line=${lineno}::$(esc "$message")"
    done

exit 0
