package dev.puicore.modbridge.platform.fabric;

import dev.puicore.modbridge.core.gate.ConnectionGate.Decision;
import java.util.concurrent.atomic.AtomicReference;
import org.geysermc.event.PostOrder;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;

/**
 * Geyser の接続イベントに判定をつなぐ。Geyser が入っているときだけ読み込む（Geyser のクラスを参照するため）。
 *
 * <p>{@link SessionLoginEvent} は、Bedrock のプレイヤーが Java サーバーに入る直前に呼ばれる。ここで断れば、
 * そのプレイヤーはワールドに入らないので、ワールドは変更されない。
 *
 * <p>ほかの拡張が後から取り消しを外さないよう、最後（{@link PostOrder#LAST}）に呼ばれるように登録し、
 * 断るときは毎回取り消し直す。
 */
final class GeyserGate {
    private GeyserGate() {}

    /** 登録する。登録できなければ例外を投げる（呼出し側が方針に沿って扱う）。 */
    static void register(AtomicReference<Decision> decision) {
        GeyserApi api = GeyserApi.api();
        if (api == null) {
            throw new IllegalStateException("Geyser API が null");
        }
        api.eventBus().subscribe(EventRegistrar.of(GeyserGate.class), SessionLoginEvent.class, event -> {
            Decision d = decision.get();
            if (d == null || !d.allowBedrock()) {
                event.setCancelled(true, d == null ? "Server is starting" : d.message());
                ModBridgeFabric.LOG.info("[modbridge] gate: Bedrock の接続を断った（{}）", d == null ? "pending" : d.policy());
            }
        }, PostOrder.LAST);
        ModBridgeFabric.LOG.info("[modbridge] gate: Geyser の SessionLoginEvent に登録した（PostOrder.LAST）");
    }
}
