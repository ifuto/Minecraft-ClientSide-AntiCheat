# MCSA 通信プロトコル

- protocol = 1
- 伝送: Minecraft のカスタムペイロード（Bukkit からはプラグインメッセージとして見える）
- 実装:
  - クライアント … `client/src/main/java/dev/ifuto/mcsa/client/net/`
  - サーバー … `server/src/main/java/dev/ifuto/mcsa/server/net/Wire.java`

両側は **1 バイト単位で同じ並び** を書く。どちらかを変えたら必ずもう片方も変えること。
`tools/verify_protocol.py` がチャンネル名・JSON キー・config キーのずれを検出する。

---

## 0. エンコーディング規則

Minecraft の慣例どおり。

| 型       | 表現                                                     |
|----------|----------------------------------------------------------|
| varint   | 7 bit ずつ下位から。最長 5 バイト                         |
| string   | varint でバイト長 + UTF-8（終端なし）                     |
| bytes    | varint でバイト長 + 生バイト列                            |

## 1. チャンネル

| チャンネル          | 方向 | 内容                                   |
|---------------------|------|----------------------------------------|
| `mcsa:challenge`    | S2C  | セッションと nonce の発行（申告の要求） |
| `mcsa:task`         | S2C  | OP からの指示（再申告 / 画面取得 / 監視）|
| `mcsa:adminmsg`     | S2C  | OP 用 MOD への応答テキスト              |
| `mcsa:shot`         | S2C  | 保存済みの証拠を OP のクライアントへ転送 |
| `mcsa:hello`        | C2S  | 最小限の自己申告（導入判定）            |
| `mcsa:report`       | C2S  | レポート本体の断片                      |
| `mcsa:seal`         | C2S  | HMAC による封印                         |
| `mcsa:evidence`     | C2S  | 証拠（画面 / テキスト）の断片           |
| `mcsa:digest`       | C2S  | 常時監視の状態ダイジェスト              |
| `mcsa:admin`        | C2S  | OP 用 MOD からのコマンド実行要求        |

Bukkit 側では `mcsa:challenge` / `mcsa:task` / `mcsa:adminmsg` / `mcsa:shot` を outgoing、
他 6 つを incoming として登録する
（`McsaPlugin#incomingChannels` / `#outgoingChannels`）。

## 2. `mcsa:challenge`（S2C）

| # | 型     | 名前          | 説明                                        |
|---|--------|---------------|---------------------------------------------|
| 1 | varint | protocol      | 期待するプロトコル版（1）                    |
| 2 | varint | sessionId     | セッション ID（レポートでそのまま返す）      |
| 3 | string | nonce         | 16 バイトのランダム値の 16 進文字列（32 文字）|
| 4 | string | serverId      | サーバー名（`server.properties` の server-name）|
| 5 | varint | probeCount    | 探索クラスの数（上限 256）                   |
| 6 | string | probeClass[]  | 存在を確認してほしいクラス名                 |
| 7 | varint | collectFlags  | 1=MOD 2=リソースパック 4=シェーダー 8=JVM 引数 |

## 3. `mcsa:hello`（C2S）

| # | 型     | 名前         | 説明                                        |
|---|--------|--------------|---------------------------------------------|
| 1 | varint | protocol     | クライアントのプロトコル版                   |
| 2 | string | modVersion   | MOD のバージョン（`fabric.mod.json`）        |
| 3 | string | selfJarSha256| 自分自身の jar の SHA-256（16 進、64 文字）  |
| 4 | string | keyId        | 鍵の指紋（後述）                             |
| 5 | varint | flags        | bit0=難読化ビルド bit1=自己整合性 OK bit2=キャッシュ |

## 4. `mcsa:report`（C2S、複数回）

| # | 型     | 名前      | 説明                                    |
|---|--------|-----------|-----------------------------------------|
| 1 | varint | sessionId | チャレンジで受け取った値                 |
| 2 | varint | seq       | 断片番号（0 始まり）                     |
| 3 | varint | total     | 断片の総数                               |
| 4 | varint | length    | `data` のバイト数                        |
| 5 | bytes  | data      | gzip したレポート JSON の一部            |

- 断片サイズは 16384 バイト（`ReportPayload.CHUNK_SIZE`）。
  Bukkit のプラグインメッセージ上限 32767 バイトに余裕を持たせている。
- サーバーは `sessionId` が一致する断片だけを束ね、`total` 個揃うと再構成する。

## 5. `mcsa:seal`（C2S）

| # | 型     | 名前        | 説明                             |
|---|--------|-------------|----------------------------------|
| 1 | varint | sessionId   | セッション ID                     |
| 2 | varint | total       | 送った断片数                      |
| 3 | varint | dataLength  | 再構成後の総バイト数              |
| 4 | string | hmac        | HMAC-SHA256 の 16 進文字列（64 文字）|

## 5.1 `mcsa:task`（S2C）

OP が `/ac scan|shot|watch` を打つたびに送る。**指示ごとに nonce を新しくする**
（同じ指示を使い回せないようにするため）。

| # | 型     | 名前            | 説明                                              |
|---|--------|-----------------|---------------------------------------------------|
| 1 | varint | protocol        | プロトコル版                                       |
| 2 | varint | sessionId       | セッション ID                                      |
| 3 | string | nonce           | 新しい nonce（証拠の HMAC に混ぜる）                |
| 4 | varint | kind            | 1=RESCAN 2=CAPTURE 3=NOTE 4=WATCH_ON 5=WATCH_OFF   |
| 5 | varint | intervalSeconds | WATCH_ON のときのダイジェスト間隔（秒）             |
| 6 | string | reason          | 取得理由（証拠の sidecar に記録される）             |

- `CAPTURE` は **対象プレイヤーの画面には何も表示しない**。チャット・トースト・撮影音・
  バニラのスクリーンショット通知、いずれも出さない（`SilentCapture`）。
- クライアント側で `allowCapture=false` のときは、代わりに `KIND_NOTE` のテキストで
  「拒否された」ことを返す。

## 5.2 `mcsa:evidence`（C2S、複数回）

| # | 型     | 名前  | 説明                                            |
|---|--------|-------|-------------------------------------------------|
| 1 | varint | sessionId | セッション ID                               |
| 2 | varint | kind  | 1=画面（PNG/JPEG） 3=テキスト                   |
| 3 | varint | seq   | 断片番号（0 始まり）                            |
| 4 | varint | total | 断片の総数（上限 1024）                         |
| 5 | string | name  | ファイル名（サーバー側でサニタイズされる）       |
| 6 | string | hmac  | **全データに対する** HMAC-SHA256（16 進）        |
| 7 | bytes  | data  | 断片（最大 16384 バイト）                       |

HMAC は断片 0 だけでなく全断片に同じ値を入れる。断片 0 が欠落しても検証できるようにするため。
サーバーは全断片が揃ってから `HMAC(key, nonce ‖ data)` を検証し、
一致したものだけを `plugins/MCSA/evidence/` に保存する（不一致でも保存はするが
`hmacValid=false` を sidecar に残し、アラートを出す）。

## 5.3 `mcsa:digest`（C2S、定期的）

「起動時だけ正常な顔をする」タイプへの対策。2 段階認証と同じで、
**1 回通ることではなく通し続けることを条件にする**。

| # | 型     | 名前            | 説明                                          |
|---|--------|-----------------|-----------------------------------------------|
| 1 | varint | sessionId       | セッション ID                                  |
| 2 | varint | seq             | 送信回数（単調増加）                           |
| 3 | string | stateHex        | 状態の FNV-1a 16 進（16 文字）。中身は送らない  |
| 4 | varint | flags           | bit0=自分の jar 変化 bit1=Mixin 変化 bit2=ライブラリ変化 bit3=検知対象出現 bit4=クラスローダ変化 bit5=画面取得対応 |
| 5 | varint | intervalSeconds | いまの間隔（秒）                               |

サーバーは `flags != 0` を検出すると即アラートを出し、詳細を取るために再チャレンジする。
`watchdog.timeout-seconds` を超えて届かなくなったら `WATCHDOG_SILENT` として記録する。

## 5.4 `mcsa:shot`（S2C、複数回）

サーバーが保存した証拠を、**指示を出した OP のクライアント**へ転送する。
OP 用 MOD（`mcsa-admin`）が `mcsa:shot` を登録している相手だけに送る
（`Player#hasListeningPluginChannel` で判定）。

| # | 型     | 名前       | 説明                                  |
|---|--------|------------|---------------------------------------|
| 1 | varint | transferId | 転送 ID（断片を束ねる）                |
| 2 | varint | kind       | 1=画面 3=テキスト                     |
| 3 | varint | seq        | 断片番号（0 始まり）                   |
| 4 | varint | total      | 断片の総数（上限 1024）                |
| 5 | string | name       | ファイル名                             |
| 6 | bytes  | data       | 断片（最大 16384 バイト）               |

受け取った OP 側は `.minecraft/mcsa-evidence/` に保存し、ゲーム内ビューア
（`EvidenceScreen`）を開く。設定（`config/mcsa/admin.json`）で OS のビューアを
自動で開くこともできる。サーバー側の原本と監査ログはそのまま残る。

## 5.5 `mcsa:admin` / `mcsa:adminmsg`

OP 用 MOD（`admin/`）との連携。

- `mcsa:admin`（C2S）: `string` 1 個。`/ac` の引数そのもの（例 `shot Steve reason`）。
  サーバーは送信者に `mcsa.admin` があるか確認してから `Bukkit.dispatchCommand` する。
- `mcsa:adminmsg`（S2C）: `string` 1 個。実行結果や証拠の受信通知。

## 6. 署名

```
hmac  = hex( HMAC-SHA256( key, utf8(nonce) ‖ gzipBytes ) )
keyId = hex( HMAC-SHA256( key, utf8("mcsa/keyid/v1") ) )[0..12]
```

- `key` は 32 バイト。クライアントは `client/build.gradle` が生成する
  `dev.ifuto.mcsa.client.gen.KeyMaterial`（XOR マスクされた int 配列）から復元する。
- 同じ値をサーバーの `config.yml` → `hmac.key` に 16 進で設定する。
- `keyId` は鍵そのものを送らずに「同じ鍵のビルドか」を照合するためのもの。
- 鍵はクライアントの中にしかないため、これは改ざんの **抑止** であって証明ではない（`DESIGN.md` §5.2）。

## 7. レポート JSON（gzip 前の本文）

`com.google.gson.JsonObject` をそのまま `toJson()` した、改行なしの JSON。

```jsonc
{
  "proto": 1,
  "nonce": "チャレンジの nonce",
  "sessionId": 123,
  "ts": 1780000000000,
  "modVersion": "1.0.0",

  "client": {
    "mc": "1.21.11",
    "os": "Linux",
    "arch": "amd64",
    "jvm": "21.0.5",
    "server": "play.example.com:25565"
  },

  "redacted": ["shaderPacks"],          // プレイヤーが送信を拒否した項目

  "mods": [                              // ロード済み MOD
    {
      "id": "sodium",
      "name": "Sodium",
      "version": "0.6.0",
      "file": "sodium-fabric-0.6.0.jar", // mods/ 直下に無い場合は無い
      "size": 1234567,
      "sha256": "…",
      "nested": false                    // 他 MOD に同梱されている場合 true
    }
  ],

  "modJars": [                           // mods/ の実ファイル
    {
      "file": "some.jar",
      "size": 12345,
      "sha256": "…",
      "manifest": true,                  // fabric.mod.json を持っているか
      "ids": ["some"],
      "nestedIds": ["some-lib"],
      "loaded": true
    }
  ],

  "resourcePacks": ["vanilla", "file/My Pack.zip"],
  "resourcePackFiles": [ { "name": "My Pack.zip", "type": "file", "size": 1234, "sha256": "…" } ],

  "shaders": { "loader": "iris", "enabled": true, "pack": "Complementary" },
  "shaderPackFiles": [ { "name": "Complementary.zip", "type": "file", "size": 1234, "sha256": "…" } ],

  "probes": {
    "jvmArgCount": 12,
    "suspiciousJvmArgs": ["-javaagent:/tmp/x.jar"],
    "agent": false,
    "debugger": false,
    "classLoader": "net.fabricmc.loader.impl.launch.knot.KnotClassLoader",
    "defaultProbesFound": [],
    "probesFound": [],
    "oddClasspath": [],

    "mixinPresent": true,
    "mixinConfigCount": 37,
    "mixinConfigs": ["fabric-lifecycle-events-v1.client.mixins.json"],
    "mixinUnknownConfigs": ["mixins.somecheat.json"],
    "mixinInjectedCount": 12,
    "mixinInjectedSample": ["net.minecraft.class_746#$handler$abc000"],

    "agentClassPresent": false,
    "attachApiPresent": false,
    "suspiciousThreads": [],
    "threadCount": 41,
    "classloaderChain": ["net.fabricmc.loader.impl.launch.knot.KnotClassLoader", "…"],

    "libraryCount": 128,
    "libraryListTruncated": false,
    "libraries": ["fabric-loader-0.19.5.jar"],
    "suspiciousLibraries": [],

    "jarScan": {
      "scannedJars": 42,
      "scannedEntries": 180233,
      "skippedJars": 0,
      "patternCount": 18,
      "scannedJarNames": ["meteor-client.jar"],
      "matchedClasses": ["meteordevelopment.meteorclient.MeteorClient@meteor-client.jar"]
    },

    "watchdog": {
      "intervalSeconds": 45,
      "uptimeSeconds": 1200,
      "seq": 27,
      "state": "9f1c0a2b3d4e5f60",
      "changed": false,
      "changes": []
    },
    "captureSupported": true,
    "runtimeTampered": false,
    "dynamicClassesFound": [],

    "findings": ["MIXIN_UNKNOWN_CONFIG:mixins.somecheat.json"],
    "findingCount": 1,
    "findingDropped": 0
  },

  "consent": {
    "accepted": true,
    "hash": "9f2c1a4b7e04",
    "at": 1758240000000
  },

  "self": {
    "jarName": "mcsa-client-1.0.0.jar",
    "jarSize": 234567,
    "jarSha256": "…",
    "entryFingerprint": "…",
    "obfuscated": true,
    "className": "dev.ifuto.mcsa.client.integrity.SelfIntegrity"
  }
}
```

サーバー側が読むキーは `ClientReport` / `ModPolicy` / `InjectionPolicy` / `AcCommand` に集約してある。

### `probes.findings[]` のコード一覧

クライアントは判定せず、観測結果を `CODE:詳細` の形で送るだけ。判定は
`InjectionPolicy`（サーバー）が行う。★ = 既定で重大扱い。

| コード                   | 意味                                              |
|--------------------------|---------------------------------------------------|
| `CHEAT_CLASS` ★          | 検知対象のクラスがロードされている                |
| `CHEAT_CLASS_LOADED` ★   | サーバー指定のクラスがロード済み（jar 名つき）     |
| `CHEAT_CLASS_IN_JAR` ★   | jar の中に検知対象のクラスを見つけた              |
| `JVM_ARG_SUSPICIOUS` ★   | `-javaagent` / `-agentlib` / `-xbootclasspath`     |
| `DEBUGGER_ARG` ★         | jdwp / dt_socket                                  |
| `AGENT_CLASS` ★          | `sun.instrument.InstrumentationImpl` がロード済み  |
| `THREAD_SUSPICIOUS`      | 怪しい名前のスレッドが動いている                  |
| `CLASSLOADER_ODD`        | Fabric の Knot 以外のクラスローダが混ざっている   |
| `CLASSLOADER_NOT_FABRIC` | そもそも Fabric ローダで動いていない              |
| `LIBRARY_SUSPICIOUS`     | 怪しい名前のライブラリ（jar）を読み込んでいる     |
| `MIXIN_UNKNOWN_CONFIG` ★ | インストール済み MOD のどれにも属さない Mixin 設定 |
| `MIXIN_CONFIG_COUNT`     | Mixin 設定の総数（参考）                          |
| `MIXIN_INJECTED`         | ゲームプレイ関連クラスへの注入痕跡の数（参考）     |
| `MIXIN_INJECT_SAMPLE`    | 注入痕跡のサンプル（参考）                        |
| `LIBRARY_COUNT`          | 読み込み中ライブラリの数（参考）                  |
| `STATE_CHANGED` ★        | ウォッチドッグが起動時との差分を検出              |
| `CONSENT_MISSING` ★      | プライバシィ告知に同意していない（`ModPolicy`）    |
| `CONSENT_NOTICE_MISMATCH`| 運営が配っている文面と違う版に同意している         |
| `ODD_CLASSPATH`          | クラスパスに見慣れない jar                        |

## 8. サイズとエラー処理

- 再構成後の gzip バイト列の上限は 8 MiB（`SessionManager.MAX_REPORT_BYTES`）。超えたら破棄。
- 断片の `total` は 4096 まで。`sessionId` が一致しない断片は捨てる。
- HMAC 検証失敗時の挙動は `hmac.on-invalid`（`FLAG` = 受理してフラグ / `REJECT` = 破棄）。
- 解析に失敗したときの理由は `Session#lastError` に保持し、`/ac info <player>` に出す。
