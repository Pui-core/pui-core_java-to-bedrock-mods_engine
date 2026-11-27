package dev.puicore.modbridge.fixture;

import dev.puicore.modbridge.core.adapter.Adapter;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import java.util.List;
import java.util.Set;

/** fixture のブロックを加工台にする最小アダプター。 */
public final class FixtureMachineAdapter implements Adapter {
    @Override
    public Descriptor descriptor() {
        return new Descriptor("pmb_fixture:machines", API_VERSION, "pmb_fixture", Set.of("0.0.4"),
                Set.of(Mode.A), Set.of(Capability.MACHINE_MENU));
    }

    @Override
    public void register(Registrar registrar) {
        var recipe = new Recipe("pmb_fixture:refine_copper", "銅インゴット2個を試験インゴット1個に加工",
                new ItemAmount("minecraft:copper_ingot", 2), new ItemAmount("pmb_fixture:test_ingot", 1));
        registrar.menu(new Menu("pmb_fixture:processing_bench", "試験用加工台", Set.of("pmb_fixture:test_block"), 5,
                List.of(recipe)));
    }
}
