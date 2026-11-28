# PuiModBridge（仮称）

Java Edition の MOD を、Bedrock Edition のプレイヤーが改造なしのクライアントで遊べるようにする OSS 互換エンジンです。サーバーでの利用を必須要件にしています。設計方針は [Epic #1](https://github.com/Pui-core/pui-core_java-to-bedrock-mods_engine/issues/1) にあります。

> **現在の状態: v0.0.1 → v0.1.0 開発中**
> Windows 版 Bedrock のローカルワールドで単純なブロック素材の描画を確認しました。ビルド基盤、MOD の静的解析と診断（`scan` / `validate` / `report`）、アドオン生成の試作（`compile`）、検証用の fixture MOD、接続スパイク用スクリプトを提供しています。任意の MOD の互換は保証しません。

## 対応モード

| モード | 実行主体 | 状態 |
|---|---|---|
| A: Java MOD サーバー接続 | Fabric サーバー + Geyser + Hydraulic | Bedrock 2人の同時入場と試験用ブロックの設置・破壊の同期を確認（[試験結果](docs/adr/0005-mode-a-connection-gate.md#v010-の認証実機検証)） |
| B: Bedrock ネイティブ変換 | BDS / ローカルワールド | Windows のローカルワールドで基本素材の描画と作業台のレシピを確認。公式 BDS は未検証（[ADR-0006](docs/adr/0006-mode-b-behavior-pack.md)） |
| C: プロキシ・複数サーバー | Geyser/Fabric と独立 BDS | 配置設定の検査、接続先の監視、明示的な Bedrock 移動。実機試験は後日 |

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

単純なモデル（平面アイテム、立方体のブロック）と、作業台のレシピだけを変換します。それ以外は、理由を付けて対応表（`mapping.json`）で SKIPPED にします。Java のコードで決まる性質（スタック数・硬さなど）と Java のロジックは変換せず、そのことを対応表と診断に書きます。解析に ERROR があるときや、ID が衝突したときは何も作らず、出力フォルダーも変更しません。Windows 版 26.65 のローカルワールドで、立方体ブロックの面の向き・透過・日本語名を確認済みです。BDS の動作は未検証です。詳しくは [ADR-0004](docs/adr/0004-asset-compiler-and-mapping.md) と [ADR-0006](docs/adr/0006-mode-b-behavior-pack.md) にあります。

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

- **起動時の互換性検査**では、`mods/` の解析で Bedrock 版に重大な非対応が見つかると、既定の `strict` でサーバーの起動を中断します。方針は JVM 引数 `-Dmodbridge.gate=strict|permissive|off` で設定します。判定の根拠は `config/modbridge/gate-report.json` とサーバーログに出ます。 [ADR-0005](docs/adr/0005-mode-a-connection-gate.md)
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
- **`--keep-running` は LAN 全体に公開されます**。従来のスパイクでは Java 側が `online-mode=false` です。信頼できる LAN でだけ使い、確認が終わったら停止してください。

### v0.1.0 の認証・実機検証

fixture MOD と `platform-fabric` の両方をビルドしてから、認証付きの検証環境を起動します。

```sh
# Minecraft EULA に同意する場合だけ環境変数を付ける
MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh --floodgate
MODBRIDGE_ACCEPT_EULA=true scripts/spike-server.sh --floodgate --keep-running
```

`--floodgate` は `local/mode-a-server` に専用ワールドを作ります。Java 版は認証付きでこの PC の `127.0.0.1:25565`、Bedrock 版は Floodgate 認証付きで `--keep-running` 時に LAN のポート `19132` から接続できます。操作・再接続・ワールドの再起動を確認する手順は [ADR-0005](docs/adr/0005-mode-a-connection-gate.md#v010-の認証実機検証) にあります。

## 複数バックエンドの運用（v0.6.0 開発版）

Geyser/Fabric の MOD サーバーと BDS は、認証設定、パック、ワールド、プレイヤーデータを個別に管理します。既存の Paper/Velocity は別の入口とポートで運用できます。Velocity の modern forwarding を使って Java 側も接続する場合は、対応するバックエンドと private な転送経路を用意します。[Velocity の設定](https://docs.papermc.io/velocity/player-information-forwarding/) [Floodgate の設定](https://geysermc.org/wiki/floodgate/setup/)

- **単独サーバー**では `DIRECT` に接続先を1件指定します。認証とパックの受け入れはそのサーバーが行います。
- **VPS から自宅・WSL へ転送**する場合、現行の NetherNet は `NETHERNET_FORWARD` を使います。Geyser の内蔵シグナリングの TCP と WebRTC の UDP を、同一公開ポートから private ネットワークのバックエンドへそれぞれ転送します。TCP は内蔵シグナリングの設定先、UDP は bedrock.webrtc-port の設定先に転送します。シグナリングで HTTP と HTTPS のどちらを使うかはクライアントの信頼設定に合わせます。固定版 Geyser 2.12.1-b1312 は RakNet 単独設定を受け付けません。NAT 越しには、到達可能な公開 UDP アドレスとポートを `advertise-addresses` に設定します。バックエンドは VPS または VPN からだけ到達できるようにし、公開側とバックエンド側を別々に監視します。RakNet を提供する旧構成の UDP 転送には `UDP_FORWARD` を使えます。[Geyser のポート転送](https://geysermc.org/wiki/geyser/port-forwarding/) [NetherNet と NAT の設定](https://geysermc.org/blog/preparing-for-nethernet/)
- **サーバー間の移動**は `BEDROCK_TRANSFER` で運用者が登録した接続先へ、Bedrock クライアントが新しく接続します。移動先で改めて入場判定とパック受け入れが行われます。ワールドや持ち物は各バックエンドのデータを使います。移動を受け付けない端末ではサーバー一覧から接続します。

運用設定の例です。`client` はプレイヤーの端末から接続できるアドレス、`probe` は監視を実行する場所から到達できるアドレスです。同じ通信方式で同じ監視先は登録できません。DNS の別名が同じサーバーを指すかは配備時に確認します。`stateDirectory` は配備先の保存ディレクトリを区別する名前です。実際のサービスにも、それぞれ異なる作業・保存ディレクトリを指定します。

```json
{
  "schemaVersion": 1,
  "topology": "BEDROCK_TRANSFER",
  "backends": [
    {
      "id": "fabric",
      "kind": "GEYSER",
      "transport": "NETHERNET",
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

`--probe` は、RakNet では Bedrock のオフライン ping、NetherNet では設定先の TCP 接続の成否をバックエンドごとに JSON へ出力します。観測先が1件でも応答しなければ終了コード1を返します。TCP 接続に成功しても、NetherNet の WebRTC 経路や入場、ワールドの読込、パック適用は実クライアントで確認します。サンプルの Fabric 側では Geyser の `bedrock.transport` を `nethernet` に設定します。Geyser/Fabric で `-Dmodbridge.travelConfig=/etc/modbridge/topology.json -Dmodbridge.travelOrigin=fabric` を付けると、認証済みの Bedrock セッションから `/modbridge-travel bedrock` を実行できます。

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
SuccessExitStatus=143
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
