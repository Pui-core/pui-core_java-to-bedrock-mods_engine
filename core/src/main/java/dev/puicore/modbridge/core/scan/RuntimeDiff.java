package dev.puicore.modbridge.core.scan;

import dev.puicore.modbridge.core.TargetVersions;
import dev.puicore.modbridge.core.ir.BridgeIr.Definition;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.ModDescriptor;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.BridgeIr.Source;
import dev.puicore.modbridge.core.runtime.RuntimeRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 静的解析の定義と実行時レジストリを突き合わせる。
 *
 * <ul>
 *   <li>両方にある定義: 属性 {@code runtime=registered} を付ける</li>
 *   <li>実行時にだけある定義（コードでだけ登録され、素材ファイルから推定できなかったもの）:
 *       情報源 {@code RUNTIME_REGISTRY} の定義として追加し、{@code CODE_ONLY_DEFINITION}（WARN）を出す</li>
 *   <li>静的にだけある定義（素材はあるが実行時に登録されていないもの）: {@code STATIC_ONLY}（WARN）</li>
 * </ul>
 *
 * <p>対象にするのは、入力した MOD の ID か、入力した MOD の定義が使う名前空間だけ。入力していない MOD の登録は、
 * 名前空間ごとに件数だけを INFO で示す（一部の MOD だけを解析したときに誤検知を出さないため）。
 */
final class RuntimeDiff {
    static final String RUNTIME_ATTR = "runtime";
    static final String REGISTERED = "registered";

    private RuntimeDiff() {}

    static List<Definition> merge(List<ModDescriptor> mods, List<Definition> defs, RuntimeRegistry rt, List<Diagnostic> diags) {
        String target = TargetVersions.get("minecraft");
        if (!rt.minecraft().equals(target)) {
            // 別の版のデータとは、突き合わせの結果を信用できない（fail closed）
            diags.add(new Diagnostic(Severity.ERROR, "RUNTIME_MINECRAFT_MISMATCH", rt.fileName(),
                    "実行時レジストリは Minecraft " + rt.minecraft() + " で取得されている（本エンジンの対象は " + target + "）"));
        }

        // 実行時に読み込まれていない MOD（静的にだけある定義の判定から外す）
        Set<String> notLoaded = new TreeSet<>();
        for (ModDescriptor m : mods) {
            String loaded = rt.mods().get(m.id());
            if (loaded == null) {
                notLoaded.add(m.id());
                diags.add(new Diagnostic(Severity.WARN, "MOD_NOT_LOADED", m.id(),
                        "実行時レジストリの取得時に読み込まれていない（入力と取得環境の MOD が違う）"));
            } else if (!loaded.equals(m.version())) {
                // 別の版の JAR と突き合わせている。結果を信用できないので、その MOD を fail closed にする
                diags.add(new Diagnostic(Severity.ERROR, "MOD_VERSION_MISMATCH", m.id(),
                        "入力の版 " + m.version() + " と、取得時の版 " + loaded + " が違う"));
            }
        }
        if (!mods.isEmpty() && notLoaded.size() == mods.size()) {
            diags.add(new Diagnostic(Severity.ERROR, "RUNTIME_REGISTRY_UNRELATED", rt.fileName(),
                    "入力した MOD が 1 つも取得時に読み込まれていない（別の環境の実行時レジストリ）"));
        }

        // 名前空間 → その名前空間を担当する MOD
        Map<String, String> owner = new TreeMap<>();
        mods.forEach(m -> owner.putIfAbsent(m.id(), m.id()));
        defs.forEach(d -> owner.putIfAbsent(d.id().substring(0, d.id().indexOf(':')), d.modId()));

        Map<String, Definition> byKey = new LinkedHashMap<>();
        defs.forEach(d -> byKey.put(key(d.kind(), d.id()), d));
        Set<String> seen = new TreeSet<>();
        Map<String, Integer> outside = new TreeMap<>();
        List<Definition> added = new ArrayList<>();
        for (RuntimeRegistry.Entry e : rt.entries()) {
            String ns = e.namespace();
            if (!JarScanner.isModNamespace(ns)) {
                continue;
            }
            String modId = owner.get(ns);
            if (modId == null) {
                outside.merge(ns, 1, Integer::sum);
                continue;
            }
            Kind kind = kindOf(e.registry());
            String k = key(kind, e.id());
            seen.add(k);
            Definition d = byKey.get(k);
            if (d != null) {
                byKey.put(k, withAttribute(d));
            } else {
                added.add(new Definition(e.id(), kind, modId, Source.RUNTIME_REGISTRY, List.of("runtime:" + e.registry()), List.of(),
                        Map.of(RUNTIME_ATTR, REGISTERED)));
                diags.add(new Diagnostic(Severity.WARN, "CODE_ONLY_DEFINITION", e.id(), kind == Kind.RECIPE
                        ? "RECIPE がコードでだけ登録されている（レシピ JSON が無い）。Mode B では変換できない"
                        : kind + " がコードでだけ登録されている（素材ファイルから推定できなかった）。Bedrock 側の見た目を生成できない"));
            }
        }
        outside.forEach((ns, n) -> diags.add(new Diagnostic(Severity.INFO, "RUNTIME_OUTSIDE_INPUT", ns,
                "入力に無い MOD の名前空間の登録 " + n + " 件は対象外")));

        for (Definition d : byKey.values()) {
            if (!seen.contains(key(d.kind(), d.id())) && !notLoaded.contains(d.modId())) {
                diags.add(new Diagnostic(Severity.WARN, "STATIC_ONLY", d.id(),
                        d.kind() + " の素材はあるが、実行時に登録されていない（名前の違い・条件付き登録・削除された定義の可能性）"));
            }
        }
        List<Definition> out = new ArrayList<>(byKey.values());
        out.addAll(added);
        return out;
    }

    private static Definition withAttribute(Definition d) {
        Map<String, String> attrs = new TreeMap<>(d.attributes());
        attrs.put(RUNTIME_ATTR, REGISTERED);
        return new Definition(d.id(), d.kind(), d.modId(), d.source(), d.evidence(), d.assets(), Collections.unmodifiableMap(attrs));
    }

    private static Kind kindOf(String registry) {
        return switch (registry) {
            case "item" -> Kind.ITEM;
            case "block" -> Kind.BLOCK;
            case "recipe" -> Kind.RECIPE;
            default -> throw new IllegalArgumentException(registry); // RuntimeRegistry.read で検証済み
        };
    }

    private static String key(Kind kind, String id) {
        return kind + "|" + id;
    }
}
