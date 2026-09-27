# LessPing-NArena

**「narena」と入力するだけで、P2P の仮想LANトンネル経由で直接接続して ping を下げる。**

Hamachi / ZeroTier / Tailscale と同じ発想。Minekube トンネルなどの**リレー経由**
（友達 → リレー → サーバーPC）ではなく、友達の MOD とサーバーPC の MOD が
**UDP ホールパンチで直接つながり**、その上に Minecraft の接続を流します。

```
今:     友達 ──▶ Minekubeリレー（遠回り）──▶ サーバーPC      ping = 経由の分だけ増える
LessPing: 友達のMOD ⇄ UDP ホールパンチで直結 ⇄ サーバーPCのMOD ──▶ localhost の Paper
                                                               ping = 物理的な最短経路
```

## 構成要素

| 成果物 | 入れる人 | 役割 |
|--------|----------|------|
| `lessping-narena-mod-<ver>.jar`（Fabric MOD） | **全員**（サーバー主も） | UDP トンネル本体。「narena」への接続をトンネルへ差し替える |
| `lessping-narena-relay-<ver>.jar`（Paper プラグイン） | **サーバー** | シグナリング中継（「誰のアドレスはこれ」の紹介だけ。ゲーム通信は経由しない） |

MC 1.21.11 / Fabric / Java 21。

## 仕組み

1. 全員がサーバーに参加（今どおりのアドレスで OK）
2. MOD が STUN で自分の UDP アドレスを調べて HELLO を中継プラグインへ送る
3. 友達の MOD は「ホスト（サーバー主）のアドレスを教えて」と要求 → プラグインが両者へ紹介（INTRO）
4. 両者が同時に UDP パンチを打ち合い、**直接トンネルが確立**（keepalive で維持）
5. 友達がマルチプレイのアドレス欄に **`narena`** と入力して接続 →
   MOD がアドレス解決を差し替え、トンネル経由でサーバーPC の Paper へ直結
   - サーバーリストの ping 表示もトンネル経由（実際の直結 RTT が表示される）

**トンネルは Minecraft の接続と独立した純 UDP** なので、サーバーを出入りしても
張り直し不要。穴あけに失敗したら（対称 NAT など）自動的に通常接続へフォールバック。

LPX の終了処理は TCP 準拠の半閉鎖: `close()` は自分の送信側だけを閉じ
（残りデータの ACK 確認後に CLOSE を送信）、相手からの CLOSE は
「もう相手からは来ない」印として全データ到着後に EOF を流す。
CLOSE フレーム自体がロスしても keepalive 間隔で再送される。

## セットアップ

### サーバー主（ホスト）

1. Paper の `plugins/` に `lessping-narena-relay-<ver>.jar` を入れる
2. 自分のクライアントにも MOD を入れ、`config/lessping/client.json` を編集:
   ```json
   {
     "enabled": true,
     "hostMode": true,
     "backend": "127.0.0.1:25565"
   }
   ```
   - `backend`: トンネルで受けた接続の転送先。Paper が同じ PC ならこのまま
   - **ホストはゲーム内にいる必要がある**（自分のサーバーに参加している間だけ紹介される）
3. サーバーに参加する（localhost 直結で OK。HELLO はそこからでも出る）

### 参加者

1. MOD を入れる（設定は初期値のまま。`hostPlayer` にホストのプレイヤー名）
   ```json
   {
     "enabled": true,
     "hostMode": false,
     "hostPlayer": "Ifuto_mitai"
   }
   ```
2. いつもどおりサーバーに参加 → ログに `[LessPing] ★ ホストへのトンネル確立` と出る
3. マルチプレイのサーバー追加でアドレスに **`narena`** と入れて接続

## なぜ速くなるのか / ならない場合

- Minekube 等のリレーが経路上にあった場合、その分の距離とホップが消える
- 同じ LAN（家の中）なら実質 LAN 接続（プライベート IP の候補も自動で試す）
- **対称 NAT 同士**だと穴あけが失敗する → その場合は通常接続に戻るだけ（壊れない）
- リレーを経由していなかった環境（もともと直結だった場合）は改善しない

## セキュリティについて（正直な話）

- トンネル自体の暗号化は v1 では**ない**（Minecraft プロトコルのオンラインモード暗号は
  エンドツーエンドでそのまま効く）
- 中継プラグインはアドレスを紹介するだけ。**ゲームトラフィックは一切流れない**
- 「narena」という名前はドメインではない（ドット無し）ので、何も入れなければ
  バニラの挙動は「接続できない」だけ。MOD を抜けば通常に戻る

## 設定（クライアント `config/lessping/client.json`）

| キー | 既定 | 意味 |
|------|------|------|
| `enabled` | `true` | 無効にすると何もしない |
| `hostMode` | `false` | サーバー主（トンネルの受け手）なら true |
| `magicNames` | `["narena"]` | この名前への接続をトンネルへ差し替える |
| `hostPlayer` | `"Ifuto_mitai"` | hostMode=false のとき、誰へのトンネルを張るか |
| `localTcpPort` | `25599` | ローカル受け口（Minecraft はここへ接続する） |
| `udpPort` | `0`（自動） | UDP のポート。固定したいときだけ指定 |
| `backend` | `127.0.0.1:25565` | hostMode のときの転送先 |
| `stunServers` | Cloudflare / Google | 自分のアドレスを調べる STUN |
| `idleTimeoutMs` | `20000` | これ以上音信がなければトンネル断とみなす |

## コマンド（サーバー）

- `/lessping`（`/lp`）— 登録済みプレイヤーと待機中の紹介要求を表示

## 開発

```
./gradlew -p lessping build
```

- `tunnel/lpx/` — 自作の reliable-UDP（LPX）。**12% パケットロス + ジッタの
  シミュレーションで双方向 2MB が完全に届くことを確認するループバックテストが
  ビルドのたびに走る**（`lpxLoopbackTest`）
- `mixin/AllowedAddressResolverMixin` — 接続とサーバーリスト ping の両方の
  アドレス解決を差し替える（kcp-mod と同じポイント）
- `relay/` — Paper プラグイン（シグナリング中継）
