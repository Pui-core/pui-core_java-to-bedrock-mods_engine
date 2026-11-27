# ADR-0005: Mode A の接続ゲート

- 状態: 採用（Bedrock 実機で断られることは未検証）
- 日付: 2026-11-27
- 関連: #5, #1、前提は ADR-0003

## 背景

#5 の受け入れ条件に「重大未対応時は Bedrock 接続拒否、ワールドを変更しない」がある。Mode A では、Java サーバーがゲームの状態を持ち、Geyser が Bedrock の接続を中継する。MOD の構成に Bedrock で扱えないものがある状態で Bedrock のプレイヤーが入ると、見えないブロックや操作できないアイテムが原因で、事故が起きうる。そこで、サーバー側で構成を判定し、重大な非対応があれば Bedrock の接続だけを断る。

## 決定

### 判定（`core` の `ConnectionGate`）

- 入力は、サーバーの `mods/` を静的解析し、起動後の実行時レジストリと突き合わせた IR（ADR-0003）。
- **重大な非対応**は、ERROR 診断と、Mode A の計画が ERROR または UNSUPPORTED のもの。さらに、読み込まれているのに `mods/` の直下に無い MOD（JVM 引数で足したもの、サブフォルダーのものなど。同梱 JAR の MOD と Minecraft 本体・Java・Fabric Loader は除く）も `MOD_NOT_ANALYZED` として理由にする。解析していない MOD を見逃さないため。見た目の対応付けが要るもの（ADAPTER、たとえば素材の無いアイテム）は理由にしない。見た目が欠けるだけで、ワールドは壊さないため。
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
- **断り方**: `SessionLoginEvent` を `setCancelled(true, 理由)` で取り消す。ほかの拡張が後から取り消しを外さないよう、`PostOrder.LAST` で登録する。このイベントは、Bedrock のプレイヤーが Java サーバーに入る直前に呼ばれるので、断ったプレイヤーはワールドに入らない。ワールドは変更されない。
- **記録**: 判定の根拠の IR を `config/modbridge/gate-report.json` に書き、判定と理由（最大 20 件）をサーバーログに出す。接続を断ったプレイヤーの名前は記録しない。
- **Geyser が無いとき**: ゲートは使わない（Bedrock の接続自体が無い）。
- **Geyser があるのに登録できないとき**（API が無い、版違いによる `LinkageError` など。例外はすべて捕まえる）:
  - `strict` では、`SERVER_STARTING` から例外を投げて、サーバーの起動を中断する。断れないまま Bedrock を受け付けることになるため（fail closed）。`server.halt` では、止まるまでに Geyser の待受（`SERVER_STARTED`）が始まりうるので使わない。止めずに起動するには、方針を `permissive` か `off` にする。
  - 試験用に、`-Dmodbridge.gate.testFailRegistration=true` で登録の失敗を起こせる。
  - `permissive` では、ERROR をログに出して起動を続ける。この場合、Bedrock の接続は制限されない。
- **解析の失敗**: `Error` を含むすべての例外を捕まえ、判定は準備中のまま（Bedrock を断ったまま）にし、その旨をログに出す。
- **core の取り込み**: `platform-fabric` の settings で `includeBuild('..')` し、core を JAR に同梱する（Loom の `include`）。Gson は Minecraft 本体のものを使うので同梱しない。
- **Geyser の API**: Maven には SNAPSHOT 版しか無く、版を固定できない。そこで、版と SHA-256 を固定した Geyser-Fabric 2.12.1 build 1312 をビルド時に取得する（spike と同じ値）。その JAR と、同梱の `events-1.1-SNAPSHOT.jar` と `base-api-1.0.3.jar` を取り出し、コンパイル時だけ参照する。SHA-256 が合わなければビルドを止める。

## 実測（2026-11-27、ローカルのスパイクサーバー）

- **通常の構成**（Fabric API、Geyser、Hydraulic、fixture、modbridge）:
  - ログには次の順で出た: `gate policy=STRICT geyser=true` → `Geyser の SessionLoginEvent に登録した` → 実行時レジストリの書き出し → `gate decision: allowBedrock=true policy=STRICT critical=0`。
  - 書き出しから判定までは 1 秒未満だった。
- **メタデータの無い JAR を `mods/` に足した構成**:
  - `gate decision: allowBedrock=false policy=STRICT critical=1` と、理由 `MISSING_MOD_METADATA zz-not-a-mod.jar` が出た。
  - Java サーバーは通常どおり起動し、停止した。
- **`mods/` の外から MOD を足した構成**（`-Dfabric.addMods` で、メタデータだけの MOD `outside_probe` を足した）:
  - `allowBedrock=false critical=1` と、理由 `MOD_NOT_ANALYZED outside_probe` が出た。
  - この検査を最初に入れたときは、Minecraft 本体・Java・Fabric Loader も `mods/` の外にあるとして断る誤判定が出た。そこで、これらを除くようにした（Fabric Loader の種類は `builtin` ではないため、ID でも除く）。直した後の通常の構成では、`allowBedrock=true critical=0` に戻った。
- **登録の失敗**（`-Dmodbridge.gate.testFailRegistration=true`、strict）: `SERVER_STARTING` で起動が中断され、クラッシュレポートを残して停止した。Geyser の読み込みと待受のログは 1 行も出なかった。ゲートが無いまま Bedrock のポートが開く隙は無かった。
- 登録は `PostOrder.LAST` で行われた（ログ `Geyser の SessionLoginEvent に登録した（PostOrder.LAST）`）。
- 単体テスト（`ConnectionGateTest`）: 方針ごとの判定、ADAPTER と Mode B の非対応を理由にしないこと、準備中は断ること、未知の方針を STRICT にすること、解析の対象外の MOD を理由にすること、理由の件数の上限。

## 未検証・既知の制約

- **Bedrock 実機で断られること**と、断られたときにクライアントに出る文言は未確認（owner の実機確認の項目。手順は ADR-0002 の接続手順に、`mods/` にメタデータの無い JAR を足す手順を加える）。
- `mods/` だけを解析する。起動スクリプトなどで別のフォルダーから MOD を読み込む構成には対応しない。
- STRICT では、MOD ではない JAR（ライブラリなど）が `mods/` にあるだけで断る。そういう構成では `permissive` にする。
- 判定はサーバーの起動時に 1 回だけ行う。起動中に MOD を差し替える運用には対応しない。
- `mods/` の直下かどうかは、パスを `normalize` して比べる（実パスにはしない）。`mods/` がシンボリックリンクやバインドマウントで、Fabric が実パスを返す環境では、すべての MOD が `MOD_NOT_ANALYZED` になり、strict では Bedrock を断る（閉じる側の誤判定）。
- ファイルから読み込まれていない MOD（origin が PATH 以外）は、`MOD_NOT_ANALYZED` の検査から外れる。どの種類の MOD がこれに当たるかは未確認。
- STRICT で実行時レジストリを取れなかったときは、理由が `RUNTIME_REGISTRY_UNAVAILABLE` の 1 件だけになり、ほかの理由は出ない（判定は同じく断る）。
- `/geyser reload` の後も登録が残るかは未確認。残らなければ、その後は Bedrock を制限しない（実機の確認項目に加える）。
- core は Gson 2.15 向けにビルドし、実行時は Minecraft 同梱の Gson（26.3 では 2.14.0）を使う。版の違いで解析が失敗したときは、上のとおり Bedrock を断ったままにする。

## v0.1.0 の認証・実機検証

スクリプトの `--floodgate` は Minecraft 26.3 対応の Floodgate Fabric 2.2.7-b69 を SHA-256 で固定して読み込む。Java 側は `online-mode=true` とし、Bedrock 側は Geyser の `auth-type: floodgate`、Bedrock ログイン検証あり、リソースパック必須で起動する。ワールドとプレイヤーデータを認証方式ごとに保つため、従来のスパイクとは別の作業フォルダーを使う。Floodgate の鍵と生ログは共有しない。 [Floodgate の Fabric 設定](https://geysermc.org/wiki/floodgate/setup/modded/) · [固定した配布版](https://modrinth.com/mod/floodgate/version/m9eO07Zi)

### Mode A の実機確認（#5）

1. [README の認証付きスパイク](../../README.md#v010-の認証実機検証)を `--keep-running` で起動する。サーバーログで `gate decision: allowBedrock=true`、Geyser の起動完了、Hydraulic による fixture のパック登録を確認する。
2. 同じ LAN で、別々の Microsoft アカウントにサインインした無改造の Bedrock 版 2 台をサーバーの LAN アドレスとポート `19132` に接続する。パックを受け入れると接続でき、拒否するとワールドに入れないことを確認する。サーバーと同じ PC からは、fixture を入れた同じ版の Fabric クライアントで `127.0.0.1:25565` に接続する。
3. コンソールの `list` で名前を確認し、Bedrock 版の各プレイヤーに `give <プレイヤー名> pmb_fixture:test_ingot 16`、`give <プレイヤー名> pmb_fixture:test_block 2`、`give <プレイヤー名> minecraft:iron_pickaxe` を送る。両方のクライアントでインゴットの外観・名称・所持数を確認する。各プレイヤーがサバイバルでブロックを 2 個置き、片方をツルハシで壊す。他方の Bedrock と Java 版からも同じ座標の変化を確認する。各プレイヤーについて、ドロップが 1 個だけ発生し、拾うと所持数が 1 増え、ツルハシの耐久が 1 減ることを確認する。もう片方のブロックは設置したままにする。
4. Bedrock 版を切断して接続し直す。さらにコンソールで `stop` を実行し、同じ作業フォルダーで起動する。残したブロック、アイテム数、ツルハシの耐久が維持されることを確認する。
5. サーバーを止め、Bedrock 2 人のプレイヤーデータファイル（`spike-world/playerdata/` にある各 UUID の `.dat`）の SHA-256 を控える。`mods/` に MOD メタデータを持たない検証用 JAR を追加して再起動し、`allowBedrock=false` と `MISSING_MOD_METADATA` を確認する。Bedrock の両方で入場前に拒否され、Java では接続できることを確かめる。Java 版またはコンソールから、手順 3 で残したブロックを同じ座標で確認する。`geyser reload` 後にも Bedrock の接続を試す。停止後に、Bedrock のプレイヤーデータの SHA-256 が同じであることを確認し、検証用 JAR を外して元の構成に戻す。

証跡にはクライアントのバージョン、各操作の成否、拒否時の表示、再起動前後の座標と所持数、該当するサーバーログを残す。実機の結果が出るまでは #5 の受け入れ条件を未達として扱う。

### 共通素材コンパイラの表示確認（#4）

上記の Mode A は Hydraulic の出力を使う。共通素材コンパイラの検証では `modbridge compile` が生成した `.mcaddon` を Bedrock の使い捨てローカルワールドに読み込み、RP と BP の両方を適用する。生成された識別子は `mapping.json` を参照し、テクスチャ・面の向き・透過・名称と、変換対象外の項目の診断を [ADR-0004](0004-asset-compiler-and-mapping.md) の対応範囲に照らして記録する。単純な fixture の外観の確認に加え、透過や面ごとの向きについてはそれぞれを判別できる素材で確認する。
