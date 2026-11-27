package dev.puicore.modbridge.core.gate;

import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.FeaturePlan;
import dev.puicore.modbridge.core.ir.BridgeIr.Method;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.BridgeIr.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mode A の接続ゲート。サーバーの MOD 構成を解析した IR から、Bedrock の接続を受け付けるかを決める。
 *
 * <p>重大な非対応があるとき、strict の呼び出し元はこの判定を使ってサーバーの起動を中断する。
 *
 * <ul>
 *   <li>{@link Policy#STRICT}（既定）: 重大な非対応があれば断る</li>
 *   <li>{@link Policy#PERMISSIVE}: 受け付けるが、理由を記録する</li>
 *   <li>{@link Policy#OFF}: 判定しない（解析もしない）</li>
 * </ul>
 *
 * <p>重大な非対応: ERROR 診断、または Mode A の計画が ERROR か UNSUPPORTED のもの。
 * 見た目の対応付けが要るもの（ADAPTER）は、接続を断る理由にしない（見た目が欠けるだけで、ワールドは壊さない）。
 */
public final class ConnectionGate {
    /** 判定の結果を表示する件数の上限。 */
    static final int MAX_REASONS = 20;

    public enum Policy {
        STRICT, PERMISSIVE, OFF;

        /** 文字列から読む。未知の値は STRICT（安全側）。 */
        public static Policy parse(String s) {
            if (s == null) {
                return STRICT;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "permissive" -> PERMISSIVE;
                case "off" -> OFF;
                default -> STRICT;
            };
        }
    }

    /**
     * 判定。{@code allowBedrock} が false なら、呼び出し元は起動を中断するか、接続を {@code message} で断る。
     *
     * @param reasons 重大な非対応（最大 {@link #MAX_REASONS} 件。{@code total} が全件数）
     */
    public record Decision(boolean allowBedrock, Policy policy, List<String> reasons, int total, String message) {
        /** 解析が終わる前。安全側として断る。 */
        public static Decision pending(Policy policy) {
            return new Decision(policy == Policy.OFF, policy, List.of(), 0,
                    "サーバーの準備中です。少し待ってから接続してください（Server is starting）");
        }
    }

    private ConnectionGate() {}

    public static Decision decide(BridgeIr ir, Policy policy) {
        return decide(ir, policy, List.of());
    }

    /**
     * @param extraCritical IR の外で見つかった重大な非対応（解析の対象外の場所から読み込まれた MOD など）
     */
    public static Decision decide(BridgeIr ir, Policy policy, List<String> extraCritical) {
        if (policy == Policy.OFF) {
            return new Decision(true, policy, List.of(), 0, "");
        }
        List<String> reasons = new ArrayList<>(extraCritical);
        for (Diagnostic d : ir.diagnostics()) {
            if (d.severity() == Severity.ERROR) {
                reasons.add(d.code() + " " + d.subject());
            }
        }
        for (FeaturePlan p : ir.plans()) {
            if (p.mode() == Mode.A && (p.status() == Status.ERROR || p.method() == Method.UNSUPPORTED)) {
                reasons.add("PLAN " + p.subject() + " " + p.method() + "/" + p.status());
            }
        }
        int total = reasons.size();
        List<String> shown = List.copyOf(reasons.subList(0, Math.min(total, MAX_REASONS)));
        if (total == 0) {
            return new Decision(true, policy, List.of(), 0, "");
        }
        if (policy == Policy.PERMISSIVE) {
            return new Decision(true, policy, shown, total, "");
        }
        return new Decision(false, policy, shown, total,
                "このサーバーには Bedrock 版で使えない MOD があります（" + total + " 件）"
                        + "（This server has mods not supported on Bedrock Edition）");
    }
}
