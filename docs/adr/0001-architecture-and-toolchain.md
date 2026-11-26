# ADR-0001: モジュール構成・ツールチェーン・ライセンス候補

- 状態: 採用（ライセンスは提案のまま。owner が決める）
- 日付: 2026-11-27
- 関連: #1, #2

## 背景

Epic #1 では、core / IR / compiler / report を Minecraft や特定のローダーから切り離し、Mode A（Geyser/Hydraulic 連携）と Mode B（Bedrock ネイティブ変換）を別の backend に分ける方針を決めている。v0.0.1 では、この方針を崩さない最小のビルド基盤を用意する。

## 決定

1. **Gradle Kotlin DSL のマルチプロジェクト**にする。バージョンは `gradle/libs.versions.toml` に一本化する。
   - `core`: Minecraft にもローダーにも依存しない Java ライブラリ。外部依存ゼロから始める。
   - `cli`: `modbridge` コマンド。`core` だけに依存する。
   - 今後 `platform-fabric`、`backend-geyser`、`backend-bedrock-native` を追加する。追加は必要になった時点で行う（空のモジュールを先に作らない）。
2. **fixture MOD は独立ビルド**（`fixtures/fabric-fixture`）にする。Fabric Loom は Minecraft 本体と大量のライブラリを取得するため、`core` と `cli` のビルド・テストをそれに依存させない。CI では別ジョブでビルドする。
3. **Java 25 バイトコード**（`options.release = 25`）に固定する。根拠は ADR-0002。
4. 再現性のため、アーカイブのタイムスタンプを固定し、エントリー順を一定にする。コンパイラ警告はエラー扱い（`-Xlint:all -Werror`）にする。
5. CLI の終了コードは `0`=成功、`1`=診断失敗、`2`=使い方の誤りに固定する。v0.1.0 の scan / validate / report もこれに従う。
6. テストは JUnit 5、CI は GitHub Actions（Ubuntu と Windows）で行う。

## ライセンス（提案）

| 上流 | ライセンス | 本プロジェクトとの関係 |
|---|---|---|
| Geyser | MIT | Mode A の連携先。API を利用する |
| Hydraulic | MIT | Mode A の連携先。最小パッチや上流貢献の可能性あり |
| Floodgate-Fabric | MIT | Mode A の認証 |
| PackConverter | MIT | Hydraulic が内部で利用している |
| Fabric Loader / Fabric API | Apache-2.0 | fixture と platform-fabric の依存 |
| Rainbow | **GPL-3.0** | #4 で利用を検討していた。MIT で配布するならコードを取り込めない。参照・別プロセス利用に限る |

提案は **MIT** である。主な連携先がすべて MIT か Apache-2.0 で、組み合わせに問題がない。GPL-3.0 の Rainbow のコードは取り込まない。最終決定は owner が行い、`LICENSE` ファイルはその時点で追加する。決定までは fixture の `fabric.mod.json` も `LicenseRef-Undecided` にしておく。

## 影響

- `core` は依存ゼロのため、Mode B のツール（BDS 側の生成）でも同じ IR を使える。
- fixture は独立ビルドなので、上流の Minecraft 更新で fixture が壊れても core の CI は止まらない。fixture ジョブの失敗は別途扱う。
