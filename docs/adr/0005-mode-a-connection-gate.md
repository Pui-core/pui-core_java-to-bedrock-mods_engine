# ADR-0005: Mode A の起動時の互換性検査

- 状態: 採用
- 日付: 2026-11-27
- 関連: #5, #1、前提は ADR-0003

## 背景

Mode A では、Java サーバーがゲームの状態を持ち、Geyser が Bedrock の接続を中継する。Bedrock が扱えない MOD があると、ブロックが見えない、アイテムが操作できないなどの問題が起きる。#5 では、重大な非対応を検出した時点でサーバー全体の起動を中断し、既存ワールドとプレイヤーデータを保つ。

## 決定

### 判定（`core` の `ConnectionGate`）

- 入力は、サーバーの `mods/` を静的解析し、登録済みのブロック・アイテムと読み込まれたレシピを突き合わせた IR（ADR-0003）。
- **重大な非対応**は、ERROR 診断と、Mode A の計画が ERROR または UNSUPPORTED のもの。さらに、読み込まれているのに `mods/` の直下に無い MOD（JVM 引数で足したもの、サブフォルダーのものなど。同梱 JAR の MOD と Minecraft 本体・Java・Fabric Loader は除く）も `MOD_NOT_ANALYZED` として理由にする。解析していない MOD を見逃さないため。見た目の対応付けが要るもの（ADAPTER、たとえば素材の無いアイテム）は理由にしない。見た目が欠けるだけで、ワールドは壊さないため。
- 方針は 3 つ。未知の値は STRICT にする（安全側）。

  | 方針 | 重大な非対応があるとき |
  |---|---|
  | `strict`（既定） | サーバーの起動を中断する |
  | `permissive` | 受け付けて、理由をログに残す |
  | `off` | 判定しない（解析もしない） |

- `strict` は構成を判定できなかった場合も起動を中断する。`permissive` で解析中または解析に失敗した場合は、Bedrock の接続を保留する。

### Fabric 側（`platform-fabric`、MOD ID `modbridge`）

- 実行時レジストリの取得 MOD（ADR-0003）に接続ゲートを足し、MOD ID を `modbridge_registry_dump` から `modbridge` に、成果物名を `modbridge-fabric` に変えた。
- `strict` は専用サーバーの `Main` でデータパックの読み込み直後に判定する。この時点で Geyser API とレシピを参照できる。Minecraft 26.3 は `SERVER_STARTING` より先に `level.dat` を保存するため、専用サーバー用 Mixin で保存より前に検査する。検査で止めたときはデータパックとワールドロックを解放し、理由をログに出してプロセスを終了コード 1 で止める。
- `permissive` は `SERVER_STARTING` で Geyser に登録し、`SERVER_STARTED` から別スレッドで解析する。Geyser の `SessionLoginEvent` に `PostOrder.LAST` で登録し、判定が準備中なら取り消す。
- `-Dmodbridge.registryDump=<file>` のダンプは `SERVER_STARTED` で生成する。
- **記録**: 判定の根拠の IR を `config/modbridge/gate-report.json` に書き、判定と理由（最大 20 件）をサーバーログに出す。接続を断ったプレイヤーの名前は記録しない。
- **Geyser が無いとき**: ゲートは使わない（Bedrock の接続自体が無い）。
- **Geyser があるのに登録できないとき**（API が無い、版違いによる `LinkageError` など。例外はすべて捕まえる）:
  - `strict` では、ワールドの保存と接続待受より前に例外を投げて、サーバーの起動を中断する。
  - 試験用に、`-Dmodbridge.gate.testFailRegistration=true` で登録の失敗を起こせる。
  - `permissive` では、ERROR をログに出して起動を続ける。この場合、Bedrock の接続は制限されない。
- **core の取り込み**: `platform-fabric` の settings で `includeBuild('..')` し、core を JAR に同梱する（Loom の `include`）。Gson は Minecraft 本体のものを使うので同梱しない。
- **Geyser の API**: Maven には SNAPSHOT 版しか無く、版を固定できない。そこで、版と SHA-256 を固定した Geyser-Fabric 2.12.1 build 1312 をビルド時に取得する（spike と同じ値）。その JAR と、同梱の `events-1.1-SNAPSHOT.jar` と `base-api-1.0.3.jar` を取り出し、コンパイル時だけ参照する。SHA-256 が合わなければビルドを止める。

## 旧方式の実測（2026-11-27）

- **通常の構成**（Fabric API、Geyser、Hydraulic、fixture、modbridge）:
  - ログには次の順で出た: `gate policy=STRICT geyser=true` → `Geyser の SessionLoginEvent に登録した` → 実行時レジストリの書き出し → `gate decision: allowBedrock=true policy=STRICT critical=0`。
  - 書き出しから判定までは 1 秒未満だった。
- **メタデータの無い JAR を `mods/` に足した構成**では、`MISSING_MOD_METADATA zz-not-a-mod.jar` を検出し、起動後に Bedrock の接続を断った。
- **`mods/` の外から MOD を足した構成**（`-Dfabric.addMods` で、メタデータだけの MOD `outside_probe` を足した）:
  - `allowBedrock=false critical=1` と、理由 `MOD_NOT_ANALYZED outside_probe` が出た。
  - この検査を最初に入れたときは、Minecraft 本体・Java・Fabric Loader も `mods/` の外にあるとして断る誤判定が出た。そこで、これらを除くようにした（Fabric Loader の種類は `builtin` ではないため、ID でも除く）。直した後の通常の構成では、`allowBedrock=true critical=0` に戻った。
- **登録の失敗**（`-Dmodbridge.gate.testFailRegistration=true`、strict）: `SERVER_STARTING` で起動が中断され、クラッシュレポートを残して停止した。Geyser の読み込みと待受のログは 1 行も出なかった。ゲートが無いまま Bedrock のポートが開く隙は無かった。
- 登録は `PostOrder.LAST` で行われた（ログ `Geyser の SessionLoginEvent に登録した（PostOrder.LAST）`）。
- 単体テスト（`ConnectionGateTest`）: 方針ごとの判定、ADAPTER と Mode B の非対応を理由にしないこと、準備中は断ること、未知の方針を STRICT にすること、解析の対象外の MOD を理由にすること、理由の件数の上限。

## 起動中断の実測（2026-11-28）

Fabric 26.3 と Geyser 2.12.1-b1312 を使い、Windows のループバック専用の検証環境で試した。正常構成では `allowBedrock=true` の判定後に Minecraft と Geyser が待受を開始した。メタデータのない検証用 JAR を置くと、`MISSING_MOD_METADATA` と起動中断の理由を記録して終了コード 1 で停止した。Minecraft の TCP と Bedrock の RakNet に応答はなく、起動完了のログもなかった。ワールドのロックファイルを除く30ファイルの SHA-256 は一致した。照合対象にはワールドの保存データと、同時試験からコピーした2人分のプレイヤーデータを含む。

検証用 JAR を外すと `strict` で再び起動し、同じ座標の試験用ブロックが保持された。検証用 JAR を置いたままの `permissive` は理由を記録して起動し、`off` は解析を省いて起動した。Geyser の登録失敗を試験用 JVM 引数で起こした場合も、`strict` では待受を始めず終了コード 1 で止まった。

## 未検証・既知の制約

- `mods/` だけを解析する。起動スクリプトなどで別のフォルダーから MOD を読み込む構成には対応しない。
- STRICT では、MOD ではない JAR（ライブラリなど）を `mods/` に置いても起動を中断する。
- 判定はサーバーの起動時に 1 回だけ行う。起動中に MOD を差し替える運用には対応しない。
- `mods/` の直下かどうかは、パスを `normalize` して比べる。`mods/` がシンボリックリンクやバインドマウントで、Fabric が実パスを返すと、置いた MOD が `MOD_NOT_ANALYZED` になり、strict では起動を中断する。
- ファイルから読み込まれていない MOD（origin が PATH 以外）は、`MOD_NOT_ANALYZED` の検査から外れる。どの種類の MOD がこれに当たるかは未確認。
- core は Gson 2.15 向けにビルドし、実行時は Minecraft 同梱の Gson（26.3 では 2.14.0）を使う。互換性の問題で解析できない場合も、strict は起動を中断する。

## v0.1.0 の認証・実機検証

スクリプトの `--floodgate` は Minecraft 26.3 対応の Floodgate Fabric 2.2.7-b69 を SHA-256 で固定して読み込む。Java 側は `online-mode=true` とし、Bedrock 側は Geyser の `auth-type: floodgate`、Bedrock ログイン検証あり、リソースパック必須で起動する。ワールドとプレイヤーデータを認証方式ごとに保つため、従来のスパイクとは別の作業フォルダーを使う。Floodgate の鍵と生ログは共有しない。 [Floodgate の Fabric 設定](https://geysermc.org/wiki/floodgate/setup/modded/) · [固定した配布版](https://modrinth.com/mod/floodgate/version/m9eO07Zi)

### Mode A の実機確認（#5）

1. [README の認証付きスパイク](../../README.md#v010-の認証実機検証)を `--keep-running` で起動する。サーバーログで `gate decision: allowBedrock=true`、Geyser の起動完了、Hydraulic による fixture のパック登録を確認する。
   ホワイトリストを使う場合は、サーバーコンソールの `fwhitelist add <ゲーマータグ>` で Bedrock の参加者を登録する。 [Floodgate のホワイトリスト](https://geysermc.org/wiki/floodgate/features/#whitelist-command)
2. 同じ LAN で、別々の Microsoft アカウントにサインインした無改造の Bedrock 版 2 台をサーバーの LAN アドレスとポート `19132` に接続する。リソースパックが読み込まれ、両方が入場することを確認する。パック拒否時の入場制御は後述のプロトコル試験で確認する。
3. コンソールの `list` で名前を確認し、Bedrock プレイヤーに `give <プレイヤー名> pmb_fixture:test_ingot 16`、`give <プレイヤー名> pmb_fixture:test_block 2`、`give <プレイヤー名> minecraft:iron_pickaxe` を送る。両方のクライアントでインゴットの外観・名称・所持数を確認する。一方がサバイバルでブロックを設置してツルハシで壊し、両方の画面で同じ座標の変化を確認する。もう一方がドロップを拾い、所持数が増えることを確認する。壊した側ではツルハシの耐久を確認する。再起動試験に使う検証用ブロックは設置したままにする。
4. Bedrock 版を切断して接続し直す。さらにコンソールで `stop` を実行し、同じ作業フォルダーで起動する。残したブロック、アイテム数、ツルハシの耐久が維持されることを確認する。
5. 別の検証環境で、ワールドと Bedrock 2 人のプレイヤーデータ（Java 26.3 では `players/data/` にある各 UUID の `.dat`）の SHA-256 を控える。既定の `strict` で `mods/` に MOD メタデータを持たない検証用 JAR を加えて起動し、`MISSING_MOD_METADATA` と起動中断を確認する。サーバーがポートを開かず、ロックファイルを除く保存済みワールドとプレイヤーデータが変化しないことを確認する。検証用 JAR を外し、正常に起動することを確認する。

証跡にはクライアントのバージョン、各操作の成否、拒否時の表示、再起動前後の座標と所持数、該当するサーバーログを残す。

### 実機試験の結果（2026-11-27〜28）

Windows 上の隔離ワールドで Java 26.3 サーバー、Fabric Loader 0.20.1、Geyser 2.12.1-b1312、Hydraulic 1.0.0-b132、Floodgate Fabric 2.2.7-b69、fixture 0.0.3 を使用した。Bedrock の待受は検証用の Tailscale アダプターに限定した。初回の Bedrock クライアント版は未記録。PC での再試験には、無改造の Windows 版 Bedrock 26.65 を使用した。

| 項目 | 結果 |
| --- | --- |
| サーバー起動・登録 | Floodgate の起動、Hydraulic による fixture パックの変換、接続ゲートの `allowBedrock=true policy=STRICT critical=0` をログで確認 |
| 認証・再接続 | Tailscale 経由の NetherNet 接続と Floodgate 認証を確認。初回は空のホワイトリストで拒否された。登録後に入場し、タイムアウト後は同じアカウントで Windows 版 Bedrock から再接続できた |
| 外観・取得・設置 | Bedrock で黄色のインゴットと青・橙のブロックの外観を確認。付与した試験用インゴットは16個、ブロックは2個、鉄のツルハシは1個。ブロックの設置はサーバーの保存統計で2回だった |
| 破壊・回収・耐久 | Windows 版 Bedrock で設置済みの2個を鉄のツルハシで壊し、ドロップ2個を回収した。ブロックの `mined` と `picked_up` はそれぞれ2、ツルハシの `minecraft:damage` は2だった |
| サーバー再起動 | PC での初回試験ではインゴット16個、ブロック2個、ツルハシの `minecraft:damage=2` が維持された。2人での試験でも、新しい起動検査を入れたサーバーへ両方が再入場し、インゴットは10個と6個、ブロックは各1個、再起動前に使っていたツルハシの `minecraft:damage=4` が維持された。ワールドに残したコンソール設置の検証用ブロックは同じ座標 `(0, 100, 0)` で確認した |
| Bedrock 2人の同時試験 | プロトコル 2225 の別アカウントで同時入場。2人のインベントリに試験用ブロックとインゴットを確認。サーバー統計で1人目の設置・破壊と2人目の回収を確認した。ブロックの設置・破壊がもう一方の画面に反映されることを利用者が確認した |
| リソースパック | `force-resource-packs: true` の構成で、新しい ID の検証用リソースパックを追加して接続した。Windows 版 Bedrock ではパックが自動で読み込まれ、拒否を選択できる画面は出なかった |

### 必須リソースパックの拒否（2026-11-28）

同じバージョンの Geyser、Hydraulic、fixture を使う別のワールドを Windows のループバックに限定して起動した。この試験環境では合成プレイヤーで接続するためログイン認証を無効にし、`force-resource-packs: true` にした。

`bedrock-protocol@3.61.0` の検証用クライアント（Bedrock プロトコル 2225、RakNet）で、サーバーが `must_accept=true` でパック4個を案内することを確認した。クライアントが `ResourcePackClientResponse` の `REFUSED`（`cancel`）を返すと、サーバーは `disconnectionScreen.resourcePack` を返して切断した。同じ試験を2回実施し、いずれも `StartGame` は受信せず、サーバーの `list` は0人、新規プレイヤーの保存ファイルは0件だった。 [Geyser build 1312 のリソースパック処理](https://github.com/GeyserMC/Geyser/blob/0c326c3/core/src/main/java/org/geysermc/geyser/network/bedrock/UpstreamPacketHandler.java#L88-L172)

### 共通素材コンパイラの表示確認（#4）

上記の Mode A は Hydraulic の出力を使う。共通素材コンパイラの検証では `modbridge compile` が生成した `.mcaddon` を Bedrock の使い捨てローカルワールドに読み込み、RP と BP の両方を適用する。生成された識別子は `mapping.json` を参照し、テクスチャ・面の向き・透過・名称と、変換対象外の項目の診断を [ADR-0004](0004-asset-compiler-and-mapping.md) の対応範囲に照らして記録する。単純な fixture の外観の確認に加え、透過や面ごとの向きについてはそれぞれを判別できる素材で確認する。
