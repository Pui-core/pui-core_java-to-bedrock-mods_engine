package dev.puicore.modbridge.platform.fabric;

import dev.puicore.modbridge.core.gate.ConnectionGate;
import dev.puicore.modbridge.core.gate.ConnectionGate.Decision;
import dev.puicore.modbridge.core.gate.ConnectionGate.Policy;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.IrJson;
import dev.puicore.modbridge.core.runtime.RuntimeRegistry;
import dev.puicore.modbridge.core.scan.Analyzer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PuiModBridge の Fabric 専用サーバー用の入口。
 *
 * <ul>
 *   <li>実行時レジストリの取得: {@code -Dmodbridge.registryDump=<file>}（{@link RegistryDump}）</li>
 *   <li>Bedrock の接続ゲート: {@code -Dmodbridge.gate=strict|permissive|off}（既定 strict）。
 *       サーバーの起動後に {@code mods/} を静的解析し、実行時レジストリと突き合わせてから判定する。
 *       判定が出るまでは Bedrock の接続を断る（Java の接続には関わらない）</li>
 * </ul>
 */
public final class ModBridgeFabric implements DedicatedServerModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("modbridge");

    /** 現在の判定。Geyser の接続イベント（別スレッド）から読む。 */
    static final AtomicReference<Decision> DECISION = new AtomicReference<>();

    @Override
    public void onInitializeServer() {
        Policy policy = Policy.parse(System.getProperty("modbridge.gate"));
        DECISION.set(Decision.pending(policy));
        String dumpTarget = System.getProperty("modbridge.registryDump");
        boolean stop = Boolean.getBoolean("modbridge.registryDump.stop");
        boolean geyser = FabricLoader.getInstance().isModLoaded("geyser-fabric");

        if (policy != Policy.OFF && geyser) {
            // Geyser の初期化（MOD の初期化時）が済んだ後に登録する
            ServerLifecycleEvents.SERVER_STARTING.register(server -> GeyserGate.register(DECISION));
        }
        LOG.info("[modbridge] gate policy={} geyser={}", policy, geyser);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            String json;
            try {
                json = RegistryDump.toJson(server);
            } catch (RuntimeException e) {
                LOG.error("[modbridge] registry collection failed", e);
                json = null;
            }
            if (dumpTarget != null && !dumpTarget.isBlank() && json != null) {
                RegistryDump.write(Path.of(dumpTarget), json);
            }
            if (policy != Policy.OFF && geyser) {
                String registry = json;
                // 解析はサーバーのスレッドを止めないよう、別スレッドで行う。終わるまでは pending（Bedrock を断る）
                Thread t = new Thread(() -> analyze(policy, registry), "modbridge-gate");
                t.setDaemon(true);
                t.start();
            }
            if (stop) {
                LOG.info("[modbridge] stopping server after registry dump");
                server.halt(false);
            }
        });
    }

    static void analyze(Policy policy, String registryJson) {
        try {
            Path game = FabricLoader.getInstance().getGameDir();
            RuntimeRegistry rt = registryJson == null ? null : RuntimeRegistry.fromJson("runtime-registry", registryJson);
            BridgeIr ir = Analyzer.analyzePaths(List.of(game.resolve("mods")), rt);
            Decision d = ConnectionGate.decide(ir, policy);
            if (rt == null && policy == Policy.STRICT) {
                d = new Decision(false, policy, List.of("RUNTIME_REGISTRY_UNAVAILABLE"), 1,
                        "サーバーの構成を確認できないため、Bedrock 版の接続を受け付けません（Server configuration could not be verified）");
            }
            Path report = FabricLoader.getInstance().getConfigDir().resolve("modbridge").resolve("gate-report.json");
            RegistryDump.write(report, IrJson.write(ir));
            DECISION.set(d);
            LOG.info("[modbridge] gate decision: allowBedrock={} policy={} critical={} (report: config/modbridge/gate-report.json)",
                    d.allowBedrock(), d.policy(), d.total());
            d.reasons().forEach(r -> LOG.warn("[modbridge] gate reason: {}", r));
        } catch (RuntimeException e) {
            // 判定できなければ pending のまま（STRICT・PERMISSIVE とも Bedrock を断る）
            LOG.error("[modbridge] gate analysis failed; Bedrock connections stay blocked", e);
        }
    }
}
