# ビルド手順

## 前提

- JDK 17 以上
- Gradle 8.x
- CMake 3.15+
- Visual Studio 2022 (Windows DLLビルド時) または g++ (Linux SOビルド時)
- Minecraft 1.20.1 開発環境

## 1. ハッシュ生成

既知チートパッケージ・ファイル名のブラックリストはハッシュ化して保存します。

```bash
python3 tools/generate_hashes.py
```

これにより以下が生成されます：

- `client-fabric/src/main/java/com/anticheat/client/util/CriticalPackageHashes.java`
- `client-fabric/src/main/java/com/anticheat/client/util/CriticalFileHashes.java`
- `client-fabric/src/main/java/com/anticheat/client/util/CriticalModuleHashes.java`
- `native/src/hashes.h`

新しいチートパッケージを追加したい場合は `tools/generate_hashes.py` のリストを編集し再生成してください。

## 2. ネイティブDLLビルド

### Windows

```powershell
cd native
mkdir build
cd build
cmake .. -G "Visual Studio 17 2022" -A x64 -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
# build/Release/anticheat-native.dll が生成される
# これを client-fabric/src/main/resources/natives/win64/anticheat-native.dll にコピー
```

### Linux

```bash
cd native
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
make
# 生成物: libanticheat-native.so
# client-fabric/src/main/resources/natives/linux/libanticheat-native.so にコピー
```

## 3. クライアントModビルド

```bash
cd client-fabric
./gradlew build
# build/libs/anticheat-client-1.0.0.jar
```

生成されたjarを `.minecraft/mods/` に配置。

初回起動で `mods/anticheat-native.dll` が展開され、バックグラウンド監視が開始されます。

> **重要**: `config/anticheat-client.properties` の `hmacSecret` をサーバと一致させてください。

## 4. サーバプラグイン

```bash
cd server-paper
./gradlew build
# build/libs/AnticheatServer-1.0.0.jar
```

`plugins/` フォルダへ配置し、サーバを起動。 `plugins/AnticheatServer/config.yml` の `hmacSecret` をクライアントと一致させる。

## 5. 動作確認

1. クライアントでバニラ状態で参加 → コンソールに `Handshake from <player>` が表示される
2. クライアントmodsフォルダに `meteor-client-...jar` を配置 → 数秒以内に `File violation` ログがクライアント側に出力され、 `anticheat:violation` パケットが送信される
3. サーバ側で `VIOLATION from ...` ログと共に `ban` コマンドが実行される

## トラブルシューティング

- **Nativeロード失敗**: 
  - `mods/anticheat-native.dll` が存在するか確認
  - Visual C++再配布パッケージが必要な場合あり
  - Linuxでは `libanticheat-native.so` に実行権限を付与

- **パケットが届かない**:
  - clientとserverの `anticheat:violation` チャネルが登録されているか確認
  - Paper 1.20.5以降ではプラグインメッセージングAPIが変更されている可能性あり → `server-paper/src/main/java` のリスナを新しいCustomPayload APIに更新

- **誤検知**:
  - `docs/FALSE_POSITIVE_ANALYSIS.md` 参照
  - `CriticalPackageHashes` から該当ハッシュを除去し再ビルド
