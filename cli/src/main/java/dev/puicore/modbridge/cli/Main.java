package dev.puicore.modbridge.cli;

import dev.puicore.modbridge.core.Doctor;
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
 * <p>終了コード: 0=成功（ERROR 診断なし）, 1=ERROR 診断あり・診断失敗, 2=使い方の誤り, 3=入出力の失敗。
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
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (writeIr && (a.equals("--out") || a.equals("-o"))) {
                if (i + 1 >= args.size()) {
                    err.println("--out にはファイル名が必要");
                    return USAGE;
                }
                outFile = Path.of(args.get(++i));
            } else if (a.startsWith("-")) {
                err.println("不明なオプション: " + a);
                return USAGE;
            } else {
                inputs.add(Path.of(a));
            }
        }
        if (inputs.isEmpty()) {
            err.println("JAR ファイルか、JAR を含むフォルダーを 1 つ以上指定する");
            return USAGE;
        }
        BridgeIr ir = Analyzer.analyzePaths(inputs);
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

    private static int report(List<String> args, PrintStream out, PrintStream err) {
        if (args.size() != 1) {
            err.println("使い方: modbridge report <ir.json>");
            return USAGE;
        }
        BridgeIr ir;
        try {
            ir = IrJson.read(Files.readString(Path.of(args.get(0)), StandardCharsets.UTF_8));
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
        ir.definitions().forEach(d -> kinds.merge(d.kind().name(), 1L, Long::sum));
        out.println("## 定義（静的解析で推定）");
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
                  scan <jar|dir>... [--out FILE]  JAR を静的に解析し、Bridge IR（JSON）を出力する
                  validate <jar|dir>...           解析して診断だけを表示する
                  report <ir.json>                IR から互換性レポートを表示する
                終了コード: 0=成功, 1=ERROR 診断あり, 2=使い方の誤り, 3=入出力の失敗
                （compile は v0.2.0 以降で追加予定）""");
    }
}
