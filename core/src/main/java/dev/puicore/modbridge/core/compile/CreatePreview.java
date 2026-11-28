package dev.puicore.modbridge.core.compile;

import com.google.gson.GsonBuilder;
import dev.puicore.modbridge.core.TargetVersions;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.BridgeIr.Definition;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.ModDescriptor;
import dev.puicore.modbridge.core.scan.JarScanner;
import dev.puicore.modbridge.core.scan.SafeArchive;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Create の静的素材を小さな固定範囲で検証し、稼働させる必要がある段階を区別する。 */
public final class CreatePreview {
    private static final List<Sample> STATIC = List.of(
            new Sample("andesite_alloy", Kind.ITEM), new Sample("brass_ingot", Kind.ITEM),
            new Sample("andesite_casing", Kind.BLOCK), new Sample("brass_casing", Kind.BLOCK));

    private record Sample(String name, Kind kind) {
        String id() {
            return "create:" + name;
        }

        String asset() {
            return "assets/create/" + (kind == Kind.ITEM ? "models/item/" : "blockstates/") + name + ".json";
        }
    }

    public record Stage(String feature, List<String> evidence, String modeA, String modeB) {}
    public record Report(String input, String sha256, String createVersion, BridgeIr.Loader loader, String declaredMinecraft,
            String bridgeTargetMinecraft, String modeARuntime, List<Stage> stages) {}

    private final Path path;
    private final SafeArchive archive;
    private final ModDescriptor mod;

    private CreatePreview(Path path, SafeArchive archive, ModDescriptor mod) {
        this.path = path;
        this.archive = archive;
        this.mod = mod;
    }

    public static CreatePreview read(Path path) throws SafeArchive.ArchiveException {
        var archive = SafeArchive.open(path);
        var scan = JarScanner.scan(archive);
        var error = scan.diagnostics().stream().filter(diagnostic -> diagnostic.severity() == BridgeIr.Severity.ERROR).findFirst();
        if (error.isPresent()) {
            var diagnostic = error.get();
            throw new IllegalArgumentException(diagnostic.code() + ": " + diagnostic.message());
        }
        var mod = scan.mods().stream().filter(candidate -> candidate.id().equals("create")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Create の MOD メタデータがある JAR を指定する"));
        return new CreatePreview(path, archive, mod);
    }

    public Report report() {
        String target = TargetVersions.get("minecraft");
        String minecraft = mod.depends().getOrDefault("minecraft", "UNKNOWN");
        List<String> assets = STATIC.stream().map(Sample::asset).filter(archive::has).toList();
        List<Stage> stages = List.of(
                new Stage("static_materials", assets, "GEYSER_MAPPING_UNTESTED",
                        assets.isEmpty() ? "NO_STATIC_ASSETS_FOUND" : "STATIC_ADDON_PREVIEW"),
                stage("shafts_and_gears", "KINETIC_SYNC_REQUIRED", "KINETIC_LOGIC_NOT_IMPLEMENTED", "shaft", "cogwheel", "large_cogwheel"),
                stage("machines", "SERVER_ADAPTER_REQUIRED", "CREATE_PROCESSING_NOT_IMPLEMENTED", "mechanical_press", "mechanical_mixer", "depot"),
                stage("conveyors", "TRACKING_AND_TRANSPORT_REQUIRED", "CREATE_TRANSPORT_NOT_IMPLEMENTED", "belt"),
                stage("moving_structures_and_trains", "SEPARATE_FEASIBILITY_REQUIRED", "SEPARATE_FEASIBILITY_REQUIRED",
                        "mechanical_bearing", "track", "contraption_controls"));
        return new Report(archive.fileName(), archive.sha256(), mod.version(), mod.loader(), minecraft, target,
                runtime(minecraft, target), stages);
    }

    private String runtime(String minecraft, String target) {
        if (mod.loader() != BridgeIr.Loader.FABRIC) {
            return "LOADER_MISMATCH";
        }
        if (minecraft.matches("[0-9]+(?:\\.[0-9]+)+")) {
            return target.equals(minecraft) ? "DECLARED_TARGET_MATCH_UNTESTED" : "MINECRAFT_VERSION_MISMATCH";
        }
        return "MINECRAFT_REQUIREMENT_UNVERIFIED";
    }

    private Stage stage(String feature, String modeA, String modeB, String... blocks) {
        List<String> evidence = new ArrayList<>();
        for (String name : blocks) {
            String file = "assets/create/blockstates/" + name + ".json";
            if (archive.has(file)) {
                evidence.add(file);
            }
        }
        return new Stage(feature, List.copyOf(evidence), modeA, modeB);
    }

    /** ワールドを必要としない静的素材の試作。入力 JAR を稼働用 MOD 一式として扱わない。 */
    public PackCompiler.Result compile() {
        List<Definition> definitions = new ArrayList<>();
        List<BridgeIr.FeaturePlan> plans = new ArrayList<>();
        for (Sample sample : STATIC) {
            if (!archive.has(sample.asset())) {
                continue;
            }
            definitions.add(new Definition(sample.id(), sample.kind(), mod.id(), BridgeIr.Source.STATIC_ASSET,
                    List.of(sample.asset()), List.of(sample.asset()), Map.of()));
            plans.add(new BridgeIr.FeaturePlan(sample.kind().name().toLowerCase(java.util.Locale.ROOT) + ":" + sample.id(),
                    BridgeIr.Mode.B, BridgeIr.Method.AUTO, BridgeIr.Status.UNTESTED, "Create の静的プレビュー"));
        }
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("Create の対象素材が JAR に無い");
        }
        var input = new BridgeIr.InputFile(archive.fileName(), archive.sha256(), archive.size());
        var ir = new BridgeIr(BridgeIr.SCHEMA, BridgeIr.SCHEMA_VERSION, "create-preview", List.of(input),
                List.of(mod), definitions, List.of(), plans);
        return PackCompiler.compileStaticPreview(ir, Map.of(archive.fileName(), path),
                new PackCompiler.Settings("Create static preview", "modbridge_create_preview"));
    }

    public static String json(Report value) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value);
    }
}
