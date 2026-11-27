package dev.puicore.modbridge.core.compile;

import java.util.Locale;

/**
 * Java の識別子から Bedrock の識別子・テクスチャ短縮名を作る規則。
 *
 * <p>Bedrock 側は {@code [a-z0-9_]} だけを使う（Java の {@code / . -} は {@code _} にする）。
 * 変換で別々の Java 識別子が同じ Bedrock 識別子になることがあるので、衝突は {@link PackCompiler} が検出して拒否する。
 */
public final class BedrockIds {
    private BedrockIds() {}

    /** {@code ns:path} を Bedrock の識別子にする。 */
    public static String identifier(String javaId) {
        int c = javaId.indexOf(':');
        return clean(javaId.substring(0, c)) + ":" + clean(javaId.substring(c + 1));
    }

    /** テクスチャ（{@code ns:path}）の短縮名。atlas の texture_data のキーに使う。 */
    public static String textureShortName(String javaTextureId) {
        return identifier(javaTextureId);
    }

    /** テクスチャをパックに置く場所。Java の名前空間とパスをそのまま使う（ファイル名なので {@code /} を残す）。 */
    public static String texturePath(String javaTextureId) {
        int c = javaTextureId.indexOf(':');
        return "textures/" + javaTextureId.substring(0, c) + "/" + javaTextureId.substring(c + 1);
    }

    private static String clean(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
