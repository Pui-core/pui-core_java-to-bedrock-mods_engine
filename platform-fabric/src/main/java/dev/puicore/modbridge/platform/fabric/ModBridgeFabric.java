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
            ServerLifecycleEvents.SERVER_STARTING.register(server -> {
                try {
                    if (Boolean.getBoolean("modbridge.gate.testFailRegistration")) {
                        throw new IllegalStateException("試験用: 登録の失敗を起こした（-Dmodbridge.gate.testFailRegistration）");
                    }
                    GeyserGate.register(DECISION);
                } catch (Throwable e) { // Geyser の版違いによる LinkageError なども含める
                    if (policy == Policy.STRICT) {
                        // 断れないまま Bedrock を受け付けることになるので、起動を中断する（fail closed）。
                        // halt では止まるまでに Geyser の待受（SERVER_STARTED）が始まりうるので、例外で起動処理ごと止める
                        LOG.error("[modbridge] Geyser の接続ゲートを登録できないため、サーバーの起動を中断する。"
                                + "Bedrock を制限せずに起動するには -Dmodbridge.gate=permissive か off を付ける", e);
                        throw new IllegalStateException("modbridge: 接続ゲートを登録できない（strict）", e);
                    } else {
                        LOG.error("[modbridge] Geyser の接続ゲートを登録できない（permissive のため起動は続ける。Bedrock の接続は制限されない）", e);
                    }
                }
            });
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
            Path mods = game.resolve("mods").toAbsolutePath().normalize();
            BridgeIr ir = Analyzer.analyzePaths(List.of(mods), rt);
            Decision d = ConnectionGate.decide(ir, policy, notAnalyzed(mods));
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
        } catch (Throwable e) { // Gson の版違いによる NoSuchMethodError なども含める
            // 判定できなければ pending のまま（STRICT・PERMISSIVE とも Bedrock を断る）
            LOG.error("[modbridge] 接続ゲートの解析に失敗した。Bedrock の接続は断ったままにする", e);
        }
    }

    /**
     * 読み込まれているのに、解析した {@code mods/} の直下に無い MOD（JVM 引数で足したもの、サブフォルダーのものなど）。
     * 同梱 JAR の MOD と、ファイルから読み込まれていないもの（Minecraft 本体・Java・Fabric Loader）は除く。
     */
    static final java.util.Set<String> LOADER_MODS = java.util.Set.of("minecraft", "java", "fabricloader");

    static List<String> notAnalyzed(Path mods) {
        List<String> out = new java.util.ArrayList<>();
        for (var c : FabricLoader.getInstance().getAllMods()) {
            // 組み込み（minecraft・java）と Fabric Loader 自身は、mods/ の外にあるのが正常
            if ("builtin".equals(c.getMetadata().getType()) || LOADER_MODS.contains(c.getMetadata().getId()) || c.getContainingMod().isPresent()
                    || c.getOrigin().getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH) {
                continue;
            }
            for (Path p : c.getOrigin().getPaths()) {
                Path abs = p.toAbsolutePath().normalize();
                if (!mods.equals(abs.getParent())) {
                    out.add("MOD_NOT_ANALYZED " + c.getMetadata().getId() + "（mods/ の直下に無い: " + abs.getFileName() + "）");
                }
            }
        }
        out.sort(null);
        return out;
    }
}
