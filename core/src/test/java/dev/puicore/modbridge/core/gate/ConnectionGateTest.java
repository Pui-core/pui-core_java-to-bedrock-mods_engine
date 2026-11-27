package dev.puicore.modbridge.core.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.puicore.modbridge.core.gate.ConnectionGate.Decision;
import dev.puicore.modbridge.core.gate.ConnectionGate.Policy;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.FeaturePlan;
import dev.puicore.modbridge.core.ir.BridgeIr.Method;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.BridgeIr.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionGateTest {

    private static BridgeIr ir(List<Diagnostic> diags, List<FeaturePlan> plans) {
        return new BridgeIr(BridgeIr.SCHEMA, BridgeIr.SCHEMA_VERSION, "test", List.of(), List.of(), List.of(), diags, plans);
    }

    private static final FeaturePlan OK_A = new FeaturePlan("mod:ok", Mode.A, Method.AUTO, Status.UNTESTED, "");
    private static final FeaturePlan ADAPTER_A = new FeaturePlan("item:ok:code", Mode.A, Method.ADAPTER, Status.UNTESTED, "");
    private static final FeaturePlan UNSUPPORTED_B = new FeaturePlan("mod:ok", Mode.B, Method.UNSUPPORTED, Status.UNTESTED, "");

    @Test
    void 重大な非対応が無ければ受け付ける_見た目の対応付けやModeBの非対応は理由にしない() {
        Decision d = ConnectionGate.decide(ir(List.of(new Diagnostic(Severity.WARN, "CODE_ONLY_DEFINITION", "ok:code", "")),
                List.of(OK_A, ADAPTER_A, UNSUPPORTED_B)), Policy.STRICT);
        assertTrue(d.allowBedrock());
        assertEquals(0, d.total());
    }

    @Test
    void STRICTではERROR診断かModeAの対応不可で断る() {
        Decision d = ConnectionGate.decide(ir(List.of(new Diagnostic(Severity.ERROR, "DEPENDENCY_MISSING", "bad", "")),
                List.of(new FeaturePlan("mod:bad", Mode.A, Method.UNSUPPORTED, Status.ERROR, ""))), Policy.STRICT);
        assertFalse(d.allowBedrock());
        assertEquals(2, d.total());
        assertTrue(d.reasons().contains("DEPENDENCY_MISSING bad"));
        assertTrue(d.message().contains("2 件"));
    }

    @Test
    void PERMISSIVEは受け付けて理由を残し_OFFは判定しない() {
        BridgeIr bad = ir(List.of(new Diagnostic(Severity.ERROR, "X", "y", "")), List.of());
        Decision p = ConnectionGate.decide(bad, Policy.PERMISSIVE);
        assertTrue(p.allowBedrock());
        assertEquals(1, p.total());
        Decision off = ConnectionGate.decide(bad, Policy.OFF);
        assertTrue(off.allowBedrock());
        assertEquals(0, off.total());
    }

    @Test
    void 準備中は断り_未知の方針は安全側のSTRICTにする() {
        assertFalse(Decision.pending(Policy.STRICT).allowBedrock());
        assertFalse(Decision.pending(Policy.PERMISSIVE).allowBedrock(), "解析が終わるまでは PERMISSIVE でも断る");
        assertTrue(Decision.pending(Policy.OFF).allowBedrock());
        assertEquals(Policy.STRICT, Policy.parse("strikt"));
        assertEquals(Policy.STRICT, Policy.parse(null));
        assertEquals(Policy.PERMISSIVE, Policy.parse(" Permissive "));
    }

    @Test
    void 理由の表示は上限で切る() {
        List<Diagnostic> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(new Diagnostic(Severity.ERROR, "E", "s" + i, ""));
        }
        Decision d = ConnectionGate.decide(ir(many, List.of()), Policy.STRICT);
        assertEquals(50, d.total());
        assertEquals(ConnectionGate.MAX_REASONS, d.reasons().size());
    }
}
