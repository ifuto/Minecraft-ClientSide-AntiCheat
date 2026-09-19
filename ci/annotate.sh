#!/usr/bin/env bash
# ビルドログのエラー行を GitHub Checks のアノテーションに変換する。
#
#   bash ci/annotate.sh <build-log>
#
# Actions の生ログは blob 経由でしか取れない環境があるため、
# 失敗理由をアノテーション（Checks API で普通に読める）に乗せておく。
# javac の "file.java:12: error: ..." 形式は file / line 付きで出す。
set -uo pipefail

LOG="${1:-}"
if [ -z "$LOG" ] || [ ! -f "$LOG" ]; then
  echo "::error::ビルドログが見つかりません: ${LOG:-<未指定>}"
  exit 0
fi

esc() {
  local s="$1"
  s="${s//%/%25}"
  s="${s//$'\r'/%0D}"
  s="${s//$'\n'/%0A}"
  printf '%s' "$s"
}

emit() {
  local line="$1"
  if [[ "$line" =~ (.*\.(java|kt)):([0-9]+):[0-9]*:?[[:space:]]?e(rror)?:[[:space:]](.*) ]]; then
    local file="${BASH_REMATCH[1]#"$GITHUB_WORKSPACE/"}"
    echo "::error file=$file,line=${BASH_REMATCH[3]}::$(esc "${BASH_REMATCH[5]}")"
  else
    echo "::error::$(esc "$line")"
  fi
}

PAT="error:|FAILED|Caused by|Exception|Could not resolve|Unsupported|cannot find symbol|Error occurred while enabling"

if grep -aqE "$PAT" "$LOG"; then
  # javac のエラー本体（ファイル名付き）を優先し、その後に概要を足す
  grep -aE '\.(java|kt):[0-9]+:' "$LOG" | head -40 | while IFS= read -r line; do emit "$line"; done
  grep -aE "$PAT" "$LOG" | grep -avE '\.(java|kt):[0-9]+:' | head -20 | while IFS= read -r line; do emit "$line"; done
else
  tail -40 "$LOG" | while IFS= read -r line; do emit "$line"; done
fi
