# ADR-0006: Mode B のビヘイビアパック生成と .mcaddon

- 状態: 採用（Windows 版 26.65 のローカルワールドで素材・レシピ・加工を確認。公式 BDS 1.26.66.1 の Windows と WSL Ubuntu でパックの読込・更新・復旧を確認）
- 日付: 2026-11-27
- 関連: #6, #4, #1、前提は ADR-0004

## 背景

Mode B では、Java の MOD のコードを BDS で動かさず、宣言データ（素材・レシピ）を Bedrock のアドオンに変換する。ADR-0004 でリソースパック（見た目）を作れるようになったが、アイテム・ブロック・レシピの定義（ビヘイビアパック）が無いと、Bedrock では表示も使用もできない。#6 では、ビヘイビアパックを作り、2 つのパックを `.mcaddon` にまとめる。BDS への配置（dry-run・バックアップ・apply・rollback）は、別の PR で扱う。

## 決定

### 作るもの（`modbridge compile` の出力）

| ファイル | 中身 |
|---|---|
| `modbridge-rp.mcpack` | リソースパック（ADR-0004） |
| `modbridge-bp.mcpack` | ビヘイビアパック（このADR） |
| `modbridge.mcaddon` | 上の 2 つの `.mcpack` をまとめた ZIP（入れ子の `.mcpack` を Bedrock が開けるかは未検証。開けなければフォルダー形式に変える） |
| `mapping.json` | 対応表。ビヘイビアパックの変換結果（`behavior`）と、変換しない Java のロジック（`notConverted`）を足した |

### 定義の変換（`BehaviorPack`）

- 書式の版は `1.21.80`。1.21.80 以降、ブロックは `minecraft:geometry` と `minecraft:material_instances` を両方書く必要があるため。これに合わせて、両パックの `min_engine_version` を `[1, 21, 80]` に上げた。

  | 種類 | 変換 |
  |---|---|
  | アイテム | `minecraft:item`。`minecraft:icon` の `textures.default` に、item_texture.json の短縮名を書く。名前は `minecraft:display_name` で `item.<id>.name` を参照する |
  | ブロック | `minecraft:block`。形は `minecraft:geometry.full_block`。面は `material_instances` で、全面が同じなら `*` だけ、違う面があれば側面（north）を `*` にして違う面だけを書く。描画方法はリソースパックで判定した `render`（opaque / alpha_test / blend） |
  | ブロックアイテム | ファイルを作らない（Bedrock がブロックから自動で作る） |
  | 作業台のレシピ | `crafting_shaped` → `minecraft:recipe_shaped`、`crafting_shapeless` → `minecraft:recipe_shapeless`（`tags` は `crafting_table`）。材料と結果は、単一のアイテムのものだけ |
  | 加熱レシピ（v0.4.0） | `smelting`、`blasting`、`smoking`、`campfire_cooking` → `minecraft:recipe_furnace`。機器に合わせて `furnace`、`blast_furnace`、`smoker`、`campfire` と `soul_campfire` のタグを使う。材料1種と結果1個に対応する |

- 左右非対称の定形レシピは書式 `1.20.80` の `assume_symmetry: true` で、Java と同じく左右両方の配置を受け付ける。[Bedrock の定形レシピ](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/recipereference/examples/recipedefinitions/recipe_shaped?view=minecraft-bedrock-stable)

- **変換しないレシピ**（理由付きで SKIPPED、`RECIPE_UNSUPPORTED`）:
  - 定形レシピで、pattern の文字と key が対応しないもの（未定義の文字、使われないキー）
  - タグ（`#c:ingots` など）や、複数候補の材料
  - 成分付きの結果
  - このパックで変換していない MOD のアイテム
  - 加熱レシピの複数個の結果、醸造・鍛冶などの非対応の種類
- **加熱時間と経験値**: Bedrock の加熱レシピでは指定できないので、Bedrock 側の仕様を使う。対応表は PARTIAL とし、`RECIPE_COOKING_DEFAULTS`（WARN）を出す。 [Bedrock の加熱レシピ仕様](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/recipereference/examples/recipedefinitions/recipe_furnace?view=minecraft-bedrock-stable)
- **バニラのアイテム**: 材料・結果にバニラのアイテムがあるレシピは、ID が Java と同じとみなして変換する。ただし PARTIAL にし、`VANILLA_ID_ASSUMED`（WARN）で該当する ID を示す。Bedrock では名前が違うバニラのアイテムがあるため。

### 黙って欠落させない

- **Java のコードで決まる性質**（スタック数・硬さ・道具の条件・ドロップ数など）は、静的には分からないので変換しない。Bedrock の既定値になる。アイテムとブロックは PARTIAL にし、注記で明示する。たとえば fixture の `test_ingot` は、Java ではスタック数が 16、Bedrock では既定の 64 になる。
- **Java のロジック**: entrypoint か Mixin を持つ MOD ごとに、`JAVA_LOGIC_NOT_CONVERTED`（WARN）を出す。`mapping.json` の `notConverted` にも記録する。
- コードでだけ登録された定義（ADR-0003 の `RUNTIME_REGISTRY`）は、リソースパックと同じく SKIPPED にする。
- **変換しないデータ・素材**: MOD ごとに、変換しないカテゴリとファイル数を `DATA_NOT_CONVERTED`（WARN）と `notConverted` に出す。定義を持たない MOD（データだけの MOD など）も対象にする。
  - `data/` は、レシピ以外のすべて（タグ、ルートテーブル、worldgen、アドバンスメントなど）。
  - `assets/` は、モデル・テクスチャ・blockstate・アイテム定義・言語以外（音、パーティクル、シェーダーなど）。
  - fixture では、`data/minecraft/tags`（ツルハシで掘る指定）と `data/pmb_fixture/loot_table` が出る。Bedrock では、道具の条件とドロップの指定が失われる。

### 識別子の衝突

- レシピの Bedrock 識別子が衝突したら、`ID_COLLISION`（ERROR）にして何も作らない。アイテム・ブロック（ADR-0004）とは別に検査する。レシピは別の名前空間のため。

### パックの関係

- ビヘイビアパックのヘッダーとモジュールの UUID は、リソースパックと同じくパック ID から決める（`modbridge:bp:...`）。版は、ビヘイビアパックの内容と、RP への依存（UUID と版）のハッシュから決める。RP だけが変わったときも BP の版を変え、Bedrock が古い依存先の BP を使い回さないようにするため。
- ビヘイビアパックの `dependencies` に、リソースパックのヘッダーの UUID と版を書く。
- **キャッシュ**: 次をすべて満たすときだけ使う。生成器の版は `pack-compiler/2` に上げた（キャッシュの鍵が変わる）。
  - 4 つの成果物が揃っている
  - 両パックの内容のハッシュが対応表と一致する
  - 両パックの manifest の UUID と版が対応表と一致し、BP の依存先が RP のヘッダーである
  - 対応表の BP のファイルの一覧と、BP の中身が一致する
  - `.mcaddon` が、2 つのパックから作り直したものと一致する

  これは、壊れたキャッシュや取り違えを見つけるための検査である。チェックサムごと書き換えた改ざんまでは防がない（キャッシュはローカルの作業フォルダーにある前提）。

### 決定性

- JSON の項目順は、`LinkedHashMap` と `TreeMap` で固定する。`Map.of` は、要素が 2 つ以上あると反復の順序が JVM の起動ごとに変わりうる。そのため、要素が 1 つのものだけに使う。
- 実際に、開発中に要素が 2 つの `Map.of`（ビヘイビアパックの `dependencies`）があり、JVM の起動ごとに `.mcaddon` のバイト列が変わった。直した後は、別々の JVM で 3 回作って一致した。同じ JVM の中では順序が変わらないため、単体テストでは検出できない。

## 実測（2026-11-27）

- 単体テスト 69 件が成功。ビヘイビアパックに関わるものは次のとおり。
  - アイテム・ブロック・レシピのファイルの中身
  - ブロックアイテムのファイルを作らないこと
  - RP への依存と `.mcaddon` の中身
  - `JAVA_LOGIC_NOT_CONVERTED` が出ること
  - v0.3.0 で非対応だったタグ・かまど・未変換の結果を SKIPPED にすること
  - バニラの ID を PARTIAL にすること
  - 4 つの成果物でのキャッシュの検証と、対応表を書き換えたキャッシュを使わないこと
  - レシピの識別子の衝突で何も作らないこと
  - pattern と key が対応しない定形レシピを外すこと
  - 変換しないデータ・素材のカテゴリを報告すること
- fixture 0.0.3 にレシピを 2 つ足した（インゴット 9 個 → ブロック、ブロック → インゴット 9 個）。スパイクの Java サーバーでも、2 つとも実行時レジストリに登録されていた。
- スパイクの `mods/` 一式と実行時レジストリで `modbridge compile` を実行した。結果は次のとおり。
  - ビヘイビアパック: BLOCK PARTIAL 1、ITEM PARTIAL 1、ITEM GENERATED 1（ブロックアイテム）、ITEM SKIPPED 1（素材なし）、RECIPE GENERATED 2
  - `JAVA_LOGIC_NOT_CONVERTED`: geyser-fabric、hydraulic、modbridge、pmb_fixture
  - `DATA_NOT_CONVERTED`: pmb_fixture（`data/minecraft/tags` 1、`data/pmb_fixture/loot_table` 1）
  - 別々の JVM で 3 回作って、成果物のバイト列が一致した

## Minecraft for Windows 26.65 のローカル実測（2026-11-28）

- fixture 0.0.4 と Compressed Blocks 1.0.3 の生成済み RP・BP を隔離ワールドに同梱してインポートした。fixture のブロック・アイテムの描画と、サバイバルで試験用加工台と鍛冶台からフォームが開くことを確認した。
- 試験用加工台で銅インゴット4個のうち2個を消費し、試験用インゴット1個を得た。鍛冶台では丸石18個のうち9個を消費し、圧縮丸石1個を得た。
- ワールドを保存して開き直した後、加工台と鍛冶台が同じ場所にあり、残った材料と加工結果が所持品に保持された。
- Bedrock のコンテンツログで、`unlock` がない 1.20 以降のレシピを読み込めないことを確認した。変換後の材料 ID を解放条件に登録すると、レシピブックに表示される。作業台でブロック1個からインゴット9個を取得し、定形レシピでブロック1個に戻せることを確認した。Java の進捗に基づく解放条件の変換は対象外。
- レシピ修正は PR #25 で先に取り込んだ。加工ルールを含まない PR #25 単体のパックでも、純正クライアントで作業台の両方向のレシピを確認した。
- v0.4.0 の fixture の加熱レシピ4種は Java 26.3 の実行時レジストリに登録された。生成パックを入れた Windows の隔離ワールドでは、かまど・溶鉱炉・燻製器の出力欄にテストインゴットが入り、通常の焚き火と魂の焚き火からも同じアイテムを回収できた。

## 未検証・既知の制約

- 公式 BDS での純正クライアントへのパック配布、複数人参加、プレイヤーの所持品の保存、フォーム加工は受け入れ試験に残る。
- BDS への配置と復旧の設計は [ADR-0007](0007-bds-install-and-rollback.md) を参照。
- Java のコードで決まる性質は、Mode A で取得した実行時レジストリから補える見込み。実行時レジストリにスタック数・硬さなどを足すのは、将来の課題。
- レシピのバニラのアイテム ID の対応表は無い。
- 立方体以外の形、状態を持つブロック、ブロックのドロップ（ルートテーブル）、道具の条件は変換しない。
- `notConverted` には、サーバー側だけの MOD（Geyser など）も並ぶ。Bedrock の成果物に関係しない MOD を区別していない。
