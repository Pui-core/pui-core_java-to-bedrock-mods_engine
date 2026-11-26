package dev.puicore.modbridge.cli;

import dev.puicore.modbridge.core.Doctor;
import dev.puicore.modbridge.core.TargetVersions;
import java.io.PrintStream;

/** modbridge CLI。終了コード: 0=成功, 1=診断失敗, 2=使い方の誤り。 */
public final class Main {
    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            usage(err);
            return USAGE;
        }
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
            default -> {
                err.println("不明なコマンド: " + args[0]);
                usage(err);
                yield USAGE;
            }
        };
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
                  doctor   実行環境と対象バージョンを点検する
                  version  対象バージョンを表示する
                （scan / validate / compile / report は v0.1.0 以降で追加予定）""");
    }
}
