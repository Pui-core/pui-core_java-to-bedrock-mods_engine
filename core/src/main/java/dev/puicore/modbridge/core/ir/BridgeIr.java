package dev.puicore.modbridge.core.ir;

import java.util.List;
import java.util.Map;

/**
 * Bridge IR（中間表現）。Java MOD から得た事実と、モード別の変換方式・検証状態を分けて保持する。
 *
 * <p>同じ入力からは同じ IR を生成する（時刻・絶対パス・実行環境を含めない。リストは全てソート済み）。
 * 永続識別子は {@code namespace:path} の文字列で表し、Minecraft の起動時に振られる数値 ID は扱わない。
 */
public record BridgeIr(
        String schema,
        int schemaVersion,
        String generator,
        List<InputFile> inputs,
        List<ModDescriptor> mods,
        List<Definition> definitions,
        List<Diagnostic> diagnostics,
        List<FeaturePlan> plans) {

    public static final String SCHEMA = "modbridge.bridge-ir";
    public static final int SCHEMA_VERSION = 1;

    /** 解析した入力ファイル。名前はファイル名だけ（ディレクトリは含めない）。 */
    public record InputFile(String name, String sha256, long size) {}

    public enum Loader { FABRIC, NEOFORGE, UNKNOWN }

    /** MOD のメタデータ。{@code depends} の値は宣言どおりのバージョン範囲（未評価）。 */
    public record ModDescriptor(
            String id,
            String version,
            String name,
            Loader loader,
            String sourceFile,
            String environment,
            Map<String, String> depends,
            List<String> provides,
            List<String> entrypoints,
            List<String> mixinConfigs,
            List<String> nestedJars) {}

    public enum Kind { ITEM, BLOCK, RECIPE }

    /** 情報源。静的解析で推定したものと、実行時レジストリで確認したものを区別する。 */
    public enum Source { STATIC_ASSET, RUNTIME_REGISTRY }

    /**
     * 定義（アイテム・ブロック・レシピ）。
     *
     * @param evidence 推定の根拠となったファイル（jar 内パス）
     * @param assets   参照している素材（モデル・テクスチャ）
     */
    public record Definition(
            String id,
            Kind kind,
            String modId,
            Source source,
            List<String> evidence,
            List<String> assets,
            Map<String, String> attributes) {}

    public enum Severity { ERROR, WARN, INFO }

    /** 診断。{@code code} は機械処理用の安定した名前、{@code message} は人向けの説明。 */
    public record Diagnostic(Severity severity, String code, String subject, String message) {}

    /** 変換方式。どの方法で対応するかの計画で、動作するかどうかは {@link Status} で別に持つ。 */
    public enum Method { AUTO, RULE, ADAPTER, UNSUPPORTED }

    /** 実測の検証状態。検証の記録がないものは必ず UNTESTED にする。 */
    public enum Status { UNTESTED, SUPPORTED, PARTIAL, UNSUPPORTED, ERROR }

    public enum Mode { A, B }

    public record FeaturePlan(String subject, Mode mode, Method method, Status status, String reason) {}
}
