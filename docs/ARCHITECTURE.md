# アーキテクチャ概要

## 全体構成

```
Minecraft Client (Fabric)
  ├─ anticheat-client.jar (Fabric Mod)
  │   ├─ PackageScanner (Java)
  │   ├─ FileMonitor (Java WatchService + Native DLL)
  │   ├─ MemoryGuard (Java + Native)
  │   ├─ InputAuthenticity (Java + Native Hook)
  │   ├─ RotationAnalyzer
  │   ├─ CPSAnalyzer
  │   ├─ NativeBridge (JNI)
  │   └─ SelfBanPacketSender (Custom Payload)
  │
  └─ anticheat-native.dll / libanticheat-native.so (same dir as jar)
      ├─ FileMonitor (ReadDirectoryChangesW / inotify)
      ├─ MemoryGuard (EnumProcessModules, VirtualQuery)
      └─ InputHook (WH_MOUSE_LL, WH_KEYBOARD_LL)

Server (Paper/Spigot)
  └─ AnticheatServer.jar
      ├─ PluginMessageListener (anticheat:violation, heartbeat, handshake)
      ├─ HMAC validation
      ├─ BanHandler (minecraft:ban)
      └─ HeartbeatChecker
```

## データフロー

1. **起動時**：
   - `AntiCheatClientMod.onInitializeClient()` がNative DLLをmodsフォルダからロード (jarと同じ階層)
   - `NativeBridge.initialize()` がDLLを展開してロード、ハッシュ検証 (TODO)
   - 各検出モジュールの初期スキャン

2. **実行時ループ**：
   - Javaスレッド + Nativeスレッドが並行でModsフォルダを監視
   - `ClientTickEvents.END_CLIENT_TICK` で回転をチェック
   - Mixin (`MinecraftClientMixin`, `MouseMixin`, `KeyboardMixin`) でクリックを検出し、 `InputAuthenticity` と `CPSAnalyzer` に通知
   - 定期タスク (ScheduledExecutor) でパッケージスキャン (30s), メモリガード (20s), ファイル監視ポーリング (5s), ハートビート (30s)

3. **違反検出時**：
   - `ViolationReporter.reportXXX()` が呼ばれる
   - JSON + HMAC生成
   - `SelfBanPacketSender.sendViolationPacket()` が `ClientPlayNetworking.send(anticheat:violation, buf)` で送信
   - 同時にローカル切断を試みる

4. **サーバ受信時**：
   - `AntiCheatServerPlugin.onPluginMessageReceived()` がHMAC検証
   - `handleViolation()` が `ban %player% %reason%` コマンドを実行

## 通信プロトコル

- **Fabric Custom Payload**: Identifier `anticheat:violation`, `anticheat:heartbeat`, `anticheat:handshake`
- **Bukkit Plugin Messaging**: 同じチャネル名で `registerIncomingPluginChannel`
- **ペイロード**: Fabricの `PacketByteBuf.writeString(json)` は先頭にVarInt長さが付く。Bukkit側ではVarIntデコードを試みる

## ビルド方法

### Client Fabric Mod

```bash
cd client-fabric
./gradlew build
# 生成物: build/libs/anticheat-client-1.0.0.jar
```

Fabric Loader 0.15.7, Yarn 1.20.1+build.10, Minecraft 1.20.1

### Native DLL

```bash
cd native
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
# 生成物: anticheat-native.dll (Windows) / libanticheat-native.so (Linux)
# 手動で client-fabric/src/main/resources/natives/win64/ にコピー
```

WindowsではVisual Studio 2022 + JNI (JDK 17) が必要。CMakeがJNI_INCLUDE_DIRSを自動検出。

### Server Paper Plugin

```bash
cd server-paper
./gradlew build
# 生成物: build/libs/AnticheatServer-1.0.0.jar
```

## 設定

### Client: config/anticheat-client.properties

- `hmacSecret`: サーバと共有する秘密鍵 (HMAC用)
- `requireNative`: trueならNativeロード失敗時に改ざんとして扱う
- `inputAuthWindowMs`: ハードウェア入力とゲーム行動の許容時間差 (ms)
- `maxYawPerTick`, `maxPitchPerTick`: 回転スナップ閾値
- `cpsWindowSize`, `cpsVarianceThreshold`: CPS解析パラメータ

### Server: plugins/AnticheatServer/config.yml

- `hmacSecret`: クライアントと一致させる必要あり
- `heartbeatTimeoutMs`: ハートビートタイムアウト
- `requireAnticheat`: trueならMod未導入プレイヤーをKick
- `banCommand`: 実行するBANコマンド
- `kickOnHeartbeatTimeout`: タイムアウト時にKickするか

## ハッシュ生成

`tools/generate_hashes.py` がブラックリストのハッシュを生成：

- 入力: パッケージ名, ファイル名サブストリング, モジュール名
- 出力: `client-fabric/.../CriticalPackageHashes.java` 等と `native/src/hashes.h`

パッケージ名は **直接文字列を保存せずハッシュのみ** を保存する要件を満たす。

除外リスト: Sodium, OptiFine, Lunar, Badlion, Xaero, Tweakeroo, Malilib等は誤検知を避けるためブラックリストから除外。

## セキュリティ考慮

- DLLハッシュ検証: TODO - 起動時に期待ハッシュと比較
- HMAC: 共有秘密鍵方式だが、クライアントから抽出可能 → 完全ではない
- 難読化: ProGuardやCustom ObfuscatorでMod jarを難読化すると解析困難化できるが、オープンソースなら無意味 (本リポジトリはオープン)
- 最終的にはサーバサイドアンチチートと併用すべし (THREAT_MODEL.md 参照)
