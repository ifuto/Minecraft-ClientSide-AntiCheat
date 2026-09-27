# LessPing-NArena

**「narena」と入力するだけで、P2P の仮想LANトンネル経由で直接接続して ping を下げる。**

Hamachi / ZeroTier / Tailscale と同じ発想。Minekube トンネルなどの**リレー経由**
（友達 → リレー → サーバーPC）ではなく、友達の MOD とサーバーPC の間に
**UDP ホールパンチで直接つながり**、その上に Minecraft の接続を流します。

```
今:     友達 ──▶ Minekubeリレー（遠回り）──▶ サーバーPC      ping = 経由の分だけ増える
LessPing: 友達のMOD ⇄ UDP ホールパンチで直結 ⇄ サーバーPC のプラグイン ──▶ localhost の Velocity
                                                               ping = 物理的な最短経路
```

v0.2 から **参加者は「narena」だけ打てばよく、先に本サーバーに入る必要はありません**。
また **サーバー主はゲームを起動していなくても OK**（ホスト側のトンネル端末は
**Velocity プラグイン**自身が務めます）。v0.2.1 で Paper プラグインから
**Velocity プラグイン**に移行し、SMP / PvP のどちらのサーバーにいるプレイヤーにも
対応しました。v0.3.0 で **Cloudflare Tunnel と組む IP 非公開モード**（WebSocket
ブリッジ）が入りました。**直結（最速）→ Cloudflare 経由（IP 非公開）→ Minekube** の
順に自動フォールバックします。

## 構成要素

| 成果物 | 入れる人 | 役割 |
|--------|----------|------|
| `lessping-narena-relay-<ver>.jar`（**Velocity プラグイン**） | **サーバー（Velocity）** | ① ゲーム内シグナリング中継（SMP/PvP どちらにいても拾う） ② **ホスト側トンネル端末**（P2P の受け口。STUN・穴あけ・LPX→localhost Velocity 中継を全部この中で） |
| `lessping-narena-mod-<ver>.jar`（Fabric MOD） | **参加者全員** | UDP トンネル本体。「narena」への接続をトンネルへ差し替える |

MC 1.21.11 / Fabric（MOD）/ **Velocity 4.x（Java 25）**（プラグイン）/ Java 21。

**サーバー主は MOD を入れなくてもよい**し、SMP / PvP 個々の Paper に入れるものもありません。

## 仕組み

1. Velocity プラグインが起動すると、**サーバーPC 上で UDP 端末**を開き、STUN で
   自分の公開アドレスを調べる（30 秒ごとに更新 = NAT マップの維持）
2. プラグインはサーバーリスト ping（status ping）の応答の **version 名**に
   自分の候補アドレスを `LP1:...`（鍵設定時は AES-GCM 暗号化された `LP2:...`）として載せる。
   MOTD や人数は Velocity の通常応答を
   そのまま維持。通常のクライアントには（プロトコル一致なら）version 名は表示されず、
   ゲーム通信もこの経路を流れない
3. 参加者の MOD は**クライアント起動直後**にその ping を打って候補を取得 → 穴あけ開始
   （本サーバーにログインする必要がない。15 秒ごとに再試行し、確立したら stop）
4. 両者で UDP パンチが成功したら **直接トンネル確立**（keepalive で維持）
5. 参加者がマルチプレイのアドレス欄に **`narena`** と入力して接続 →
   MOD がアドレス解決を差し替え、トンネル経由でサーバーPC の **Velocity** へ直結。
   トンネルは 25565 と同じ入り口（プロキシ直下）なので **`/server` での SMP⇔PvP 移動も
   そのまま使える**（バックエンド間はローカルなので追加遅延なし）
   - サーバーリストの ping 表示もトンネル経由（実際の直結 RTT が表示される）
   - まだトンネルが張れていないときは数秒待って、それでもダメなら順にフォールバック:
     **① WebSocket 経由（`wsUrl`、Cloudflare Tunnel。IP 非公開）→ ② 本サーバー（Minekube）
     へ素通し**。「narena」が繋がらなくなることは無い
6. （従来のゲーム内経路も並行して動く: 両者が本サーバーに入っていれば
   HELLO/INTRO でも紹介し合う。SMP と PvP の**どちらにいても**プロキシが受け取る。
   どちらか先に確立した方が使われる）

**トンネルは Minecraft の接続と独立した純粋 UDP** なので、サーバーを出入りしても
張り直し不要。穴あけに失敗しても（対称 NAT など）通常接続にフォールバックするだけ。

LPX の終了処理は TCP 準拠の半閉鎖: `close()` は自分の送信側だけを閉じ
（残りデータの ACK 確認後に CLOSE を送信）、相手からの CLOSE は
「もう相手からは来ない」印として全データ到着後に EOF を流す。
CLOSE フレーム自体がロスしても keepalive 間隔で再送される。

## セットアップ

### サーバー主（ホスト）

1. **Velocity の `plugins/`** に `lessping-narena-relay-<ver>.jar` を入れて
   Velocity を再起動。**以上**（SMP / PvP 側に何も入れる必要はない）
   - 旧 Paper 版プラグイン（v0.2.0 以前）がバックエンドに入っている場合は**削除**する
     （Velocity 版がシグナリングを吸収するため二重になる）
   - `velocity.toml` の設定変更（`ping-passthrough` 等）は**不要**
2. 設定は `plugins/lessping-narena/config.properties` に生成されるが、
   **既定のままで動く**（転送先 = この Velocity 自身、UDP ポート自動、鍵 = 内蔵鍵）。
   ※ v0.3.1 から鍵まわりの既定が変わったので、**MOD も 0.3.1 以降に揃えること**
   （古い MOD とは P2P が張れず、CF/Minekube 経由にフォールバックする）
3. 確認: コンソールに
   ```
   ホスト端末を開始: UDP xxxx → backend 127.0.0.1:25565
   ```
   と出れば OK。プロキシのコンソールやゲーム内で **`/lp`** を打つと状態が見られる
4. **ゲームを起動していなくても参加者は「narena」で入れます**
   （自分が遊ぶときは今までどおり localhost / 通常アドレスで OK。MOD は入れなくても可）

### 参加者

1. MOD を入れる（Fabric API 必須）。設定は無編集で動く
2. ゲームを起動したら、マルチプレイでサーバー追加、アドレスに **`narena`** と入れて接続
   - ログに `[LessPing] ★ ホストへのトンネル確立` と出ていたら直結できている
   - `サーバーからホスト (...) のエンドポイントを受信` → 穴あけの様子もログに出る
3. 通信はトンネル（直結）。失敗時は自動で通常経路に流れるので、繋がらないことはない

## Cloudflare Tunnel と組む（IP 非公開モード・v0.3.0〜）

P2P 直結は最速だが「繋いだ相手には自分の IP が見える」のは避けられない。
Minekube をやめて**公開面を Cloudflare だけにする**と、サーバーPC の IP を
誰にも知られずに運用できる（DDoS も Cloudflare が吸う。家のポートは全閉でよい）:

```
友達のMOD ──WSS(443)──▶ Cloudflare エッジ ──▶ cloudflared(家) ──▶ プラグ内蔵の
WebSocket ブリッジ(:8081) ──▶ localhost の Velocity(25565) ──▶ SMP / PvP
```

### セットアップ

1. **Cloudflare Zero Trust** → Tunnels → 対象のトンネルの Public hostname
   （`narena.dpdns.org`）の**サービスを `http://localhost:8081` に変更**
   （`tcp://localhost:25565` から変更。WebSocket は `http://` サービスで通る）
2. Public hostname に **Access ポリシーを付けない**（メール認証があると MC は繋げない）
3. プラグイン側は設定不要（`ws.enabled=true` が既定。`plugins/lessping-narena/config.properties`）
4. 参加者の MOD も設定不要（`wsUrl` の既定が `wss://narena.dpdns.org`）
5. 確認: ブラウザで `https://narena.dpdns.org/` を開いて
   `LessPing-NArena WS bridge OK` と出れば疎通 OK。プロキシで `/lp` を打つと
   `WSブリッジ: ws://127.0.0.1:8081 接続中 0/16` のように出る

### 動作

- **シグナリング（status ping）も `wsUrl` 経由を優先**するので、Minekube が無くても
  「narena」だけで P2P 直結を張れる（`signalServer` は予備経路になる）
- P2P 直結に失敗したら（対称 NAT など）自動で **WSS → Cloudflare → Velocity** に
  繋ぐ。直結より +5〜15ms（CF 東京エッジ経由）だが、Minekube 経由より速い可能性が高い
- Minekube と併存もできる（`wsUrl` を空にすれば従来動作）

## なぜ速くなるのか / ならない場合

- Minekube 等のリレーが経路上にあった場合、その分の距離とホップが消える
- 同じ LAN（家の中）なら実質 LAN 接続（プライベート IP の候補も自動で試す）
- **対称 NAT 同士**だと穴あけが失敗する → その場合は通常接続に戻るだけ（壊れない）
- リレーを経由していなかった環境（もともと直結だった場合）は改善しない

## セキュリティについて（正直な話）

- トンネル自体の暗号化は v1 では**ない**（Minecraft プロトコルのオンラインモード暗号は
  エンドツーエンドでそのまま効く）
- **既定で内蔵鍵が有効**（v0.3.1〜・設定不要）。ping 応答は **AES-256-GCM で暗号化**
  （`LP2:`）され、PUNCH も鍵から導出したトークンが必須。**サーバーアドレスを知った
  だけの人には、サーバーPC の IP を読めず、トンネルも張れない**
- ただし内蔵鍵は MOD に入っており GitHub でも公開されている。**MOD を手に入れた人
  なら解読できる**（信頼境界が「アドレスを知る人」から「MOD を持る人」に上がるだけ）。
  本気で隠すなら `host-endpoint.secret` に独自鍵を設定し、参加者にも
  `config/lessping/client.json` の `secret` に同じ値を設定してもらう。
  `"none"` を設定すると無認証の平文運用（`LP1:`）に戻る
- P2P 直結の性質上、**実際に繋いだ相手は（鍵がなくても）あなたの IP を知る**。
  これは直結である以上避けられない。IP を見せたくない相手には CF 経由（WSS）で
  入ってもらうか、`host-endpoint.publish: false` で直結候補の公開を止める
- 中継プラグインが扱うのは「アドレスの紹介」だけ。**ゲームトラフィックは一切流れない**
- 「narena」という名前はドメインではない（ドット無し）ので、MOD を抜けば
  バニラの挙動は「接続できない」だけ

## 設定（クライアント `config/lessping/client.json`）

| キー | 既定 | 意味 |
|------|------|------|
| `enabled` | `true` | 無効にすると何もしない |
| `magicNames` | `["narena"]` | この名前への接続をトンネルへ差し替える |
| `wsUrl` | `"wss://narena.dpdns.org"` | WebSocket 経由（Cloudflare Tunnel）。シグナリングとフォールバックに使う。空なら無効 |
| `signalServer` | `"n-arena.play.minekube.net"` | シグナリング・フォールバックの予備経路（直接 TCP）。ここに status ping を打つ |
| `signalPollMs` | `15000` | シグナリングのポーリング間隔（トンネル未確立時のみ） |
| `hostPlayer` | `"Ifuto_mitai"` | ゲーム内 INTRO 経路で誰とのトンネルを張るか |
| `secret` | `""`（内蔵鍵） | 共有鍵。空 = 内蔵鍵、`"none"` = 無認証、独自鍵はサーバー側と同じ値を |
| `hostMode` | `false` | （旧）クライアント自身がトンネルの受け手になる。プラグイン端末があれば不要 |
| `localTcpPort` | `25599` | ローカル受け口（Minecraft はここへ接続する） |
| `udpPort` | `0`（自動） | UDP のポート。固定したいときだけ指定 |
| `backend` | `127.0.0.1:25565` | （hostMode 時）届いた接続の転送先 |
| `stunServers` | Cloudflare / Google | 自分のアドレスを調べる STUN |
| `idleTimeoutMs` | `20000` | これ以上音信がなければトンネル断とみなす |

## 設定（サーバー `plugins/lessping-narena/config.properties`）

| キー | 既定 | 意味 |
|------|------|------|
| `host-endpoint.enabled` | `true` | ホスト端末（サーバーPC で P2P の受け口）を有効にする |
| `host-endpoint.udp-port` | `0`（自動） | 端末の UDP ポート |
| `host-endpoint.backend` | 空（この Velocity 自身） | トンネルで受けた接続の転送先 |
| `host-endpoint.secret` | 空（内蔵鍵） | 空 = 内蔵鍵、`"none"` = 無認証、独自鍵 = 本気で隠すとき（応答は AES-GCM 暗号化） |
| `host-endpoint.stun-servers` | Cloudflare / Google | 公開アドレスを調べる STUN（カンマ区切り） |
| `host-endpoint.stun-interval-ms` | `30000` | STUN 更新間隔（NAT マップの維持） |
| `host-endpoint.publish` | `true` | ping 応答に候補を載せるか（false でゲーム内 INTRO 経路のみ） |
| `host-endpoint.max-connections` | `32` | 同時に受け付けるトンネル接続の上限 |
| `ws.enabled` | `true` | WebSocket ブリッジ（Cloudflare Tunnel 用）を有効にする |
| `ws.listen` | `127.0.0.1:8081` | WebSocket ブリッジの待受アドレス。cloudflared はここを向ける |
| `ws.max-connections` | `16` | WebSocket 経由の同時接続上限 |
| `debug` | `false` | シグナリングの詳細ログ |
| `freshness-ms` | `600000` | ゲーム内 INTRO のエントリ鮮度 |

## コマンド（Velocity）

- `/lessping`（`/lp`）— ホスト端末と WebSocket ブリッジの状態（UDP ポート・公開アドレス・
  接続数・認証）と登録済みプレイヤーを表示（権限 `lessping.admin`、コンソールでも可）

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
- `relay/TunnelHost.java` — サーバーPC 側のトンネル端末（STUN・PUNCH・LPX→backend）。
  Velocity API に依存しない純粋 Java
- `tunnel/WsLink.java` / `relay/WsServer.java` — RFC 6455 を Java 標準ライブラリだけで
  実装した WebSocket クライアント/サーバー（Cloudflare Tunnel 経由の IP 非公開モード用）
