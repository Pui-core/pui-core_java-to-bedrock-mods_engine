package dev.puicore.modbridge.core.scan;

import dev.puicore.modbridge.core.TargetVersions;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.BridgeIr.Definition;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.FeaturePlan;
import dev.puicore.modbridge.core.ir.BridgeIr.InputFile;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Method;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import dev.puicore.modbridge.core.ir.BridgeIr.ModDescriptor;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.BridgeIr.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/** 複数の JAR をまとめて解析し、MOD をまたぐ検査（重複・依存・循環）と方式の計画を加えて IR を作る。 */
public final class Analyzer {

    /** ローダー・ゲーム本体が提供する ID。scan 対象に含めなくても依存を満たしているとみなす。 */
    static final Set<String> BUILTIN_IDS = Set.of("minecraft", "java", "fabricloader", "fabric-loader");

    private Analyzer() {}

    /** ファイルとディレクトリ（直下の *.jar）を受け取り、IR を作る。 */
    public static BridgeIr analyzePaths(List<Path> paths) {
        List<Path> jars = new ArrayList<>();
        List<Diagnostic> diags = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.getFileName().toString().endsWith(".jar") && Files.isRegularFile(f)).forEach(jars::add);
                } catch (IOException e) {
                    diags.add(new Diagnostic(Severity.ERROR, "IO_ERROR", p.getFileName().toString(), "フォルダーを読めない: " + e.getMessage()));
                }
            } else if (Files.isRegularFile(p)) {
                jars.add(p);
            } else {
                diags.add(new Diagnostic(Severity.ERROR, "IO_ERROR", String.valueOf(p.getFileName()), "ファイルが無い"));
            }
        }
        jars.sort(Comparator.comparing(f -> f.getFileName().toString()));
        List<JarScanner.JarScan> scans = new ArrayList<>();
        List<InputFile> inputs = new ArrayList<>();
        for (Path jar : jars) {
            try {
                SafeArchive a = SafeArchive.open(jar);
                inputs.add(new InputFile(a.fileName(), a.sha256(), a.size()));
                scans.add(JarScanner.scan(a));
            } catch (SafeArchive.ArchiveException e) {
                inputs.add(new InputFile(jar.getFileName().toString(), "", sizeOrZero(jar)));
                diags.add(new Diagnostic(Severity.ERROR, e.code(), jar.getFileName().toString(), e.getMessage()));
            }
        }
        return analyze(inputs, scans, diags);
    }

    static BridgeIr analyze(List<InputFile> inputs, List<JarScanner.JarScan> scans, List<Diagnostic> preDiags) {
        List<Diagnostic> diags = new ArrayList<>(preDiags);
        List<ModDescriptor> mods = new ArrayList<>();
        List<Definition> defs = new ArrayList<>();
        for (var s : scans) {
            mods.addAll(s.mods());
            defs.addAll(s.definitions());
            diags.addAll(s.diagnostics());
        }

        // 同じ MOD ID を持つ JAR が複数ある
        Map<String, List<String>> byId = new TreeMap<>();
        mods.forEach(m -> byId.computeIfAbsent(m.id(), k -> new ArrayList<>()).add(m.sourceFile()));
        byId.forEach((id, files) -> {
            if (files.size() > 1) {
                diags.add(new Diagnostic(Severity.ERROR, "DUPLICATE_MOD_ID", id, "同じ MOD ID の JAR が複数ある: " + String.join(", ", new TreeSet<>(files))));
            }
        });

        // 別々の MOD が同じ識別子を定義している
        Map<String, Set<String>> defOwners = new TreeMap<>();
        defs.forEach(d -> defOwners.computeIfAbsent(d.kind() + " " + d.id(), k -> new TreeSet<>()).add(d.modId()));
        defOwners.forEach((key, owners) -> {
            if (owners.size() > 1) {
                diags.add(new Diagnostic(Severity.ERROR, "DUPLICATE_DEFINITION", key.substring(key.indexOf(' ') + 1),
                        key.substring(0, key.indexOf(' ')) + " を複数の MOD が定義している: " + String.join(", ", owners)));
            }
        });

        // 依存の欠落
        Set<String> available = new TreeSet<>(BUILTIN_IDS);
        mods.forEach(m -> {
            available.add(m.id());
            available.addAll(m.provides());
        });
        for (ModDescriptor m : mods) {
            for (var dep : m.depends().entrySet()) {
                if (!available.contains(dep.getKey())) {
                    diags.add(new Diagnostic(Severity.ERROR, "DEPENDENCY_MISSING", m.id(),
                            "依存先 " + dep.getKey() + " (" + dep.getValue() + ") が入力に無い"));
                }
            }
            String mc = m.depends().get("minecraft");
            if (mc != null) {
                diags.add(new Diagnostic(Severity.INFO, "TARGET_MINECRAFT", m.id(),
                        "minecraft " + mc + " を要求（本エンジンの対象は " + TargetVersions.get("minecraft") + "。範囲の評価は未実装）"));
            }
        }

        // 依存の循環（Fabric は循環を許すが、読込順や初期化順の問題の原因になるので警告する）
        for (List<String> cycle : findCycles(mods)) {
            diags.add(new Diagnostic(Severity.WARN, "DEPENDENCY_CYCLE", cycle.get(0), "依存が循環している: " + String.join(" -> ", cycle) + " -> " + cycle.get(0)));
        }

        List<FeaturePlan> plans = plan(mods, defs, diags);

        return new BridgeIr(BridgeIr.SCHEMA, BridgeIr.SCHEMA_VERSION, "modbridge " + TargetVersions.get("modbridge"),
                sorted(inputs, Comparator.comparing(InputFile::name).thenComparing(InputFile::sha256)),
                sorted(mods, Comparator.comparing(ModDescriptor::id).thenComparing(ModDescriptor::sourceFile)),
                sorted(defs, Comparator.comparing(Definition::id).thenComparing(Definition::kind).thenComparing(Definition::modId)),
                sorted(new ArrayList<>(new LinkedHashSet<>(diags)), Comparator.comparing(Diagnostic::severity)
                        .thenComparing(Diagnostic::code).thenComparing(Diagnostic::subject).thenComparing(Diagnostic::message)),
                sorted(plans, Comparator.comparing(FeaturePlan::subject).thenComparing(FeaturePlan::mode)));
    }

    /**
     * 方式の計画。検証の記録がまだ無いので、状態は全て UNTESTED にする（SUPPORTED にはしない）。
     * 重大な問題（ERROR 診断）がある対象は fail closed として UNSUPPORTED / ERROR にする。
     */
    static List<FeaturePlan> plan(List<ModDescriptor> mods, List<Definition> defs, List<Diagnostic> diags) {
        Set<String> errorSubjects = new TreeSet<>();
        Map<String, Set<String>> warnSubjects = new HashMap<>();
        for (Diagnostic d : diags) {
            if (d.severity() == Severity.ERROR) {
                errorSubjects.add(d.subject());
            } else if (d.severity() == Severity.WARN) {
                warnSubjects.computeIfAbsent(d.subject(), k -> new TreeSet<>()).add(d.code());
            }
        }
        List<FeaturePlan> plans = new ArrayList<>();
        for (ModDescriptor m : mods) {
            String subject = "mod:" + m.id();
            if (errorSubjects.contains(m.id())) {
                plans.add(new FeaturePlan(subject, Mode.A, Method.UNSUPPORTED, Status.ERROR, "重大な診断がある（fail closed）"));
                plans.add(new FeaturePlan(subject, Mode.B, Method.UNSUPPORTED, Status.ERROR, "重大な診断がある（fail closed）"));
                continue;
            }
            plans.add(new FeaturePlan(subject, Mode.A, Method.AUTO, Status.UNTESTED, "Java サーバー上で元の MOD のコードを実行する"));
            boolean hasCode = !m.entrypoints().isEmpty() || !m.mixinConfigs().isEmpty();
            plans.add(hasCode
                    ? new FeaturePlan(subject, Mode.B, Method.UNSUPPORTED, Status.UNTESTED, "Java コード（entrypoint / Mixin）の動作は自動変換しない。宣言データのみ対象")
                    : new FeaturePlan(subject, Mode.B, Method.AUTO, Status.UNTESTED, "コードを持たないデータ MOD"));
        }
        for (Definition d : defs) {
            String subject = d.kind().name().toLowerCase() + ":" + d.id();
            if (errorSubjects.contains(d.id()) || errorSubjects.contains(d.modId())) {
                plans.add(new FeaturePlan(subject, Mode.A, Method.UNSUPPORTED, Status.ERROR, "重大な診断がある（fail closed）"));
                plans.add(new FeaturePlan(subject, Mode.B, Method.UNSUPPORTED, Status.ERROR, "重大な診断がある（fail closed）"));
                continue;
            }
            Set<String> warns = warnSubjects.getOrDefault(d.id(), Set.of());
            String note = warns.isEmpty() ? "" : "（警告: " + String.join(", ", warns) + "）";
            switch (d.kind()) {
                case ITEM, BLOCK -> {
                    plans.add(new FeaturePlan(subject, Mode.A, Method.AUTO, Status.UNTESTED, "Geyser + Hydraulic のカスタム定義とパック変換" + note));
                    plans.add(new FeaturePlan(subject, Mode.B, Method.AUTO, Status.UNTESTED, "素材から RP/BP を生成（v0.3.0）" + note));
                }
                case RECIPE -> {
                    plans.add(new FeaturePlan(subject, Mode.A, Method.AUTO, Status.UNTESTED, "Java サーバーがレシピを処理する"));
                    plans.add(new FeaturePlan(subject, Mode.B, Method.RULE, Status.UNTESTED, "レシピ type 別の変換規則（v0.4.0）: " + d.attributes().getOrDefault("type", "不明")));
                }
                default -> throw new IllegalStateException(d.kind().toString());
            }
        }
        return plans;
    }

    /** 強連結成分のうち 2 要素以上のもの（と自己依存）を循環として返す。各循環は辞書順最小の ID から始める。 */
    static List<List<String>> findCycles(List<ModDescriptor> mods) {
        Map<String, Set<String>> graph = new TreeMap<>();
        Set<String> ids = new TreeSet<>();
        mods.forEach(m -> ids.add(m.id()));
        mods.forEach(m -> graph.computeIfAbsent(m.id(), k -> new TreeSet<>())
                .addAll(m.depends().keySet().stream().filter(ids::contains).toList()));
        // Tarjan
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new TreeSet<>();
        List<List<String>> out = new ArrayList<>();
        int[] counter = {0};
        for (String v : graph.keySet()) {
            if (!index.containsKey(v)) {
                strongConnect(v, graph, index, low, stack, onStack, out, counter);
            }
        }
        out.sort(Comparator.comparing(c -> c.get(0)));
        return out;
    }

    private static void strongConnect(String v, Map<String, Set<String>> g, Map<String, Integer> index, Map<String, Integer> low,
            Deque<String> stack, Set<String> onStack, List<List<String>> out, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]++);
        stack.push(v);
        onStack.add(v);
        for (String w : g.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, g, index, low, stack, onStack, out, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> comp = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.add(w);
            } while (!w.equals(v));
            if (comp.size() > 1 || g.getOrDefault(v, Set.of()).contains(v)) {
                comp.sort(null);
                out.add(List.copyOf(comp));
            }
        }
    }

    private static <T> List<T> sorted(List<T> list, Comparator<T> c) {
        List<T> copy = new ArrayList<>(list);
        copy.sort(c);
        return List.copyOf(copy);
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
