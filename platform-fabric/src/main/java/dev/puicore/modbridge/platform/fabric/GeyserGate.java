package dev.puicore.modbridge.platform.fabric;

import dev.puicore.modbridge.core.gate.ConnectionGate.Decision;
import java.util.concurrent.atomic.AtomicReference;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;

/**
 * Geyser の接続イベントに判定をつなぐ。Geyser が入っているときだけ読み込む（Geyser のクラスを参照するため）。
 *
 * <p>{@link SessionLoginEvent} は、Bedrock のプレイヤーが Java サーバーに入る直前に呼ばれる。ここで断れば、
 * そのプレイヤーはワールドに入らないので、ワールドは変更されない。
 */
final class GeyserGate {
    private GeyserGate() {}

    static void register(AtomicReference<Decision> decision) {
        GeyserApi api;
        try {
            api = GeyserApi.api();
        } catch (RuntimeException | LinkageError e) {
            api = null;
        }
        if (api == null) {
            ModBridgeFabric.LOG.error("[modbridge] Geyser API が見つからないため、接続ゲートを登録できない（Bedrock の接続は制限されない）");
            return;
        }
        api.eventBus().subscribe(EventRegistrar.of(GeyserGate.class), SessionLoginEvent.class, event -> {
            Decision d = decision.get();
            if (d == null || !d.allowBedrock()) {
                event.setCancelled(true, d == null ? "Server is starting" : d.message());
                ModBridgeFabric.LOG.info("[modbridge] gate: Bedrock の接続を断った（{}）", d == null ? "pending" : d.policy());
            }
        });
        ModBridgeFabric.LOG.info("[modbridge] gate: Geyser の SessionLoginEvent に登録した");
    }
}
