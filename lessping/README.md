# LessPing-NArena

**「narena」と入力するだけで、P2P の仮想LANトンネル経由で直接接続して ping を下げる。**

Hamachi / ZeroTier / Tailscale と同じ発想。Minekube トンネルなどの**リレー経由**
（友達 → リレー → サーバーPC）ではなく、友達の MOD とサーバーPC の間に
**UDP ホールパンチで直接つながり**、その上に Minecraft の接続を流します。

```
今:     友達 ──▶ Minekubeリレー（遠回り）──▶ サーバーPC      ping = 経由の分だけ増える
LessPing: 友達のMOD ⇄ UDP ホールパンチで直結 ⇄ サーバーPC のプラグイン ──▶ localhost の Paper
                                                               ping = 物理的な最短経路
```

v0.2.0 から **参加者は「narena」だけ打てばよく、先に本サーバーに入る必要はありません**。
また **サーバー主はゲームを起動していなくても OK**（ホスト側のトンネル端末は
Paper プラグイン自身が務めます）。

## 構成要素

| 成果物 | 入れる人 | 役割 |
|--------|----------|------|
| `lessping-narena-relay-<ver>.jar`（Paper プラグイン） | **サーバー** | ① ゲーム内シグナリング中継 ② **ホスト側トンネル端末**（P2P の受け口。STUN・穴あけ・LPX→localhost Paper 中継を全部この中で） |
| `lessping-narena-mod-<ver>.jar`（Fabric MOD） | **参加者全員** | UDP トンネル本体。「narena」への接続をトンネルへ差し替える |

MC 1.21.11 / Fabric（MOD）/ Paper（プラグイン）/ Java 21。

**サーバー主は MOD を入れなくてもよくなりました**（入れればゲーム内 HELLO 経路も使える）。

## 仕組み（v0.2.0）

1. Paper プラグインが起動すると、**サーバーPC 上で UDP 端末**を開き、STUN で
   自分の公開アドレスを調べる（30 秒ごとに更新 = NAT マップの維持）
2. プラグインはサーバーリスト ping（status ping）の応答の **version 名**に
   自分の候補アドレスを `LP1:...` として載せる。通常のクライアントには
   （プロトコル一致なら）表示されないし、ゲーム通信はこの経路を流れない
3. 参加者の MOD は**クライアント起動直後**にその ping を打って候補を取得 → 穴あけ開始
   （本サーバーにログインする必要がない。15 秒ごとに再試行し、確立したら stop）
4. 両者で UDP パンチが成功したら **直接トンネル確立**（keepalive で維持）
5. 参加者がマルチプレイのアドレス欄に **`narena`** と入力して接続 →
   MOD がアドレス解決を差し替え、トンネル経由でサーバーPC の Paper へ直結
   - サーバーリストの ping 表示もトンネル経由（実際の直結 RTT が表示される）
   - まだトンネルが張れていないときは最大 10 秒待って、それでもダメなら
     **自動的に通常経路（本サーバー経由）へ接続**。「narena」が繋がらなくなることは無い
6. （従来のゲーム内経路も並行して動く: 両者が本サーバーに入っていれば
   HELLO/INTRO でも紹介し合う。どちらか先に確立した方が使われる）

**トンネルは Minecraft の接続と独立した純 UDP** なので、サーバーを出入りしても
張り直し不要。穴あけに失敗しても（対称 NAT など）通常接続にフォールバックするだけ。

LPX の終了処理は TCP 準拠の半閉鎖: `close()` は自分の送信側だけを閉じ
（残りデータの ACK 確認後に CLOSE を送信）、相手からの CLOSE は
「もう相手からは来ない」印として全データ到着後に EOF を流す。
CLOSE フレーム自体がロスしても keepalive 間隔で再送される。

## セットアップ

### サーバー主（ホスト）

1. Paper の `plugins/` に `lessping-narena-relay-<ver>.jar` を入れて起動。**以上**
   - 端末の UDP ポートは自動。転送先はサーバー自身（Paper のポート）
   - 確認: コンソールに `ホスト端末を開始: UDP ....`、サーバーリスト ping に
     `LP1:...` が載る（`/lp` でも状態が見られる）
2. （任意）`plugins/LessPing-NArena/config.yml` の `host-endpoint.secret` に
   共有鍵を設定すると、鍵を持つ参加者だけがトンネルを張れる
   （その場合、参加者は `config/lessping/client.json` の `secret` に同じ値を設定）
3. **ゲームを起動していなくても参加者は「narena」で入れます**
   （自分が遊ぶときは今までどおり localhost 接続でOK。MOD は入れなくても可）

### 参加者

1. MOD を入れる（Fabric API 必須）。設定は無編集で動く
2. ゲームを起動したら、マルチプレイでサーバー追加、アドレスに **`narena`** と入れて接続
   - ログに `[LessPing] ★ ホストへのトンネル確立` と出ていたら直結できている
   - `サーバーからホスト (...) のエンドポイントを受信` → 穴あけの様子もログに出る
3. 通信はトンネル（直結）。失敗時は自動で通常経路に流れるので、繋がらないことはない

## なぜ速くなるのか / ならない場合

- Minekube 等のリレーが経路上にあった場合、その分の距離とホップが消える
- 同じ LAN（家の中）なら実質 LAN 接続（プライベート IP の候補も自動で試す）
- **対称 NAT 同士**だと穴あけが失敗する → その場合は通常接続に戻るだけ（壊れない）
- リレーを経由していなかった環境（もともと直結だった場合）は改善しない

## セキュリティについて（正直な話）

- トンネル自体の暗号化は v1 では**ない**（Minecraft プロトコルのオンラインモード暗号は
  エンドツーエンドでそのまま効く）
- 鍵を設定しない場合、サーバーアドレスを知る人は誰でも（status ping で候補を
  見て）トンネルを張れる = 本サーバーに接続できるのと同じ範囲。
  **候補アドレス（= サーバーPC の IP）も ping 応答を見れば分かる**ので、
  公開したくない場合は `host-endpoint.secret` を設定すること（応答はマスクされ、
  正しい鍵を持つ人だけが読める）
- 中継プラグインが扱うのは「アドレスの紹介」だけ。**ゲームトラフィックは一切流れない**
- 「narena」という名前はドメインではない（ドット無し）ので、MOD を抜けば
  バニラの挙動は「接続できない」だけ

## 設定（クライアント `config/lessping/client.json`）

| キー | 既定 | 意味 |
|------|------|------|
| `enabled` | `true` | 無効にすると何もしない |
| `magicNames` | `["narena"]` | この名前への接続をトンネルへ差し替える |
| `signalServer` | `"n-arena.play.minekube.net"` | シグナリング用の本サーバーアドレス。ここに status ping を打つ |
| `signalPollMs` | `15000` | シグナリングのポーリング間隔（トンネル未確立時のみ） |
| `hostPlayer` | `"Ifuto_mitai"` | ゲーム内 INTRO 経路で誰とのトンネルを張るか |
| `secret` | `""` | サーバー側 `host-endpoint.secret` と揃える共有鍵 |
| `hostMode` | `false` | （旧）クライアント自身がトンネルの受け手になる。プラグイン端末があれば不要 |
| `localTcpPort` | `25599` | ローカル受け口（Minecraft はここへ接続する） |
| `udpPort` | `0`（自動） | UDP のポート。固定したいときだけ指定 |
| `backend` | `127.0.0.1:25565` | （hostMode 時）届いた接続の転送先 |
| `stunServers` | Cloudflare / Google | 自分のアドレスを調べる STUN |
| `idleTimeoutMs` | `20000` | これ以上音信がなければトンネル断とみなす |

## 設定（サーバー `plugins/LessPing-NArena/config.yml`）

| キー | 既定 | 意味 |
|------|------|------|
| `host-endpoint.enabled` | `true` | ホスト端末（サーバーPC で P2P の受け口）を有効にする |
| `host-endpoint.udp-port` | `0`（自動） | 端末の UDP ポート |
| `host-endpoint.backend` | `""`（サーバー自身） | トンネルで受けた接続の転送先 |
| `host-endpoint.secret` | `""` | 共有鍵（設定すると鍵を持つ人だけトンネル可、応答もマスク） |
| `host-endpoint.stun-servers` | Cloudflare / Google | 公開アドレスを調べる STUN |
| `host-endpoint.stun-interval-ms` | `30000` | STUN 更新間隔（NAT マップの維持） |
| `host-endpoint.publish` | `true` | ping 応答に候補を載せるか（false でゲーム内 INTRO 経路のみ） |
| `host-endpoint.max-connections` | `32` | 同時に受け付けるトンネル接続の上限 |

## コマンド（サーバー）

- `/lessping`（`/lp`）— ホスト端末の状態（UDP ポート・公開アドレス・接続数・認証）と
  登録済みプレイヤーを表示

## 開発

```
./gradlew -p lessping build
```

- `tunnel/lpx/` — 自作の reliable-UDP（LPX）+ STUN + 共通鍵処理。**MOD とプラグインが
  共有する純粋 Java のプロトコル実装**（プラグインはこのフォルダを直接コンパイル対象にする）。
  **12% パケットロス + ジッタのシミュレーションで双方向 2MB が完全に届くことを確認する
  ループバックテストがビルドのたびに走る**（`lpxLoopbackTest`）
- `signal/SignalClient.java` — status ping を自前で打ち、version 名から
  ホスト端末の候補を取り出すシグナリングクライアント
- `mixin/AllowedAddressResolverMixin` — 接続とサーバーリスト ping の両方の
  アドレス解決を差し替える
- `relay/TunnelHost.java` — サーバーPC 側のトンネル端末（STUN・PUNCH・LPX→backend）
