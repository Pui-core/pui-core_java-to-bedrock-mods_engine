# PuiModBridge（仮称）

Java Edition の MOD を、Bedrock Edition のプレイヤーが改造なしのクライアントで遊べるようにする OSS 互換エンジンです。サーバーでの利用を必須要件にしています。設計方針は [Epic #1](https://github.com/Pui-core/pui-core_java-to-bedrock-mods_engine/issues/1) にあります。

> **現在の状態: v0.0.1 → v0.1.0 開発中**
> 実機で検証済みの変換機能はまだありません。今あるのはビルド基盤、MOD の静的解析と診断（`scan` / `validate` / `report`）、アドオン生成の試作（`compile`）、検証用の fixture MOD、接続スパイク用スクリプトです。任意の MOD の互換は保証しません。

## 対応モード

| モード | 実行主体 | 状態 |
|---|---|---|
| A: Java MOD サーバー接続 | Fabric サーバー + Geyser + Hydraulic | サーバー側の起動と登録を確認済み。Bedrock 実機からの接続は未確認（[ADR-0002](docs/adr/0002-v0.0.1-target-versions-and-spike.md)） |
| B: Bedrock ネイティブ変換 | BDS / ローカルワールド | 未着手（v0.3.0） |
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

単純なモデル（平面アイテム、立方体のブロック）と、作業台のレシピだけを変換します。それ以外は、理由を付けて対応表（`mapping.json`）で SKIPPED にします。Java のコードで決まる性質（スタック数・硬さなど）と Java のロジックは変換せず、そのことを対応表と診断に書きます。解析に ERROR があるときや、ID が衝突したときは何も作らず、出力フォルダーも変更しません。BDS・実機での動作は未検証です。詳しくは [ADR-0004](docs/adr/0004-asset-compiler-and-mapping.md) と [ADR-0006](docs/adr/0006-mode-b-behavior-pack.md) にあります。

### BDS への配置

```sh
modbridge bds-install build/pack --bds /path/to/bedrock-server                            # 計画を表示するだけ（既定）
modbridge bds-install build/pack --bds /path/to/bedrock-server --apply --confirm-stopped   # BDS を止めてから配置する
modbridge bds-rollback --bds /path/to/bedrock-server --backup <ID> --confirm-stopped        # 配置の前に戻す
```

配置の前に、ワールドと設定を `modbridge-backups/` に保存します。BDS が動いている（ワールドの DB がロックされている）ときは配置しません。詳しくは [ADR-0007](docs/adr/0007-bds-install-and-rollback.md) にあります。

## fixture MOD

`fixtures/fabric-fixture` は検証用の自作 MOD です。独自アイテム `pmb_fixture:test_ingot`、独自ブロック `pmb_fixture:test_block`、素材を持たないコード専用のアイテム `pmb_fixture:code_only_marker` と、レシピ 2 つ（インゴット 9 個 ⇔ ブロック）を登録します。Minecraft 本体を取得するので、ルートとは別のビルドにしています。

```sh
cd fixtures/fabric-fixture && ./gradlew build   # build/libs/pmb-fabric-fixture-0.0.3.jar
```

## Fabric サーバー用 MOD（Mode A）

`platform-fabric` は、Fabric 専用サーバー（Geyser と一緒に使う）に入れる MOD です（MOD ID `modbridge`）。

- **接続ゲート**: サーバーの起動後に `mods/` を解析し、Bedrock 版で重大な非対応があれば、Bedrock からの接続を断ります。Java 版の接続には関わりません。方針は JVM 引数 `-Dmodbridge.gate=strict|permissive|off`（既定 `strict`）で決めます。判定の根拠は `config/modbridge/gate-report.json` とサーバーログに出ます。詳しくは [ADR-0005](docs/adr/0005-mode-a-connection-gate.md) にあります。
- **実行時レジストリの取得**: JVM 引数 `-Dmodbridge.registryDump=<file>` を付けると、MOD が実際に登録したアイテム・ブロック・レシピを JSON に書き出します（`-Dmodbridge.registryDump.stop=true` を足すと、書き出した後にサーバーを止めます）。

```sh
cd platform-fabric && ./gradlew build          # build/libs/modbridge-fabric-0.2.0.jar（Geyser の API を取得するため、初回はネットワークが要る）
```

ビルド済みなら、接続スパイクのスクリプトが自動で組み込み、`local/spike-server/registry-dump.json` に書き出します。

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
- **`--keep-running` は LAN 全体に公開されます**。Java 側は `online-mode=false` で、ホワイトリストもありません。信頼できる LAN でだけ使い、確認が終わったらすぐ停止してください。

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
