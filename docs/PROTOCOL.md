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

| チャンネル          | 方向 | 内容                     |
|---------------------|------|--------------------------|
| `mcsa:challenge`    | S2C  | セッションと nonce の発行 |
| `mcsa:hello`        | C2S  | 最小限の自己申告（導入判定）|
| `mcsa:report`       | C2S  | レポート本体の断片        |
| `mcsa:seal`         | C2S  | HMAC による封印           |

Bukkit 側では `mcsa:challenge` を outgoing、他 3 つを incoming として登録する。

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
    "oddClasspath": []
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

サーバー側が読むキーは `ClientReport` / `ModPolicy` / `AcCommand` に集約してある。

## 8. サイズとエラー処理

- 再構成後の gzip バイト列の上限は 8 MiB（`SessionManager.MAX_REPORT_BYTES`）。超えたら破棄。
- 断片の `total` は 4096 まで。`sessionId` が一致しない断片は捨てる。
- HMAC 検証失敗時の挙動は `hmac.on-invalid`（`FLAG` = 受理してフラグ / `REJECT` = 破棄）。
- 解析に失敗したときの理由は `Session#lastError` に保持し、`/ac info <player>` に出す。
