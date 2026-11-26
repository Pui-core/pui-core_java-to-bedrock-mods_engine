package dev.puicore.modbridge.core;

import java.util.ArrayList;
import java.util.List;

/** 実行環境の前提を点検する。外部へ接続せず、ファイルも変更しない。 */
public final class Doctor {

    public enum Level { OK, WARN, FAIL }

    public record Check(String id, Level level, String message) {}

    public record Result(List<Check> checks) {
        public boolean failed() {
            return checks.stream().anyMatch(c -> c.level() == Level.FAIL);
        }
    }

    private Doctor() {}

    public static Result run() {
        return run(Runtime.version().feature(), System.getProperty("file.encoding", ""));
    }

    static Result run(int javaFeature, String fileEncoding) {
        List<Check> checks = new ArrayList<>();
        int required = TargetVersions.requiredJavaFeature();
        if (javaFeature >= required) {
            checks.add(new Check("java.version", Level.OK, "Java " + javaFeature + "（必要: " + required + " 以上）"));
        } else {
            checks.add(new Check("java.version", Level.FAIL,
                    "Java " + javaFeature + " では実行できない。Java " + required + " 以上を使う"));
        }
        if ("UTF-8".equalsIgnoreCase(fileEncoding)) {
            checks.add(new Check("file.encoding", Level.OK, "UTF-8"));
        } else {
            checks.add(new Check("file.encoding", Level.WARN,
                    "既定文字コードが " + fileEncoding + "。JSON・言語ファイルは常に UTF-8 で扱う"));
        }
        checks.add(new Check("target.minecraft", Level.OK, "Java Edition " + TargetVersions.get("minecraft")
                + " / Fabric Loader " + TargetVersions.get("fabric-loader")));
        checks.add(new Check("target.hydraulic", Level.WARN,
                "Hydraulic " + TargetVersions.get("hydraulic") + " は開発初期版。本番利用は未検証"));
        return new Result(List.copyOf(checks));
    }
}
