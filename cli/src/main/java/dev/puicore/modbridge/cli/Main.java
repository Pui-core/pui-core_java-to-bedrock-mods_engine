package dev.puicore.modbridge.cli;

import dev.puicore.modbridge.core.Doctor;
import dev.puicore.modbridge.core.bds.BdsInstaller;
import dev.puicore.modbridge.core.compile.PackCompiler;
import dev.puicore.modbridge.core.compile.PackPublisher;
import dev.puicore.modbridge.core.TargetVersions;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.FeaturePlan;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.IrJson;
import dev.puicore.modbridge.core.scan.Analyzer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * modbridge CLI。
 *
 * <p>終了コード: 0=成功（ERROR 診断なし）, 1=ERROR 診断あり（入力 JAR が読めない場合も、診断 IO_ERROR としてここに含む）・
 * 不正な IR, 2=使い方の誤り, 3=IR の書き出しと IR ファイルの読込の失敗。
 */
public final class Main {
    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;
    static final int IO = 3;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            usage(err);
            return USAGE;
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        return switch (args[0]) {
            case "help", "--help" -> {
                usage(out);
                yield OK;
            }
            case "doctor" -> doctor(out);
            case "version", "--version" -> {
                out.println("modbridge " + TargetVersions.get("modbridge"));
                TargetVersions.all().forEach((k, v) -> out.println("  " + k + " = " + v));
                yield OK;
            }
            case "scan" -> scan(rest, out, err, true);
            case "validate" -> scan(rest, out, err, false);
            case "report" -> report(rest, out, err);
            case "compile" -> compile(rest, out, err);
            case "bds-install" -> bdsInstall(rest, out, err);
            case "bds-rollback" -> bdsRollback(rest, out, err);
            default -> {
                err.println("不明なコマンド: " + args[0]);
                usage(err);
                yield USAGE;
            }
        };
    }

    /** scan: IR を出力する。validate: 診断だけを表示する。どちらも ERROR 診断があれば終了コード 1。 */
    private static int scan(List<String> args, PrintStream out, PrintStream err, boolean writeIr) {
        List<Path> inputs = new ArrayList<>();
        Path outFile = null;
        Path runtime = null;
        boolean onlyFiles = false;
        try {
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                if (!onlyFiles && a.equals("--")) {
                    onlyFiles = true; // 以降は「-」で始まる名前もファイルとして扱う
                } else if (!onlyFiles && writeIr && (a.equals("--out") || a.equals("-o"))) {
                    if (i + 1 >= args.size()) {
                        err.println("--out にはファイル名が必要");
                        return USAGE;
                    }
                    if (outFile != null) {
                        err.println("--out は 1 回だけ指定する");
                        return USAGE;
                    }
                    outFile = Path.of(args.get(++i));
                } else if (!onlyFiles && a.equals("--runtime")) {
                    if (i + 1 >= args.size()) {
                        err.println("--runtime には実行時レジストリのファイル名が必要");
                        return USAGE;
                    }
                    if (runtime != null) {
                        err.println("--runtime は 1 回だけ指定する");
                        return USAGE;
                    }
                    runtime = Path.of(args.get(++i));
                } else if (!onlyFiles && a.startsWith("-")) {
                    err.println("不明なオプション: " + a);
                    return USAGE;
                } else {
                    inputs.add(Path.of(a));
                }
            }
        } catch (InvalidPathException e) {
            err.println("パスとして使えない: " + e.getInput());
            return USAGE;
        }
        if (inputs.isEmpty()) {
            err.println("JAR ファイルか、JAR を含むフォルダーを 1 つ以上指定する");
            return USAGE;
        }
        BridgeIr ir = Analyzer.analyzePaths(inputs, runtime);
        if (writeIr) {
            String json = IrJson.write(ir);
            if (outFile == null) {
                out.print(json);
            } else {
                try {
                    writeAtomically(outFile, json);
                } catch (IOException e) {
                    err.println("書き込めない: " + outFile + " (" + e.getMessage() + ")");
                    return IO;
                }
                printDiagnostics(ir, out);
                out.println("IR を書き出した: " + outFile);
            }
        } else {
            printDiagnostics(ir, out);
        }
        return hasErrors(ir) ? FAILED : OK;
    }

    /** compile: 解析してからリソースパックと対応表を作り、出力フォルダーに公開する。 */
    private static int compile(List<String> args, PrintStream out, PrintStream err) {
        List<Path> inputs = new ArrayList<>();
        Map<String, String> opts = new TreeMap<>();
        boolean onlyFiles = false;
        try {
            for (int i = 0; i < args.size(); i++) {
                String a = args.get(i);
                if (!onlyFiles && a.equals("--")) {
                    onlyFiles = true;
                } else if (!onlyFiles && List.of("--out", "--runtime", "--cache", "--pack-name", "--pack-id").contains(a)) {
                    if (i + 1 >= args.size()) {
                        err.println(a + " には値が必要");
                        return USAGE;
                    }
                    if (opts.put(a, args.get(++i)) != null) {
                        err.println(a + " は 1 回だけ指定する");
                        return USAGE;
                    }
                } else if (!onlyFiles && a.startsWith("-")) {
                    err.println("不明なオプション: " + a);
                    return USAGE;
                } else {
                    inputs.add(Path.of(a));
                }
            }
            if (inputs.isEmpty() || !opts.containsKey("--out")) {
                err.println("使い方: modbridge compile <jar|dir>... --out DIR [--runtime FILE] [--cache DIR] [--pack-name NAME] [--pack-id ID]");
                return USAGE;
            }
            PackCompiler.Settings settings;
            try {
                settings = new PackCompiler.Settings(opts.getOrDefault("--pack-name", PackCompiler.Settings.DEFAULT.packName()),
                        opts.getOrDefault("--pack-id", PackCompiler.Settings.DEFAULT.packId()));
            } catch (IllegalArgumentException e) {
                err.println(e.getMessage());
                return USAGE;
            }
            Path outDir = Path.of(opts.get("--out"));
            Path cache = opts.containsKey("--cache") ? Path.of(opts.get("--cache")) : null;
            Path runtime = opts.containsKey("--runtime") ? Path.of(opts.get("--runtime")) : null;

            BridgeIr ir = Analyzer.analyzePaths(inputs, runtime);
            String key = PackPublisher.cacheKey(ir, settings);
            Map<String, byte[]> files = null;
            try {
                files = cache == null ? null : PackPublisher.readCache(cache, key);
            } catch (IOException e) {
                err.println("キャッシュを読めない（作り直す）: " + e.getClass().getSimpleName());
            }
            if (files != null) {
                out.println("キャッシュを使う: " + key.substring(0, 12));
            } else {
                PackCompiler.Result r = PackCompiler.compile(ir, Analyzer.jarFiles(inputs), settings);
                printDiagnostics(ir, out);
                out.println("## 生成の診断");
                r.diagnostics().forEach(d -> out.println("[" + d.severity() + "] " + d.code() + " " + d.subject() + ": " + d.message()));
                if (!r.ok()) {
                    out.println("パックを作らなかった（出力フォルダーは変更していない）");
                    return FAILED;
                }
                files = r.outputs();
                Map<String, Long> status = new TreeMap<>();
                r.mapping().entries().forEach(e -> status.merge(e.status(), 1L, Long::sum));
                out.println("変換結果（リソースパック）: " + status);
                Map<String, Long> bpStatus = new TreeMap<>();
                r.mapping().behavior().forEach(e -> bpStatus.merge(e.kind() + " " + e.status(), 1L, Long::sum));
                out.println("変換結果（ビヘイビアパック）: " + bpStatus);
                if (cache != null) {
                    try {
                        PackPublisher.writeCache(cache, key, files);
                    } catch (IOException e) {
                        err.println("キャッシュに保存できない（公開は続ける）: " + e.getClass().getSimpleName());
                    }
                }
            }
            try {
                PackPublisher.publish(outDir, files);
            } catch (IOException e) {
                err.println("公開できない（旧成果物は残している）: " + e.getMessage());
                return IO;
            }
            out.println("公開した: " + outDir + "（" + String.join("、", PackCompiler.OUTPUT_FILES) + "）");
            return OK;
        } catch (InvalidPathException e) {
            err.println("パスとして使えない: " + e.getInput());
            return USAGE;
        }
    }

    /** 引数を「--名前 値」と「--フラグ」と位置引数に分ける。未知のオプションや重複は null。 */
    private static Map<String, String> options(List<String> args, List<String> valued, List<String> flags, List<String> positional,
            PrintStream err) {
        Map<String, String> opts = new TreeMap<>();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (valued.contains(a)) {
                if (i + 1 >= args.size()) {
                    err.println(a + " には値が必要");
                    return null;
                }
                if (opts.put(a, args.get(++i)) != null) {
                    err.println(a + " は 1 回だけ指定する");
                    return null;
                }
            } else if (flags.contains(a)) {
                opts.put(a, "true");
            } else if (a.startsWith("-")) {
                err.println("不明なオプション: " + a);
                return null;
            } else {
                positional.add(a);
            }
        }
        return opts;
    }

    /** bds-install: 既定は dry-run（計画の表示だけ）。--apply で配置する（--confirm-stopped が要る）。 */
    private static int bdsInstall(List<String> args, PrintStream out, PrintStream err) {
        List<String> pos = new ArrayList<>();
        Map<String, String> o = options(args, List.of("--bds", "--world"), List.of("--apply", "--confirm-stopped"), pos, err);
        if (o == null || pos.size() != 1 || !o.containsKey("--bds")) {
            err.println("使い方: modbridge bds-install <compile の出力フォルダー> --bds DIR [--world NAME] [--apply --confirm-stopped]");
            return USAGE;
        }
        try {
            Path bds = Path.of(o.get("--bds"));
            Path packs = Path.of(pos.get(0));
            BdsInstaller.Plan plan = BdsInstaller.plan(bds, packs, o.get("--world"));
            out.println("## 配置の計画（ワールド: " + plan.world() + "）");
            plan.actions().forEach(a -> out.println("- " + a.type() + " " + a.target() + ": " + a.detail()));
            plan.problems().forEach(p -> out.println("[ERROR] " + p));
            if (!plan.ok()) {
                return FAILED;
            }
            if (!o.containsKey("--apply")) {
                out.println("dry-run なので何も変更していない。配置するには、BDS を止めてから --apply --confirm-stopped を付ける");
                return OK;
            }
            String id = BdsInstaller.apply(bds, packs, o.get("--world"), o.containsKey("--confirm-stopped"));
            out.println("配置した。バックアップ: " + BdsInstaller.BACKUP_DIR + "/" + id + "（戻すには modbridge bds-rollback --bds DIR --backup " + id
                    + " --confirm-stopped）");
            return OK;
        } catch (BdsInstaller.InstallException e) {
            err.println(e.getMessage());
            return FAILED;
        } catch (InvalidPathException e) {
            err.println("パスとして使えない: " + e.getInput());
            return USAGE;
        }
    }

    private static int bdsRollback(List<String> args, PrintStream out, PrintStream err) {
        List<String> pos = new ArrayList<>();
        Map<String, String> o = options(args, List.of("--bds", "--backup"), List.of("--confirm-stopped"), pos, err);
        if (o == null || !pos.isEmpty() || !o.containsKey("--bds") || !o.containsKey("--backup")) {
            err.println("使い方: modbridge bds-rollback --bds DIR --backup ID --confirm-stopped");
            return USAGE;
        }
        try {
            BdsInstaller.rollback(Path.of(o.get("--bds")), o.get("--backup"), o.containsKey("--confirm-stopped"));
            out.println("バックアップ " + o.get("--backup") + " から元に戻した");
            return OK;
        } catch (BdsInstaller.InstallException e) {
            err.println(e.getMessage());
            return FAILED;
        } catch (InvalidPathException e) {
            err.println("パスとして使えない: " + e.getInput());
            return USAGE;
        }
    }

    private static int report(List<String> args, PrintStream out, PrintStream err) {
        if (args.size() != 1) {
            err.println("使い方: modbridge report <ir.json>");
            return USAGE;
        }
        BridgeIr ir;
        try {
            ir = IrJson.read(Files.readString(Path.of(args.get(0)), StandardCharsets.UTF_8));
        } catch (InvalidPathException e) {
            err.println("パスとして使えない: " + e.getInput());
            return USAGE;
        } catch (IOException e) {
            err.println("読めない: " + args.get(0) + " (" + e.getMessage() + ")");
            return IO;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return FAILED;
        }
        out.println("# 互換性レポート（" + ir.generator() + "、IR スキーマ " + ir.schemaVersion() + "）");
        out.println();
        out.println("## 入力");
        ir.inputs().forEach(f -> out.println("- " + f.name() + "（" + f.size() + " バイト、sha256 " + abbreviate(f.sha256()) + "）"));
        out.println();
        out.println("## MOD");
        ir.mods().forEach(m -> out.println("- " + m.id() + " " + m.version() + "（" + m.loader() + "、依存 " + m.depends().keySet() + "）"));
        out.println();
        Map<String, Long> kinds = new TreeMap<>();
        ir.definitions().forEach(d -> kinds.merge(d.kind().name() + "（" + sourceLabel(d) + "）", 1L, Long::sum));
        out.println("## 定義");
        kinds.forEach((k, n) -> out.println("- " + k + ": " + n));
        out.println();
        out.println("## 方式と検証状態");
        out.println("| 対象 | モード | 方式 | 状態 | 理由 |");
        out.println("|---|---|---|---|---|");
        for (FeaturePlan p : ir.plans()) {
            out.println("| " + p.subject() + " | " + p.mode() + " | " + p.method() + " | " + p.status() + " | " + p.reason() + " |");
        }
        out.println();
        printDiagnostics(ir, out);
        return hasErrors(ir) ? FAILED : OK;
    }

    private static void printDiagnostics(BridgeIr ir, PrintStream out) {
        Map<Severity, Long> count = new TreeMap<>();
        ir.diagnostics().forEach(d -> count.merge(d.severity(), 1L, Long::sum));
        out.println("## 診断: ERROR " + count.getOrDefault(Severity.ERROR, 0L) + " / WARN " + count.getOrDefault(Severity.WARN, 0L)
                + " / INFO " + count.getOrDefault(Severity.INFO, 0L));
        for (Diagnostic d : ir.diagnostics()) {
            out.println("[" + d.severity() + "] " + d.code() + " " + d.subject() + ": " + d.message());
        }
    }

    private static boolean hasErrors(BridgeIr ir) {
        return ir.diagnostics().stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    /** 一時ファイルに書いてから置き換える。途中で失敗しても既存のファイルは壊さない。 */
    static void writeAtomically(Path target, String content) throws IOException {
        Path abs = target.toAbsolutePath();
        Path dir = abs.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + abs.getFileName(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sourceLabel(BridgeIr.Definition d) {
        boolean runtime = "registered".equals(d.attributes().get("runtime"));
        return switch (d.source()) {
            case RUNTIME_REGISTRY -> "実行時のみ";
            case STATIC_ASSET -> runtime ? "静的・実行時の両方" : "静的解析のみ";
        };
    }

    private static String abbreviate(String sha) {
        return sha.length() > 12 ? sha.substring(0, 12) + "…" : (sha.isEmpty() ? "-" : sha);
    }

    private static int doctor(PrintStream out) {
        Doctor.Result result = Doctor.run();
        for (Doctor.Check c : result.checks()) {
            out.printf("[%s] %s: %s%n", c.level(), c.id(), c.message());
        }
        return result.failed() ? FAILED : OK;
    }

    private static void usage(PrintStream out) {
        out.println("""
                使い方: modbridge <command>
                  doctor                          実行環境と対象バージョンを点検する
                  version                         対象バージョンを表示する
                  scan <jar|dir>... [--runtime FILE] [--out FILE]
                                                  JAR を静的に解析し、Bridge IR（JSON）を出力する。
                                                  --runtime で実行時レジストリ（platform-fabric の出力）と突き合わせる
                  validate <jar|dir>... [--runtime FILE]
                                                  解析して診断だけを表示する
                  report <ir.json>                IR から互換性レポートを表示する
                  compile <jar|dir>... --out DIR [--runtime FILE] [--cache DIR] [--pack-name NAME] [--pack-id ID]
                                                  Bedrock のアドオン（RP・BP・.mcaddon）と対応表を作り、DIR に公開する
                  bds-install <DIR> --bds BDS [--world NAME] [--apply --confirm-stopped]
                                                  compile の出力を BDS のワールドに配置する（既定は dry-run）
                  bds-rollback --bds BDS --backup ID --confirm-stopped
                                                  bds-install のバックアップから元に戻す
                  （-- の後ろは「-」で始まる名前もファイルとして扱う）
                終了コード: 0=成功, 1=ERROR 診断あり（読めない JAR を含む）・不正な IR,
                          2=使い方の誤り, 3=IR の書き出し・IR ファイルの読込の失敗
                （compile が作るのはリソースパックだけ。単純なモデルだけに対応し、実機では未検証）""");
    }
}
