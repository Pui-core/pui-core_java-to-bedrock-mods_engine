# ADR-0007: BDS への配置（dry-run・バックアップ・apply・rollback）

- 状態: 採用（実際の BDS では未検証）
- 日付: 2026-11-27
- 関連: #6, #1、前提は ADR-0006

## 背景

#6 の設計に「`world_resource_packs.json` / `world_behavior_packs.json` の安全な配置。dry-run 既定、停止中バックアップ、apply/rollback」がある。手で配置すると、次の事故が起きやすい。

- ほかのパックの項目を消す
- 同じパックを二重に登録する
- BDS の実行中にワールドを書き換える
- 戻し方が分からなくなる

## 決定

### CLI

| コマンド | 動作 |
|---|---|
| `modbridge bds-install <compile の出力> --bds DIR [--world NAME]` | 配置の計画を表示するだけ（dry-run、既定） |
| `modbridge bds-install <compile の出力> --bds DIR [--world NAME] --apply --confirm-stopped` | 配置する |
| `modbridge bds-rollback --bds DIR --backup ID --confirm-stopped` | バックアップから戻す |

ワールド名を省くと、`server.properties` の `level-name` を使う。

### 触る範囲

- `behavior_packs/modbridge-bp/` と `resource_packs/modbridge-rp/`（BDS のフォルダー直下。中身を毎回置き換える）
- `worlds/<ワールド>/world_behavior_packs.json` と `world_resource_packs.json` の、自分のパックの UUID の項目だけ。ほかの項目と順序は残し、自分の項目は 1 つにする（やり直しても重複しない）
- ワールドの DB（`db/`）には触らない。`server.properties` も変えない（クライアントにリソースパックを強制する `texturepack-required` は、運用者が決める）

### 安全策

- **dry-run が既定**: `--apply` を付けない限り、何も変更しない。
- **停止の確認**: `--confirm-stopped` の明示に加えて、ワールドの DB のロック（`db/LOCK`）を取れるかで確かめる。BDS の実行中は LevelDB がロックを持つので、取れなければ断る。DB がまだ無いワールドでは、明示だけに頼る。
- **バックアップ**: 配置の前に、ワールドのフォルダー全体・自分のパックのフォルダー・2 つの JSON を `modbridge-backups/<UTC 時刻>/` に保存する。何があり、何が無かったかを `backup.json` に記録する。`db/LOCK` は保存しない（ロック中で読めない場合があり、LevelDB が作り直すため）。
- **失敗時の復旧**: 配置の途中で失敗したら、配置で変えたもの（パックのフォルダーと 2 つの JSON）をバックアップから戻す。ワールドの DB はロック中で、配置では触らないので戻さない。復旧にも失敗したら、バックアップの ID を示して rollback を促す。
- **rollback**: ロックで停止を確かめてから、ワールドのフォルダーごと戻す。戻す前にあった `db/LOCK` は作り直す。バックアップの ID は形式（`yyyyMMddTHHmmssZ`）を検査し、対象のパスが BDS のフォルダーの外を指さないことも確かめる。
- **入力の検査**: `.mcpack` は上限付きで展開する。エントリー名は ZIP Slip の検査（ADR-0003）を通す。`manifest.json` の UUID・版・モジュールの種類も確かめる。ワールド名にパスの区切り文字は使えない。BDS のフォルダーと、自分のパックのフォルダーがシンボリックリンクなら断る。コピーでも、シンボリックリンクはたどらない。

## 実測（2026-11-27、BDS 本体は使わない）

- 単体テスト 76 件が成功した（`BdsInstallerTest` 7 件を含む）。BDS と同じフォルダー構成を作って、次を確かめた。
  - dry-run は何も変更しない
  - 明示が無いと配置しない
  - DB のロックを持たれていると配置しない
  - ほかのパックの項目を残し、やり直しても重複せず、古い中身を残さない
  - ワールドの DB に触らない
  - rollback で配置の前と同じになる
  - 途中の失敗で元に戻る
  - 不正なワールド名・バックアップ ID を拒否する
- CLI で、スパイクの `mods/` から作ったアドオンを、試験用の BDS フォルダーに dry-run → apply → rollback した。
  - BP には、アイテム・ブロック・レシピ 2 つのファイルが入った。
  - `world_behavior_packs.json` に、自分の項目が 1 つ入った。

## 未検証・既知の制約

- **実際の BDS で、配置したアドオンが読み込まれるかは未確認**。BDS の起動には、Minecraft の EULA とプライバシーポリシーへの同意が要る。これは owner の判断を待っている。
- Linux の BDS での確認も未実施。LevelDB のロックは Linux でも `fcntl` で取られ、Java の `tryLock` と同じ仕組みなので検出できる見込み（推測）。
- バックアップはワールド全体を複製するので、大きなワールドでは時間と容量がかかる。古いバックアップは自動では消さない。
- Windows では、フォルダーの置き換えは原子的ではない。
- rollback では、バックアップの後に BDS で遊んだ分のワールドの変化も戻る（バックアップ時点に戻す）。
