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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PuiModBridge の Fabric 専用サーバー用の入口。
 *
 * <ul>
 *   <li>実行時レジストリの取得: {@code -Dmodbridge.registryDump=<file>}（{@link RegistryDump}）</li>
 *   <li>Mode A の起動検査: {@code -Dmodbridge.gate=strict|permissive|off}（既定 strict）。
 *       strict ではワールドと待受を初期化する前に MOD 構成を検査し、重大な非対応があれば起動を中断する</li>
 * </ul>
 */
public final class ModBridgeFabric implements DedicatedServerModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("modbridge");
    private static final Policy POLICY = Policy.parse(System.getProperty("modbridge.gate"));

    /** 現在の判定。Geyser の接続イベント（別スレッド）から読む。 */
    static final AtomicReference<Decision> DECISION = new AtomicReference<>();

    @Override
    public void onInitializeServer() {
        Policy policy = POLICY;
        DECISION.set(Decision.pending(policy));
        String dumpTarget = System.getProperty("modbridge.registryDump");
        boolean stop = Boolean.getBoolean("modbridge.registryDump.stop");
        boolean geyser = FabricLoader.getInstance().isModLoaded("geyser-fabric");

        boolean permissive = policy == Policy.PERMISSIVE && geyser;
        if (permissive) {
            ServerLifecycleEvents.SERVER_STARTING.register(server -> registerGate(policy));
        }
        LOG.info("[modbridge] gate policy={} geyser={}", policy, geyser);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean writeDump = dumpTarget != null && !dumpTarget.isBlank();
            if (writeDump || permissive) {
                String json = collectRegistry(server);
                if (writeDump && json != null) {
                    RegistryDump.write(Path.of(dumpTarget), json);
                }
                if (permissive) {
                    // 警告のみの構成では起動を待たせず、解析中は Bedrock の接続を保留する。
                    Thread worker = new Thread(() -> analyzePermissive(json), "modbridge-gate");
                    worker.setDaemon(true);
                    worker.start();
                }
            }
            if (stop) {
                LOG.info("[modbridge] stopping server after registry dump");
                server.halt(false);
            }
        });
    }

    private static void registerGate(Policy policy) {
        try {
            if (Boolean.getBoolean("modbridge.gate.testFailRegistration")) {
                throw new IllegalStateException("試験用: 登録の失敗を起こした（-Dmodbridge.gate.testFailRegistration）");
            }
            GeyserGate.register(DECISION);
        } catch (Throwable e) { // Geyser の版違いによる LinkageError なども含める
            if (policy == Policy.STRICT) {
                throw new IllegalStateException("modbridge: Geyser の接続ゲートを登録できないため起動を中断します（strict）", e);
            }
            LOG.error("[modbridge] Geyser の接続ゲートを登録できない（permissive のため起動は続ける。Bedrock の接続は制限されない）", e);
        }
    }

    /** 専用サーバーの最初のワールド保存より前に、読み込み済みのデータパックで strict の判定を行う。 */
    public static void checkStartup(RecipeManager recipes) {
        if (POLICY != Policy.STRICT || !FabricLoader.getInstance().isModLoaded("geyser-fabric")) {
            return;
        }
        registerGate(POLICY);
        Decision decision;
        try {
            decision = analyze(POLICY, RegistryDump.toJson(recipes));
        } catch (Throwable e) { // レジストリや解析ライブラリが使えない場合も起動を中断する
            throw new IllegalStateException("modbridge: MOD 構成を検証できないため起動を中断します（strict）", e);
        }
        if (!decision.allowBedrock()) {
            throw new IllegalStateException("modbridge: 重大な非対応が " + decision.total()
                    + " 件あるため起動を中断します（strict）。" + String.join(", ", decision.reasons()));
        }
        DECISION.set(decision);
    }

    private static String collectRegistry(MinecraftServer server) {
        try {
            return RegistryDump.toJson(server.getRecipeManager());
        } catch (RuntimeException e) {
            LOG.error("[modbridge] registry collection failed", e);
            return null;
        }
    }

    private static void analyzePermissive(String registryJson) {
        try {
            DECISION.set(analyze(Policy.PERMISSIVE, registryJson));
        } catch (Throwable e) { // Gson の版違いによる NoSuchMethodError なども含める
            // 判定できなければ pending のまま
            LOG.error("[modbridge] 接続ゲートの解析に失敗した。Bedrock の接続は断ったままにする", e);
        }
    }

    private static Decision analyze(Policy policy, String registryJson) {
        FabricLoader loader = FabricLoader.getInstance();
        RuntimeRegistry registry = registryJson == null ? null : RuntimeRegistry.fromJson("runtime-registry", registryJson);
        Path mods = loader.getGameDir().resolve("mods").toAbsolutePath().normalize();
        BridgeIr ir = Analyzer.analyzePaths(List.of(mods), registry);
        Decision decision = ConnectionGate.decide(ir, policy, notAnalyzed(mods));
        Path report = loader.getConfigDir().resolve("modbridge").resolve("gate-report.json");
        RegistryDump.write(report, IrJson.write(ir));
        LOG.info("[modbridge] gate decision: allowBedrock={} policy={} critical={} (report: config/modbridge/gate-report.json)",
                decision.allowBedrock(), decision.policy(), decision.total());
        decision.reasons().forEach(reason -> LOG.warn("[modbridge] gate reason: {}", reason));
        return decision;
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
