# ADR-0003: Bridge IR（スキーマ 1）と静的解析・実行時レジストリ・診断 CLI

- 状態: 採用
- 日付: 2026-11-27
- 関連: #3, #1

## 背景

Epic #1 の横断ルールは「解析 → IR → 方式決定 → モード別生成 → 検証」の順で処理し、方式（AUTO / RULE / ADAPTER / UNSUPPORTED）と実測の状態（UNTESTED / SUPPORTED / PARTIAL / UNSUPPORTED / ERROR）を分けることである。#3 では、その最初の 2 段（静的解析と IR）と診断 CLI を作る。

## 決定

### IR（`dev.puicore.modbridge.core.ir.BridgeIr`、スキーマ `modbridge.bridge-ir` 版 1）

- `inputs`（ファイル名・SHA-256・サイズ）、`mods`、`definitions`、`diagnostics`、`plans` を持つ。
- **再現性**: 時刻・絶対パス・実行環境を含めない。リストはすべてソートし、マップはキー順に並べる。同じ入力からは、入力の指定順にかかわらず同じ JSON を出力する（テストで確認）。
- **識別子**: 永続識別子は `namespace:path` の文字列にする。Minecraft が起動時に振る数値 ID は IR に入れない。
- **情報源**: 各定義に `source` を持たせる。`STATIC_ASSET`（素材ファイルからの推定）と `RUNTIME_REGISTRY`（実行時レジストリにだけあるもの）を区別する。静的・実行時の両方にある定義は `STATIC_ASSET` のまま、属性 `runtime=registered` を付ける。
- **読込**: `IrJson.read` は、スキーマ名と版が一致しない IR、必須の欄が欠けた IR、型の違う IR を `IllegalArgumentException` で拒否する。スキーマ 1 は検証の記録を持たないので、状態が `SUPPORTED` / `PARTIAL` の計画を含む IR も拒否する。

### 静的解析（`SafeArchive`、`JarScanner`、`Analyzer`）

- **コードを実行しない**。JAR は展開せず、クラスも読み込まない。内容を読むのはテキスト系（JSON・lang・toml・mcmeta・properties）だけで、画像などは存在の有無だけを記録する。
- **ZIP Slip 対策**: `..`、`.`、絶対パス、ドライブ指定、バックスラッシュ、NUL を含むエントリー名があれば、その JAR 全体を `UNSAFE_PATH` で拒否する。
- **ZIP 爆弾対策**: エントリー数、JAR のサイズ、個別エントリー、合計読込量、圧縮率（1 MiB を超えてから 200 倍まで）に上限を設ける。上限の判定には、宣言サイズではなく実際に読んだバイト数を使う。1 回に解析する JAR は 4096 個までにする。
- **同梱 JAR**: 外側の `fabric.mod.json` の `jars` に書かれた同梱 JAR だけをストリームで読み、中の `fabric.mod.json` から MOD ID と `provides` だけを取り出す（`mods[].nestedMods`）。Fabric は `jars` に無い JAR を読み込まないので、それ以外は読まない。上限は次の 3 つ。読めない同梱 JAR は、JAR 全体を拒否せず `NESTED_JAR_UNREADABLE`（WARN）にする。
  - 同梱 JAR 1 個の大きさ: 圧縮サイズ × 200 + 1 MiB
  - 展開量の合計: 1 JAR あたり 128 MiB（中のエントリーを読み飛ばす分も数える）
  - 個数: 1 JAR あたり 1024 個
- **壊れたメタデータ**: `fabric.mod.json` が JSON オブジェクトでない（空ファイルを含む）と `INVALID_JSON`（ERROR）、欄の型が違うと `INVALID_MOD_METADATA`（ERROR）にする。例外で解析全体を止めない。想定外の例外が起きた JAR は `INTERNAL_ERROR`（ERROR）にして、残りの JAR の解析を続ける。
- **診断の文言**: 例外のメッセージをそのまま使わず、例外の種類名か固定の文言にする。絶対パスや OS・言語設定に依存する文言を IR に入れないため。
- **定義の推定**（`minecraft` / `c` / `fabric` 以外の名前空間が対象）:

  | 種類 | 推定元 |
  |---|---|
  | ITEM | `assets/<ns>/items/*.json`（1.21.4 以降のクライアント側アイテム定義） |
  | BLOCK | `assets/<ns>/blockstates/*.json`、`data/<ns>/loot_table/blocks/*.json` |
  | RECIPE | `data/<ns>/recipe/*.json`（`type` を属性に記録） |

  言語ファイルにだけ名前がある定義は、`MISSING_ASSET`（WARN）として記録する。
- **素材の参照**: blockstate・アイテム定義からモデルをたどり、親モデルとテクスチャまで調べる。JAR 内に無いものは `MISSING_ASSET`（WARN）とする。`minecraft` 名前空間はゲーム本体にある前提で、調べない。
- **MOD をまたぐ検査**: `DUPLICATE_MOD_ID`（ERROR）、`DUPLICATE_DEFINITION`（ERROR）、`DEPENDENCY_MISSING`（ERROR。`minecraft` / `java` / `fabricloader`、`provides`、同梱 JAR の MOD ID は満たされているとみなす）、`DEPENDENCY_CYCLE`（WARN。Fabric 自体は循環を許すため）。
- **入力の検査**: JAR が 1 つも無いと `NO_INPUT`（ERROR）、別フォルダーに同じファイル名の JAR があると `DUPLICATE_INPUT_NAME`（ERROR。IR ではファイル名しか持たず区別できないため）。

### 方式の計画（fail closed）

- 検証の記録がまだ無いので、**状態はすべて UNTESTED にする。SUPPORTED にはしない**。
- ERROR 診断が付いた MOD と、その MOD の定義は、`UNSUPPORTED` / `ERROR` にする（fail closed）。fail closed になった MOD に依存する MOD とその定義も、依存をたどって同じ扱いにし、`DEPENDENCY_FAILED`（ERROR）を出す。
- Mode A は元の MOD を Java サーバーで動かすので、MOD もアイテム・ブロックも `AUTO`（Geyser + Hydraulic）にする。
- Mode B は Java コードを実行しないので、entrypoint か Mixin を持つ MOD は `UNSUPPORTED` にする。アイテム・ブロックは `AUTO`（素材から生成）、レシピは `RULE` にする。

### CLI

| コマンド | 動作 |
|---|---|
| `scan <jar\|dir>... [--out FILE]` | IR を出力する。`--out` を付けると一時ファイル経由で原子的に置き換え、失敗しても既存のファイルを壊さない |
| `validate <jar\|dir>...` | 診断だけを表示する |
| `report <ir.json>` | IR から互換性レポート（Markdown）を表示する |

終了コードは `0`=ERROR 診断なし、`1`=ERROR 診断あり（読めない入力 JAR は診断 `IO_ERROR` としてここに含む）または不正な IR、`2`=使い方の誤り（`--out` の重複、パスに使えない文字を含む）、`3`=IR の書き出しと IR ファイルの読込の失敗。`--` の後ろは、`-` で始まる名前もファイルとして扱う。

### 依存

`core` に Gson（Apache-2.0）を追加した。JSON の読み書きを自作すると、不正入力の処理で不具合を作りやすい。Gson は Minecraft 本体も使う定番ライブラリである。

### 実行時レジストリ（`platform-fabric`、形式 `modbridge.runtime-registry` 版 1）

静的解析では、コードでだけ登録され素材を持たない定義が見つからない。そこで Mode A 用に、Fabric 専用サーバー上でレジストリを書き出す取得 MOD を作った。

- **取得 MOD**: `platform-fabric/`（独立ビルド、MOD ID `modbridge_registry_dump`、環境は `server`）。サーバーの起動完了（`SERVER_STARTED`）後に、アイテム・ブロックのレジストリとレシピの一覧を書き出す。
  - JVM 引数 `-Dmodbridge.registryDump=<file>` を付けたときだけ動く。付けなければ何もしないので、入れたままでも害は無い。
  - `-Dmodbridge.registryDump.stop=true` で、書き出した後にサーバーを止める。
  - 出力は決定的にする（ソート済み、時刻・パスなし）。`minecraft` 名前空間は含めない。読み込まれていた MOD の ID と版（同梱 JAR の MOD を含む）も記録する。
  - 書き出しに失敗してもサーバーは止めない。呼出し側はファイルが無いことで検出する（スパイクのスクリプトは失敗として扱う）。
- **読込**（`RuntimeRegistry.read`）: 64 MiB まで。スキーマ・版・各欄の型、`registry`（item / block / recipe）、識別子の形式、`mods` と `entries` の重複を検証し、不正なら `RUNTIME_REGISTRY_INVALID`（ERROR）。読めなければ `IO_ERROR`（ERROR）。ファイルは `inputs` に SHA-256 付きで記録する。
- **突き合わせ**（`RuntimeDiff`、CLI は `scan` / `validate` の `--runtime FILE`）:

  | 状況 | 結果 |
  |---|---|
  | 静的・実行時の両方にある | 属性 `runtime=registered` |
  | 実行時にだけある | `RUNTIME_REGISTRY` の定義として追加し、`CODE_ONLY_DEFINITION`（WARN） |
  | 静的にだけある | `STATIC_ONLY`（WARN。名前の違い、条件付き登録、削除された定義など） |
  | 入力した MOD が取得時に読み込まれていない | `MOD_NOT_LOADED`（WARN）。その MOD は `STATIC_ONLY` の判定から外す |
  | 入力した MOD が 1 つも取得時に読み込まれていない | `RUNTIME_REGISTRY_UNRELATED`（ERROR。突き合わせが成り立たないため） |
  | 入力と取得時で MOD の版が違う | `MOD_VERSION_MISMATCH`（ERROR。その MOD を fail closed にする） |
  | 入力していない MOD の名前空間の登録 | 名前空間ごとの件数だけを `RUNTIME_OUTSIDE_INPUT`（INFO）で示す。一部の MOD だけを解析したときに誤検知を出さないため |
  | 取得したサーバーの Minecraft の版が対象と違う | `RUNTIME_MINECRAFT_MISMATCH`（ERROR。別の版のデータとは結果を信用できないため） |

  対象にする名前空間は、入力した MOD の ID と、入力した MOD の定義が使う名前空間だけ。MOD と Minecraft の版は文字列の完全一致で比べる（`26.3.1` のようなパッチ版や、`version` が無い MOD も不一致として fail closed にする）。
- **方式の計画**: 実行時にだけあるアイテム・ブロックは、Mode A を `ADAPTER`（Geyser は登録するが見た目は変換されないので、対応付けが要る）、Mode B を `UNSUPPORTED`（生成元の素材が無い）にする。実行時にだけあるレシピは、Mode A を `AUTO`（サーバーが処理する）にする。状態は UNTESTED のまま。

## 実測（2026-11-27）

- 単体テスト 42 件が成功（`./gradlew build` の集計）。対象は壊れた JAR、ZIP Slip、ZIP 爆弾（圧縮率・上限）、重複 MOD ID、重複定義、依存の欠落と循環とその伝播、`provides`、同梱 JAR の MOD ID と展開量の上限、実行時レジストリとの突き合わせ（実行時のみ・静的のみ・未読込の MOD・無関係なレジストリ・MOD と Minecraft の版の違い・重複・不正な形式）、素材の欠落、NeoForge・メタデータなし、オブジェクトでない・型の違う `fabric.mod.json`、JAR が 0 件、同名の入力、診断に絶対パスを含まないこと、不正な IR の拒否、IR の往復、CLI の終了コード、原子的書き出しの成功と失敗。
- 実際の fixture JAR（`pmb-fabric-fixture-0.0.1.jar`）から、ITEM 2、BLOCK 1 を推定した。fixture を単体で指定すると、`fabric-api` が入力に無いため `DEPENDENCY_MISSING` になり、fail closed になる。これは想定どおりの動作である。
- スパイク環境の `mods/` 一式（Fabric API、Geyser、Hydraulic、fixture）では、ERROR 0 件・WARN 0 件で解析できた。Fabric API は同梱 JAR 44 個から MOD ID 45 個（`fabric-api-base` などのモジュール）を取り出した。
- 実行時レジストリ: fixture に素材を持たないアイテム `pmb_fixture:code_only_marker` を足し、スパイクのサーバーで取得した。
  - 書き出しは Geyser の起動完了の直後で、ファイルは 9 KB（MOD 102 件、`pmb_fixture` のアイテム 3・ブロック 1）。
  - `modbridge validate mods/ --runtime registry-dump.json` は ERROR 0 / WARN 1。WARN は `CODE_ONLY_DEFINITION pmb_fixture:code_only_marker` だけで、残りの 3 定義は両方で一致した。
  - Geyser はカスタムアイテムを 3 個登録した（`Registered 3 custom items`）。一方で、サーバーログには `Failed to find path for item pmb_fixture:code_only_marker, skipping`（WARN）が出て、Hydraulic の変換対象は 2 個だった（`Items to convert: 2`）。素材の無い定義は、Mode A でも見た目が変換されない。Bedrock 側でどう見えるかは未検証。
- 中身が 190 MB のゼロの同梱 JAR を 20 個持つ JAR（3.7 MB）は、修正前は警告なしで 4.7 秒かかった。修正後は 0.64 秒で打ち切り、20 個とも `NESTED_JAR_UNREADABLE` になった。

## 未実装・既知の制約

- 実行時レジストリは、Fabric の Mode A 用だけ。取得にはサーバーの起動が要るので、CI ではビルドだけを確認する。サーバーの起動には Minecraft EULA への同意が要り、その判断は owner にあるため、CI では起動しない。
- 実行時レジストリはアイテム・ブロック・レシピだけ。エンティティ・タグ・バイオームなどは対象外。
- 名前空間が MOD ID と違い、素材も持たない定義は、突き合わせの対象にならない（`RUNTIME_OUTSIDE_INPUT` の件数にだけ現れる）。
- 同梱 JAR（jar-in-jar）は、MOD ID と `provides` だけを読む。中の素材・定義は解析せず、同梱 JAR の中にさらに同梱された JAR も読まない。
- バージョン範囲（`depends` の値）は評価せず、宣言のまま記録する。
- タグ、ルートテーブルの中身、エンティティは未対応（エンティティは v0.5.0）。
