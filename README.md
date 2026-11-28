# PuiModBridge（仮称）

Java Edition の MOD を、Bedrock Edition のプレイヤーが改造なしのクライアントで遊べるようにする OSS 互換エンジンです。サーバーでの利用を必須要件にしています。設計方針は [Epic #1](https://github.com/Pui-core/pui-core_java-to-bedrock-mods_engine/issues/1) にあります。

> **v0.7.0 開発版**
> 自作 fixture と Compressed Blocks の加工、公式 BDS での PC・スマートフォンの参加と再起動を検証しました。開発版では回転体・音と Create の静的プレビューを追加しました。新機能のクライアント実機試験は後の工程です。

## 対応モード

| モード | 実行主体 | 状態 |
|---|---|---|
| A: Java MOD サーバー接続 | Fabric サーバー + Geyser + Hydraulic | Bedrock 2人の同時入場と試験用ブロックの設置・破壊の同期を確認（[試験結果](docs/adr/0005-mode-a-connection-gate.md#v010-の認証実機検証)） |
| B: Bedrock ネイティブ変換 | BDS / ローカルワールド | Windows のローカルワールドで基本素材、レシピ、フォーム加工を確認。公式 BDS 1.26.66.1 の Windows と WSL Ubuntu で配置・更新・復旧を確認（[ADR-0006](docs/adr/0006-mode-b-behavior-pack.md)） |
| C: プロキシ・複数サーバー | 各 Java バックエンド | 未着手（v0.6.0） |

## 必要なもの

- JDK 25 以上（Hydraulic の要件。CI は JDK 25、手元の実測は JDK 26.0.1）
- Git Bash または Linux のシェル（スパイク用スクリプトの実行に使う）
- Gradle の個別インストールは不要（Wrapper 同梱）

## ビルドとテスト

```sh
./gradlew build                 # core / cli のビルドと単体テスト
./gradlew :cli:run --args=doctor
./gradlew :cli:installDist      # cli/build/install/modbridge/bin/modbridge を生成
```

### MOD の解析と診断

```sh
modbridge validate mods/                       # フォルダー直下の *.jar（大文字の .JAR も含む）をまとめて診断
modbridge scan mods/ --out build/ir.json       # Bridge IR（JSON）を書き出す
modbridge scan mods/ --runtime registry-dump.json --out build/ir.json  # 実行時レジストリと突き合わせる
modbridge report build/ir.json                 # 方式と検証状態の一覧を表示
```

JAR は展開せず、コードも実行しません（静的解析）。コードでだけ登録される定義は、`--runtime` に実行時レジストリ（下の「実行時レジストリの取得」）を渡すと見つけられます。IR の形式と診断コードの一覧は [ADR-0003](docs/adr/0003-bridge-ir-and-static-scan.md) にあります。計画上の状態は、実機で検証されるまですべて `UNTESTED` です。

`modbridge` の終了コードは `0`=成功、`1`=ERROR 診断あり（読めない JAR、JAR が 1 つも無い場合を含む）または不正な IR、`2`=使い方の誤り、`3`=IR の書き出しと IR ファイルの読込の失敗です。

## アドオンの生成（Mode B 向け、試作）

```sh
modbridge compile mods/ --out build/pack --cache .modbridge-cache
# build/pack/ に modbridge-rp.mcpack（見た目）、modbridge-bp.mcpack（定義）、modbridge.mcaddon（両方）、mapping.json（対応表）
```

平面アイテムと立方体のブロックに加え、作業台、かまど、溶鉱炉、燻製器、焚き火の単純なレシピを変換します。加熱レシピは材料1種と結果1個に対応し、時間と経験値には Bedrock 側の仕様を使います。非対応の定義と Java のコードで決まる性質は、対応表（`mapping.json`）と診断に理由を出します。解析に ERROR がある場合や ID が衝突した場合は、出力フォルダーを変更しません。Windows 版 26.65 のローカルワールドで、立方体ブロックの面の向き・透過・日本語名を確認済みです。詳しくは [ADR-0004](docs/adr/0004-asset-compiler-and-mapping.md) と [ADR-0006](docs/adr/0006-mode-b-behavior-pack.md) にあります。

### BDS への配置

```sh
modbridge bds-install build/pack --bds /path/to/bedrock-server                            # 計画を表示するだけ（既定）
modbridge bds-install build/pack --bds /path/to/bedrock-server --apply --confirm-stopped   # BDS を止めてから配置する
modbridge bds-rollback --bds /path/to/bedrock-server --backup <ID> --confirm-stopped        # 配置の前に戻す
```

配置の前に、ワールドと共有パックを `modbridge-backups/` に保存します。稼働中の BDS への配置・復旧は拒否します。公式 BDS 1.26.66.1 で接続するには `transport=nethernet` を使います。隔離試験では `server-ip` に Tailscale の IPv4 を指定しました。詳しくは [ADR-0007](docs/adr/0007-bds-install-and-rollback.md) を参照してください。

## fixture MOD

`fixtures/fabric-fixture` は検証用の自作 MOD です。独自アイテム `pmb_fixture:test_ingot`、独自ブロック `pmb_fixture:test_block`、素材を持たないコード専用のアイテム `pmb_fixture:code_only_marker` を登録します。作業台ではインゴット9個とブロック1個を相互に変換し、かまど・溶鉱炉・燻製器・焚き火ではブロック1個をインゴット1個に変換できます。Minecraft 本体を取得するので、ルートとは別のビルドにしています。

```sh
cd fixtures/fabric-fixture && ./gradlew build   # build/libs/pmb-fabric-fixture-0.0.4.jar
```

## Fabric サーバー用 MOD（Mode A）

`platform-fabric` は、Fabric 専用サーバー（Geyser と一緒に使う）に入れる MOD です（MOD ID `modbridge`）。

- **起動時の互換性検査**では、`mods/` の解析で Bedrock 版に重大な非対応が見つかると、既定の `strict` でサーバーの起動を中断します。方針は JVM 引数 `-Dmodbridge.gate=strict|permissive|off` で設定します。判定の根拠は `config/modbridge/gate-report.json` とサーバーログに出ます。 [ADR-0005](docs/adr/0005-mode-a-connection-gate.md)
- **実行時レジストリの取得**: JVM 引数 `-Dmodbridge.registryDump=<file>` を付けると、MOD が実際に登録したアイテム・ブロック・レシピを JSON に書き出します（`-Dmodbridge.registryDump.stop=true` を足すと、書き出した後にサーバーを止めます）。

```sh
cd platform-fabric && ./gradlew build          # build/libs/modbridge-fabric-0.7.0-SNAPSHOT.jar（初回は Geyser の API を取得）
```

ビルド済みなら、接続スパイクのスクリプトが自動で組み込み、`local/spike-server/registry-dump.json` に書き出します。

### Mode A の加工アダプター

Fabric の `fabric.mod.json` に `modbridge-adapter` エントリーポイントを登録し、`Adapter` を実装します。運用者が JVM 引数 `-Dmodbridge.adapters.trusted=<提供元MODのID>` で許可した提供元から、対象 MOD の版とモードが一致するものを読み込みます。複数の提供元はカンマで区切ります。

アダプターのビルドでは PuiModBridge のチェックアウトを Gradle の `includeBuild` で参照し、`dev.puicore.modbridge:core:0.7.0-SNAPSHOT` を `compileOnly` にします。実行時の `core` は `modbridge-fabric` が供給します。 [fixture の設定](fixtures/fabric-fixture/settings.gradle) · [fixture の依存指定](fixtures/fabric-fixture/build.gradle) · [エントリーポイント](fixtures/fabric-fixture/src/main/resources/fabric.mod.json)

`descriptor()` で API 版、対象 MOD ID、対応する版の文字列、`Mode.A`、`MACHINE_MENU` を宣言し、起動時の `register()` でメニューを登録します。終了処理が必要な場合は `shutdown()` を実装します。メニューとレシピには名前空間付き ID を付け、加工台・材料・結果は Java の登録 ID で指定します。 [Adapter API](core/src/main/java/dev/puicore/modbridge/core/adapter/Adapter.java) · [動作するサンプル](fixtures/fabric-fixture/src/main/java/dev/puicore/modbridge/fixture/FixtureMachineAdapter.java)

fixture の `pmb_fixture` を許可すると、`pmb_fixture:test_block` が加工台になり、銅インゴット2個を試験用インゴット1個に交換できます。応答は30秒以内に1回使えます。加工時に距離、権限、ブロック、材料、出力先の空きを確認し、結果をアクションバーで知らせます。スニーク中は通常どおりブロックを操作できます。

`-Dmodbridge.adapters.rules=<JSONファイル>` では、対象の MOD・版・モードと加工内容を宣言できます。[Compressed Blocks 1.0.3 用の例](fixtures/adapter-rules/compressed-blocks.json)を指定すると、Bedrock から鍛冶台を操作して丸石9個を圧縮できます。ルールはサーバーの運用者が指定します。

Minecraft for Windows 26.65 で、認証付きの隔離 Fabric/Geyser に参加し、両方の加工画面と所持品・アクションバーへの反映を確認済みです。フォームを開いてから加工台の操作範囲を離れると、加工は行われず所持品が維持されました。

### Mode B の加工ルール

```sh
modbridge compile pmb-fabric-fixture.jar fabric-api.jar --out build/machine-pack --adapter-rules fixtures/adapter-rules/fixture-mode-b.json
```

運用者が指定した JSON のうち、入力 JAR の MOD ID・版と Mode B に一致するルールを使います。加工台と材料・結果は Java 側の ID で指定します。任意の `mappings` で、カスタムアイテム・ブロック・レシピの生成先 ID を変更できます。対応するパック定義・レシピ・加工メニューにも変更が反映されます。Bedrock で未変換のカスタム ID を加工で参照すると、コンパイルを中止します。

[Compressed Blocks 1.0.3 用の例](fixtures/adapter-rules/compressed-blocks.json)は Mode B でも使えます。複数の MOD のルールを使う場合は、1ファイルの `adapters` 配列にまとめます。

サバイバルのメンバーとオペレーターが加工台を操作できます。スクリプトは画面を開くときと選択を受け付けるときにこの権限を確認します。スニーク中は通常のブロック操作ができます。選択の有効期間は600サーバーティックで、処理結果はアクションバーに出ます。既定のスタックと結合できる材料を使います。必要な個数または出力先が足りなければ、所持品を変更しません。

加工メニューを含むパックは Bedrock 1.21.100 以上と安定版の `@minecraft/server` 2.1.0、`@minecraft/server-ui` 2.0.0 を要求します。加工台には Bedrock 標準のプレイヤー権限が適用されます。他のアドオンが領域ごとに設ける操作制限との連携は未対応です。Minecraft for Windows 26.65 のローカルワールドで、両方の加工画面、材料の消費と結果の追加、ワールド再読込後の保存を確認済みです。公式 BDS での動作は実機確認待ちです。[Bedrock 1.21.100 の変更履歴](https://feedback.minecraft.net/hc/en-us/articles/38581287884557-Minecraft-1-21-100-Bedrock)

## 接続スパイク（Mode A）

Fabric 専用サーバー、Geyser-Fabric、Hydraulic、fixture MOD を `local/spike-server` に組んで起動します。取得物はすべて固定版で、SHA-256 照合が通ったものだけを使います。

```sh
# Minecraft EULA（https://aka.ms/MinecraftEULA）に同意する場合だけ環境変数を付ける
MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh                 # 起動を確認したら自動で停止
MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh --keep-running  # Bedrock 実機から接続するとき
```

- ログは `evidence/raw/`（Git 管理外）に保存します。保存時に IP アドレスを伏せ字にしますが、念のためコミットしないでください。作業フォルダー内のサーバー自身のログ（`local/spike-server/logs/`）は伏せ字にしていません。
- 成否は Geyser の起動完了行で判定します。起動に失敗したり時間切れになったりした場合は、その回のサーバープロセスだけを強制終了して、終了コード 1 を返します。
- 作業フォルダーには、空のフォルダーか、このスクリプトが作ったフォルダー（印 `.modbridge-spike` があるもの）だけを使います。
- 2 回目以降の起動では、Geyser の設定を次のように変えます。
  - 外部への STUN 問い合わせを止める。
  - RakNet（UDP 19132）と NetherNet（UDP 19133、シグナリングは TCP 19132）の両方を受ける。
  - 自動停止モードでは待受を `127.0.0.1` に限定する。

  初回起動は Geyser の設定を生成する回なので、既定設定のまま動きます。
- 実機から接続するときは `--keep-running` を使います。同じ LAN の Bedrock クライアントで「サーバーを追加」を選び、この PC の LAN アドレスとポート 19132 を入力してください。
- **`--keep-running` は LAN 全体に公開されます**。従来のスパイクでは Java 側が `online-mode=false` です。信頼できる LAN でだけ使い、確認が終わったら停止してください。

### v0.1.0 の認証・実機検証

fixture MOD と `platform-fabric` の両方をビルドしてから、認証付きの検証環境を起動します。

```sh
# Minecraft EULA に同意する場合だけ環境変数を付ける
MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh --floodgate
MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh --floodgate --keep-running
```

`--floodgate` は `local/mode-a-server` に専用ワールドを作ります。Java 版は認証付きでこの PC の `127.0.0.1:25565`、Bedrock 版は Floodgate 認証付きで `--keep-running` 時に LAN のポート `19132` から接続できます。操作・再接続・ワールドの再起動を確認する手順は [ADR-0005](docs/adr/0005-mode-a-connection-gate.md#v010-の認証実機検証) にあります。

## エンティティ・音と Create の段階試験

Mode B では、MOD の JAR に `assets/<mod>/modbridge/presentation.json` を置くと、回転エンティティの見た目とサーバー定義を RP・BP に出力します。`schemaVersion: 1`、`entities` の各要素に `id`、`texture`（`entity/<name>`）、`axis`（`x`・`y`・`z`）、`rpm`（1〜120）を指定します。`smoke: true` を付けると回転中に煙を出します。PNG は自 MOD の `textures/entity/` に置き、32×16 px とします。ひとつの回転体につきクライアントに同期する真偽値は1個で、回転角はクライアントが計算します。Java MOD の AI、レンダラー、動的な衝突形状は変換対象外です。[Bedrock エンティティプロパティ](https://learn.microsoft.com/en-us/minecraft/creator/documents/introductiontoentityproperties?view=minecraft-bedrock-stable)

`assets/<mod>/sounds.json` の自 MOD に対する直接の名前空間付き Ogg Vorbis 参照を `modbridge.<mod>.<event>` として RP に出力します。イベント参照、字幕、Java の再生トリガーや減衰設定は個別対応の範囲です。fixture のパックを生成すると、次のコマンドで回転体と音を呼び出せます。回転体を操作すると回転のオン・オフを切り替える設計です。表示、煙、音、操作の実機結果は後日記録します。[Bedrock サウンド](https://learn.microsoft.com/en-us/minecraft/creator/documents/introductiontosound?view=minecraft-bedrock-stable)

```text
/summon pmb_fixture:test_rotor
/event entity @e[type=pmb_fixture:test_rotor,c=1] modbridge:activate
/event entity @e[type=pmb_fixture:test_rotor,c=1] modbridge:deactivate
/playsound modbridge.pmb_fixture.test_rotor @s
```

```sh
modbridge create-preview /path/to/create.jar
modbridge create-preview /path/to/create.jar --out /path/to/preview
```

`create-preview` は Create の JAR をコード実行せずに読み、対象の Minecraft・ローダー・確認できた素材を JSON に出します。出力先を指定すると、安山岩合金・真鍮インゴット・安山岩ケーシング・真鍮ケーシングの静的プレビューを既存のパック形式で生成します。素材は指定した JAR から取得し、再配布する場合は上流の条件に従います。

| 対象 | Mode A | Mode B |
| --- | --- | --- |
| 静的素材 | Geyser への登録とクライアント表示は確認待ち | アイコンと単純ブロックの試験用アドオン |
| 回転軸・歯車 | Create のサーバーが持つ回転・軸方向を送るアダプターが必要 | 回転体 fixture は実装済み。Create の動力ネットワークは未対応 |
| 加工機 | Create のレシピ、入出力、状態の連携が必要 | 既存の宣言加工ルールを利用可能。Create の加工ロジックは未対応 |
| コンベア | 運搬中のアイテムの追跡、表示と当たり判定が必要 | 未対応 |
| 移動構造物・列車 | 描画・帯域・衝突の成立性を別途確認 | 未対応 |

静的試験の対象は Create Fabric `6.0.8.1+build.1744-mc1.20.1` です。公開配布の Fabric 26.3 向けビルドを確認できていないため、この JAR を現在の Fabric 26.3 サーバーに載せる Mode A の実験は保留です。[Create Fabric の配布版](https://modrinth.com/mod/create-fabric/versions) [Create のリリース](https://github.com/Creators-of-Create/Create/releases)


## 複数バックエンドの運用（v0.6.0 開発版）

Geyser/Fabric の MOD サーバーと BDS は、認証設定、パック、ワールド、プレイヤーデータを個別に管理します。既存の Paper/Velocity は別の入口とポートで運用できます。Velocity の modern forwarding を使って Java 側も接続する場合は、対応するバックエンドと private な転送経路を用意します。[Velocity の設定](https://docs.papermc.io/velocity/player-information-forwarding/) [Floodgate の設定](https://geysermc.org/wiki/floodgate/setup/)

- **単独サーバー**では `DIRECT` に接続先を1件指定します。認証とパックの受け入れはそのサーバーが行います。
- **VPS から自宅・WSL へ転送**する場合は、RakNet の公開 UDP ポートを private ネットワーク内の1台・1ポートへ対応付け、戻りの通信も同じ経路に通します。`UDP_FORWARD` は RakNet を対象とします。NetherNet のシグナリングは別です。バックエンドの入口は VPS または VPN からだけ到達できるようにし、公開側とバックエンド側から別々に監視します。
- **サーバー間の移動**は `BEDROCK_TRANSFER` で運用者が登録した接続先へ、Bedrock クライアントが新しく接続します。移動先で改めて入場判定とパック受け入れが行われます。ワールドや持ち物は各バックエンドのデータを使います。移動を受け付けない端末ではサーバー一覧から接続します。

運用設定の例です。`client` はプレイヤーの端末から接続できるアドレス、`probe` は監視を実行する場所から到達できるアドレスです。`stateDirectory` は配備先の保存ディレクトリを区別する名前です。実際のサービスにも、それぞれ異なる作業・保存ディレクトリを指定します。

```json
{
  "schemaVersion": 1,
  "topology": "BEDROCK_TRANSFER",
  "backends": [
    {
      "id": "fabric",
      "kind": "GEYSER",
      "transport": "RAKNET",
      "client": {"host": "fabric.example.org", "port": 19132},
      "probe": {"host": "fabric.internal.example.org", "port": 19132},
      "stateDirectory": "fabric-world"
    },
    {
      "id": "bedrock",
      "kind": "BDS",
      "transport": "NETHERNET",
      "client": {"host": "bedrock.example.org", "port": 19244},
      "probe": {"host": "bedrock.internal.example.org", "port": 19244},
      "stateDirectory": "bedrock-world"
    }
  ]
}
```

```sh
modbridge ops-check /etc/modbridge/topology.json
modbridge ops-check /etc/modbridge/topology.json --probe
```

`--probe` は、RakNet では Bedrock のオフライン ping、NetherNet では設定先の TCP 接続の成否をバックエンドごとに JSON へ出力します。観測先が1件でも応答しなければ終了コード1を返します。入場・ワールドの読込・パック適用は実クライアントで確認します。Geyser/Fabric で `-Dmodbridge.travelConfig=/etc/modbridge/topology.json -Dmodbridge.travelOrigin=fabric` を付けると、認証済みの Bedrock セッションから `/modbridge-travel bedrock` を実行できます。

Linux の Fabric サーバー用 systemd の例です。`fabric` 専用のサービスアカウントを使い、ほかのワールドは別ユニットで管理します。

```ini
[Unit]
Description=ModBridge Fabric backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=modbridge-fabric
WorkingDirectory=/srv/modbridge/fabric-world
ExecStart=/usr/bin/java -Dmodbridge.travelConfig=/etc/modbridge/topology.json -Dmodbridge.travelOrigin=fabric -jar fabric-server.jar nogui
Restart=on-failure
RestartSec=10
TimeoutStopSec=90
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

監視用の oneshot サービスまたは既存監視から `modbridge ops-check /etc/modbridge/topology.json --probe` を定期実行し、終了コード、個別バックエンドの応答、サービスログを通知に使います。片方が落ちた場合も、他方のプロセスやデータを共有しないため、稼働側を独立して扱えます。

更新前に対象サービスを停止して、ワールドとプレイヤーデータ、設定、MOD、配布パックを組で保存します。バックアップから別ディレクトリに復元して確認してから切り戻します。BDS のパック配置と復旧には `bds-install` と `bds-rollback` を使えます。秘密鍵と forwarding secret はサービスの管理領域に置きます。LAN／公開入口を通した再接続、別パックへの移動、障害復旧の実機試験は別途記録します。


## 構成

```text
core/        Bridge IR・静的解析・診断などの Minecraft 非依存部分
cli/         modbridge コマンド
fixtures/    検証用 fixture MOD（独立ビルド）
scripts/     スパイク・検証用スクリプト
docs/adr/    設計判断の記録
```

## ライセンス

未決定です。候補は MIT で、上流のライセンス確認結果は [ADR-0001](docs/adr/0001-architecture-and-toolchain.md) にまとめています。決定するまでは、このリポジトリのコードを再配布しないでください。

Minecraft は Mojang Studios の商標です。このプロジェクトは Mojang Studios および Microsoft の公式製品ではなく、承認も受けていません。
