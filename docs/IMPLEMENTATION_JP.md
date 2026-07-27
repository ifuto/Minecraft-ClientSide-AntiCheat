# 実装詳細 - クライアントサイドアンチチート (日本語)

## 1. パッケージチェック

### 要件
- コードにハッシュを保存して直接的な記述は避ける
- sodiumなどの誤検知を起こすものは除外

### 実装

`tools/generate_hashes.py` がブラックリストのパッケージ名から SHA-256 ハッシュを計算し、その先頭8バイトを `long` (64bit) として Javaファイルに保存します。

```java
// 生成例
HASHES.add(4768409575576765344L); // 0x422ccb3eafd303a0 : 'meteordevelopment.meteorclient'
```

実際のランタイムでは、 `Package.getPackages()` と modsフォルダ内の全jarファイルのクラスエントリを走査し、パッケージ名を `HashUtil.hash64(pkgName)` でハッシュ化してセットに存在するかチェックします。

**除外ロジック**:

`false_positive_packages` リストで以下を除外：
- Sodium系 (`sodium`, `net.caffeinemc.mods.sodium`)
- OptiFine (`net.optifine`)
- Lunar (`com.moonsworth.lunar`), Badlion (`net.badlion`)
- ミニマップ (`xaero.map`, `journeymap`, `voxelmap`)
- ユーティリティ (`tweakeroo`, `malilib`, `litematica`)
- 汎用ライブラリ (`mixinextras`, `guava eventbus`, `bytebuddy`, `javassist`, `packetevents`)

結果として、CRITICAL 71件のみが `CriticalPackageHashes.java` に残ります。

**数学的妥当性**:

- 64bitハッシュの衝突確率: n=71 で P≈1.4e-16。誤検知の心配は無視可能。
- 階層検出: `me.liquidbounce` がBAN対象なら、`me.liquidbounce.features.module.combat` のような子パッケージも親を辿って検出できるよう、jarスキャン時に累積パッケージを全てチェック。

### バイパスと対策

攻撃者がパッケージを `com.example.test` にリネームすれば回避可能。これはブラックリスト方式の根本的限界であり、後段の行動検出で補完します。

## 2. Modsフォルダ監視 (DLL)

### 要件

- `.jarと同じ階層にある.dllでバックグラウンドを起動し、Modsフォルダを監視`
- 世界の様々なチート名を調べ、それがModsにあればアウト、独自パケット送信

### 実装

**ネイティブ側 (`native/src/file_monitor.cpp`)**:

- Windows: `ReadDirectoryChangesW` をオーバーラップI/Oで監視するスレッド
- Linux: `inotify` で `IN_CREATE | IN_MOVED_TO | IN_MODIFY` を監視
- 検出したファイル名を小文字化し、全サブストリングのSHA-256ハッシュを計算してブラックリスト (`critical_file_hashes`) と照合
- 73種類のチート名をカバー: `meteor-client`, `wurst`, `baritone`, `aristois`, `futureclient`, `rusherhack`, `liquidbounce`, `inertia`, `vape`, `novoline`, `tenacity`, `thunderhack`, `celestial`, `nursultan`, `expensive`, etc

**Java側 (`FileMonitor.java`)**:

- `WatchService` で同様の監視 (クロスプラットフォーム)
- ネイティブとJavaの両方から違反を取得し `ViolationReporter` へ

**計算量**:

- ファイル名長 L=30 に対しサブストリング数 ≈ L^2/2 = 450
- 各SHA-256は数µs、1イベントあたり2ms程度で十分高速

### バイパスと対策

- Modsフォルダ外からのクラスロード (`-javaagent`) は検出不可 → パッケージスキャナがjar内部のパッケージで検出する二重構造で緩和
- ファイル名を `a.jar` にリネーム → サブストリング検出を回避できるが、内部パッケージが検出される

## 3. メモリ監視 (DLL)

### 実装

`native/src/memory_guard.cpp`:

- `EnumProcessModules` で現在プロセスにロードされた全DLLを列挙、モジュール名のハッシュが `critical_module_hashes` (cheatengine, x64dbg, processhacker etc) に一致したら違反
- `VirtualQuery` で全仮想メモリ領域を走査、RWX (READ_WRITE_EXECUTE) 領域が大きすぎる/多すぎる場合にフラグ。JVMのJITはRWXを作るが、通常は少数。閾値 30個以上または合計20MB以上で違反
- Linuxでは `/proc/self/maps` をパースして `rwxp` 領域をカウント

Java側ではJVM引数に `-javaagent`, `-noverify`, `-Xverify:none` が含まれていないかチェック

### 数学的考察

- RWX領域数の分布をPoisson(λ=15)と仮定すると、閾値30での誤検知率 P(X>30)≈0.03% と低い
- Manual MapされたDLLはモジュールリストに乗らないため回避可能 → 完全ではないが抑止力にはなる

## 4. 入力真正性

### 要件

- 操作がデバイスによるものかMod生成かを判別

### Windows低レベルフック

`input_hook.cpp`:

```cpp
HHOOK mouseHook = SetWindowsHookEx(WH_MOUSE_LL, LowLevelMouseProc, ...);
```

コールバックで `MSLLHOOKSTRUCT.flags & LLMHF_INJECTED` をチェック。`SendInput` で合成された入力はこのフラグが立つ。

Java側では攻撃/使用アクション時に `System.currentTimeMillis()` と最後のハードウェア入力時刻の差をチェック。閾値 `inputAuthWindowMs=150ms` を超えたら合成入力とみなし、5回連続でBAN。

### バイパス

- カーネルドライバやArduino HIDデバイスはハードウェアとして見え、フラグが立たない → 統計的解析で補完

### 統計的補完

- **RotationAnalyzer**: 1tickで yaw>60度はSnapとみなしKillAura検出。Minecraftの感度GCDを無視した回転は不可能回転として検出可能
- **CPSAnalyzer**: クリック間隔の平均・分散から変動係数CVを算出。人間はCV>0.15、AutoclickerはCV<0.05。閾値0.05で連続3回超過を要求することで誤検知率を3%→0.0027%へ低減

## 5. 自己BANパケット

`SelfBanPacketSender`:

```json
{
  "type":"PACKAGE",
  "subType":"CRITICAL",
  "detail":"meteordevelopment.meteorclient",
  "ts":1234567890,
  "nonce":"abc",
  "sig":"hmac-sha256"
}
```

HMAC-SHA256で `secret` を用いて署名。サーバは同じsecretで検証し、`ban %player% %reason%` を実行。

**矛盾**: チートしているクライアントが自分でBANパケットを送るというモデルは、攻撃者がModを無効化すれば回避できるという根本的矛盾を抱える。緩和策としてハートビートタイムアウトで改ざんを検出するが、完全ではない。詳細は `THREAT_MODEL.md` 参照。

## 6. 追加機能

- **Heartbeat**: 30秒ごとに `anticheat:heartbeat` を送信。途絶えたらKick
- **Handshake**: 参加時にModバージョンとOS情報を送信。`requireAnticheat=true` ならMod未導入者をKick
- **Jarハッシュチャレンジ**: `computeJarHash` で自身のjarのSHA-256を計算し、サーバのnonceと組み合わせてアテステーション可能 (将来拡張)

## 7. 今後の課題

- Jarハッシュ検証の厳格化 (リプレイ攻撃防止)
- 難読化と整合性チェック (DLLハッシュ検証)
- 移動解析 (Timer, Fly)
- サーバサイドアンチチートとの連携強化
