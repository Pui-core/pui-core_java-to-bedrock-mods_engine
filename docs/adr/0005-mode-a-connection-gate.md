# ADR-0005: Mode A の接続ゲート

- 状態: 採用（Bedrock 実機で断られることは未検証）
- 日付: 2026-11-27
- 関連: #5, #1、前提は ADR-0003

## 背景

#5 の受け入れ条件に「重大未対応時は Bedrock 接続拒否、ワールドを変更しない」がある。Mode A では、Java サーバーがゲームの状態を持ち、Geyser が Bedrock の接続を中継する。MOD の構成に Bedrock で扱えないものがある状態で Bedrock のプレイヤーが入ると、見えないブロックや操作できないアイテムが原因で、事故が起きうる。そこで、サーバー側で構成を判定し、重大な非対応があれば Bedrock の接続だけを断る。

## 決定

### 判定（`core` の `ConnectionGate`）

- 入力は、サーバーの `mods/` を静的解析し、起動後の実行時レジストリと突き合わせた IR（ADR-0003）。
- **重大な非対応**は、ERROR 診断と、Mode A の計画が ERROR または UNSUPPORTED のもの。見た目の対応付けが要るもの（ADAPTER、たとえば素材の無いアイテム）は理由にしない。見た目が欠けるだけで、ワールドは壊さないため。
- 方針は 3 つ。未知の値は STRICT にする（安全側）。

  | 方針 | 重大な非対応があるとき |
  |---|---|
  | `strict`（既定） | Bedrock の接続を断る |
  | `permissive` | 受け付けて、理由をログに残す |
  | `off` | 判定しない（解析もしない） |

- 判定が出るまで（起動中、解析中）は、`off` 以外では Bedrock の接続を断る。解析に失敗したときも断ったままにする。STRICT で実行時レジストリを取れなかったときも断る（構成を確かめられないため）。

### Fabric 側（`platform-fabric`、MOD ID `modbridge`）

- 実行時レジストリの取得 MOD（ADR-0003）に接続ゲートを足し、MOD ID を `modbridge_registry_dump` から `modbridge` に、成果物名を `modbridge-fabric` に変えた。
- **時期**:
  1. MOD の初期化で方針を読み、判定を「準備中」にする。
  2. `SERVER_STARTING` で、Geyser の `SessionLoginEvent` に登録する。Geyser の API は、Geyser の MOD の初期化で用意されるため、この時期なら使える。
  3. `SERVER_STARTED` で実行時レジストリを集め、別スレッドで `mods/` を解析して判定を出す。サーバーのスレッドは止めない。
- **断り方**: `SessionLoginEvent` を `setCancelled(true, 理由)` で取り消す。このイベントは、Bedrock のプレイヤーが Java サーバーに入る直前に呼ばれるので、断ったプレイヤーはワールドに入らない。ワールドは変更されない。
- **記録**: 判定の根拠の IR を `config/modbridge/gate-report.json` に書き、判定と理由（最大 20 件）をサーバーログに出す。接続を断ったプレイヤーの名前は記録しない。
- **Geyser が無いとき**: ゲートは使わない（Bedrock の接続自体が無い）。Geyser があるのに API を取れないときは、ERROR をログに出す。この場合、Bedrock の接続は制限されない。
- **core の取り込み**: `platform-fabric` の settings で `includeBuild('..')` し、core を JAR に同梱する（Loom の `include`）。Gson は Minecraft 本体のものを使うので同梱しない。
- **Geyser の API**: Maven には SNAPSHOT 版しか無く、版を固定できない。そこで、版と SHA-256 を固定した Geyser-Fabric 2.12.1 build 1312 をビルド時に取得する（spike と同じ値）。その JAR と、同梱の `events-1.1-SNAPSHOT.jar` と `base-api-1.0.3.jar` を取り出し、コンパイル時だけ参照する。SHA-256 が合わなければビルドを止める。

## 実測（2026-11-27、ローカルのスパイクサーバー）

- **通常の構成**（Fabric API、Geyser、Hydraulic、fixture、modbridge）:
  - ログには次の順で出た: `gate policy=STRICT geyser=true` → `Geyser の SessionLoginEvent に登録した` → 実行時レジストリの書き出し → `gate decision: allowBedrock=true policy=STRICT critical=0`。
  - 書き出しから判定までは 1 秒未満だった。
- **メタデータの無い JAR を `mods/` に足した構成**:
  - `gate decision: allowBedrock=false policy=STRICT critical=1` と、理由 `MISSING_MOD_METADATA zz-not-a-mod.jar` が出た。
  - Java サーバーは通常どおり起動し、停止した。
- 単体テスト（`ConnectionGateTest`）: 方針ごとの判定、ADAPTER と Mode B の非対応を理由にしないこと、準備中は断ること、未知の方針を STRICT にすること、理由の件数の上限。

## 未検証・既知の制約

- **Bedrock 実機で断られること**と、断られたときにクライアントに出る文言は未確認（owner の実機確認の項目。手順は ADR-0002 の接続手順に、`mods/` にメタデータの無い JAR を足す手順を加える）。
- `mods/` だけを解析する。起動スクリプトなどで別のフォルダーから MOD を読み込む構成には対応しない。
- STRICT では、MOD ではない JAR（ライブラリなど）が `mods/` にあるだけで断る。そういう構成では `permissive` にする。
- 判定はサーバーの起動時に 1 回だけ行う。起動中に MOD を差し替える運用には対応しない。
- #5 の残り（Floodgate の認証、アイテム・ブロックの操作の同期、複数人での実機確認）は別の PR で扱う。
