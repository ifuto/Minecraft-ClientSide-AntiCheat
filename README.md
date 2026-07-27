# Minecraft Client-Side Anticheat

日本語 | [English](#english)

クライアントサイドで動作する多層アンチチート Mod + ネイティブDLL + サーバプラグインの実装です。

## 概要

本プロジェクトは以下の4層でチートを検出します：

1. **パッケージハッシュ検査**  
   既知チート (Meteor, Wurst, Baritone, Aristois, Future, RusherHack, LiquidBounce, Rise, Expensive, NuxHack など) のJavaパッケージを **ハッシュのみ保存** し、直接的な文字列をコードに残さず検出。  
   誤検知を避けるため Sodium, OptiFine, Lunar/Badlion, XaeroMap, Tweakeroo, MaLiLib, Zoomify などは除外済み。

2. **Modsフォルダ監視 (DLL)**  
   `.jarと同じ階層にある.dll` がバックグラウンドスレッドでModsフォルダを監視 (Windows: `ReadDirectoryChangesW`, Linux: `inotify`)。  
   世界中のチート名 (meteor-client, wurst, baritone, aristois, futureclient, rusherhack, liquidbounce, vape, novoline, tenacity, thunderhack, celestial, nursultan など70+種) をサブストリングハッシュで検出し、独自パケットをサーバへ送信。

3. **メモリ監視 (DLL + Java)**  
   Native側で `EnumProcessModules` / `/proc/self/maps` で怪しいモジュール (`cheatengine`, `x64dbg`, `processhacker` 等) を検出。  
   `VirtualQuery` でRWX領域の異常を検出。  
   Java側で `-javaagent`, `-noverify` などの怪しいJVM引数を検出。

4. **入力真正性検証**  
   その操作が **デバイスによって行われたものか、Modにより生成されたものか** を判別：
   - Windows低レベルフック (`WH_MOUSE_LL`, `WH_KEYBOARD_LL`) でハードウェア入力時刻と `LLMHF_INJECTED` フラグを取得
   - ゲーム内の攻撃/使用行動とハードウェアイベントの時間差が閾値 (150ms) を超えたら合成入力としてフラグ
   - 回転解析: 1tickで60度以上のスナップ、GCDチェック無視のAimBot検出
   - CPS解析: クリック間隔の変動係数 CV < 0.05 かつ高CPSでAutoclicker検出 (統計的手法)

検出時、**自ら自分をBANする独自パケット** (`anticheat:violation`) を理由と一緒にサーバへ送信。サーバプラグインが受信して `minecraft:ban` を実行。

## 構成

```
client-fabric/      FabricクライアントMod (1.20.1)
native/             C++ DLL / .so ソース (JNA/JNI, ファイル監視, メモリ監視, 入力フック)
server-paper/       Paper/Spigotプラグイン (自己BANパケット受信 → BAN)
tools/              ハッシュ生成スクリプト
docs/               脅威モデル・設計文書
```

## クイックスタート

### クライアントビルド

```bash
cd client-fabric
./gradlew build
# build/libs/anticheat-client-1.0.0.jar が生成される
# modsフォルダへ配置
```

初回起動時に `mods/anticheat-native.dll` (Windows) がjar内のリソースから展開されロードされる。

### ネイティブDLLビルド (任意)

```bash
cd native
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
# 生成されたDLLを client-fabric/src/main/resources/natives/win64/ に配置すると、jarに同梱される
```

### サーバプラグイン

```bash
cd server-paper
./gradlew build
# build/libs/AnticheatServer-1.0.0.jar を plugins/ に配置
```

`plugins/AnticheatServer/config.yml` と `config/anticheat-client.properties` の `hmacSecret` を同じ値に設定すること (共有秘密鍵)。

## 通信プロトコル

- `anticheat:violation` : 違反報告 (JSON + HMAC-SHA256)
- `anticheat:heartbeat` : 30秒ごとの生存報告
- `anticheat:handshake` : 参加時のMod存在通知

HMAC: `HMAC-SHA256(secret, uuid|type|subType|detail|ts|nonce)` で偽装BANを防止。

## 脅威モデルと矛盾の考察

本アンチチートは **クライアントが協力的である前提** では高効果だが、**攻撃者がModを改変して無効化できる**という根本的矛盾を抱える。これはクライアントサイドアンチチートの数学的限界であり、ハードウェア支援 (SGX, TPM) なしでは完全な安全性は達成不可能。

詳細は [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) にて、ハッシュ衝突確率、統計的検出の妥当性、誤検知回避、バイパス手法を深く考察しています。

**推奨運用**: 必ずサーバサイドアンチチート (GrimAC, Vulcan, NoCheatPlus 等) と併用し、本Modは抑止力・補助ログとして使用する。

## 誤検知対策

以下の正当Modはブラックリストから **除外** 済み：

- Sodium, Lithium, Iris, FerriteCore
- OptiFine (`net.optifine`)
- Lunar Client (`com.moonsworth.lunar`), Badlion
- XaeroMap, Journeymap, VoxelMap
- Tweakeroo, MaLiLib, Litematica, MiniHud
- Zoomify (Vanilla 1.20で公式機能化)
- ViaVersion, ViaFabricPlus
- PacketEvents, ByteBuddy, Javassist, Guava EventBus, Forge EventBus
- NoChatReports, DiscordGamesDK
- mixinextras

## 追加検出アイデア (実装済み/提案)

- [x] パッケージハッシュ
- [x] ファイル名サブストリングハッシュ
- [x] メモリモジュール監視
- [x] 入力真正性 (低レベルフック + 統計)
- [x] 回転解析 (Snap, GCD)
- [x] CPS解析 (CVベース)
- [ ] 移動解析 (Timer, Speed)
- [ ] OpenGLフック検出
- [ ] ClassLoader検証
- [ ] スクリーンショット検証

## 数学的正当性ハイライト

- ハッシュ衝突確率: n=71で P≈1.4e-16 (無視可能)
- CPS誤検知: 単発3% → 5連続要求で 3.2e-9
- RWX検出: Poisson λ=15で閾値30 → 誤検知0.03%

## ライセンス

MIT

---

<a name="english"></a>
## English Summary

Client-side multi-layer anticheat: package hash scanning (no plaintext strings), native DLL monitoring mods folder (ReadDirectoryChangesW/inotify), memory guard (EnumProcessModules, RWX scan), input authenticity via low-level hooks + rotation/CPS statistical analysis. Self-ban custom packet `anticheat:violation` triggers `minecraft:ban` on server plugin.

Built for Fabric 1.20.1 + Paper 1.20.1. See `docs/THREAT_MODEL.md` and `docs/ARCHITECTURE.md` for deep dive on contradictions and math.

