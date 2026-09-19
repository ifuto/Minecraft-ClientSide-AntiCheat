#!/usr/bin/env sh
#
# MCSA gradlew ブートストラップ
# -----------------------------
# 通常の Gradle Wrapper は gradle/wrapper/gradle-wrapper.jar（バイナリ）を必要とするが、
# このリポジトリには jar を置いていない。そこでこのスクリプトが Gradle 本体を直接
# ダウンロードして起動する（wrapper jar がやっていることと同じ）。
#
# 正式な Wrapper に置き換えたい場合:
#     gradle wrapper --gradle-version 9.5.1
# を実行すると gradlew / gradlew.bat / gradle/wrapper/gradle-wrapper.jar が上書きされる。
#
# 使い方: ./gradlew [-p <subproject>] <task> ...   （引数はそのまま Gradle に渡る）

set -eu

APP_HOME=$(cd "$(dirname "$0")" && pwd)

# 正式な wrapper jar があるならそちらに任せる
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$WRAPPER_JAR" ]; then
    exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

GRADLE_VERSION=9.5.1
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
if [ -f "$PROPS" ]; then
    detected=$(sed -nE 's#^distributionUrl=.*gradle-([0-9][0-9.]*[0-9])-(bin|all)\.zip.*#\1#p' "$PROPS" | head -n1)
    if [ -n "$detected" ]; then
        GRADLE_VERSION=$detected
    fi
fi

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/mcsa-dists"
DIST="$CACHE/gradle-$GRADLE_VERSION"

if [ ! -x "$DIST/bin/gradle" ]; then
    mkdir -p "$CACHE"
    ZIP="$CACHE/gradle-$GRADLE_VERSION.zip"
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    echo "[gradlew] Gradle $GRADLE_VERSION をダウンロードします: $URL"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 3 -o "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$ZIP" "$URL"
    else
        echo "[gradlew] curl か wget が必要です" >&2
        exit 1
    fi
    rm -rf "$DIST"
    unzip -q -o "$ZIP" -d "$CACHE"
    rm -f "$ZIP"
fi

if [ ! -x "$DIST/bin/gradle" ]; then
    echo "[gradlew] Gradle の展開に失敗しました: $DIST" >&2
    exit 1
fi

exec "$DIST/bin/gradle" "$@"
