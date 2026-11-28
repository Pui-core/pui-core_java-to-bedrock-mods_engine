package dev.puicore.modbridge.core.compile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.puicore.modbridge.core.TargetVersions;
import dev.puicore.modbridge.core.adapter.AdapterRegistry;
import dev.puicore.modbridge.core.adapter.AdapterRules;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.BridgeIr.Definition;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.FeaturePlan;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Method;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.BridgeIr.Source;
import dev.puicore.modbridge.core.ir.BridgeIr.Status;
import dev.puicore.modbridge.core.scan.SafeArchive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bridge IR と元の JAR から、Bedrock のリソースパック（.mcpack）と対応表（mapping.json）を作る。
 *
 * <ul>
 *   <li>fail closed: IR に ERROR 診断があれば何も作らない。ID の衝突も ERROR にする</li>
 *   <li>決定的: 同じ入力・設定からは同じバイト列を作る（エントリー順と時刻を固定）</li>
 *   <li>入力の取り違え防止: JAR を開き直したら、IR に記録した SHA-256 と照合する</li>
 * </ul>
 *
 * <p>作るのはリソースパックだけ。Mode B のビヘイビアパック（アイテム・ブロックの定義）は #6 で扱う。
 */
public final class PackCompiler {
    public static final String MAPPING_SCHEMA = "modbridge.mapping";
    public static final int MAPPING_SCHEMA_VERSION = 1;
    public static final String PACK_FILE = "modbridge-rp.mcpack";
    public static final String BEHAVIOR_PACK_FILE = "modbridge-bp.mcpack";
    public static final String ADDON_FILE = "modbridge.mcaddon";
    public static final String MAPPING_FILE = "mapping.json";
    /** 公開する成果物（キャッシュもこの 4 つが揃っているときだけ使う）。 */
    public static final List<String> OUTPUT_FILES = List.of(PACK_FILE, BEHAVIOR_PACK_FILE, ADDON_FILE, MAPPING_FILE);
    /** 生成器の版。出力の形式を変えたら上げる（キャッシュの鍵に入る）。 */
    static final String COMPILER_VERSION = "pack-compiler/8";
    static final long MAX_PACK_BYTES = 256L << 20;
    static final int MAX_TEXTURES = 16_384;
    /** パックが要求する Bedrock の最低版。1.21.80 以降の書式（ブロックの geometry と material_instances）に合わせる（ADR-0006）。 */
    static final List<Integer> MIN_ENGINE_VERSION = List.of(1, 21, 80);
    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern LANG = Pattern.compile("assets/([^/]+)/lang/([a-z]{2,3})_([a-z]{2,3})\\.json");
    /** Bedrock が持つ言語（texts/languages.json に書けるもの）。 */
    static final Set<String> BEDROCK_LANGUAGES = Set.of("bg_BG", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_GB", "en_US", "es_ES",
            "es_MX", "fi_FI", "fr_CA", "fr_FR", "hu_HU", "id_ID", "it_IT", "ja_JP", "ko_KR", "nb_NO", "nl_NL", "pl_PL", "pt_BR",
            "pt_PT", "ru_RU", "sk_SK", "sv_SE", "tr_TR", "uk_UA", "zh_CN", "zh_TW");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 生成の設定。{@code packId} はヘッダーの UUID を決める（同じ ID なら、内容が変わっても同じパックとして更新される）。 */
    public record Settings(String packName, String packId, AdapterRules adapterRules) {
        public static final Settings DEFAULT = new Settings("PuiModBridge Resources", "modbridge");

        public Settings(String packName, String packId) {
            this(packName, packId, AdapterRules.EMPTY);
        }

        public Settings {
            Objects.requireNonNull(adapterRules);
            if (packName == null || packName.isBlank() || packName.length() > 64) {
                throw new IllegalArgumentException("パック名は 1〜64 文字にする");
            }
            if (packId == null || !PACK_ID.matcher(packId).matches()) {
                throw new IllegalArgumentException("パック ID は [a-z0-9_.-] の 1〜64 文字にする");
            }
        }
    }

    // ---- 対応表（mapping.json） ----

    /**
     * @param behaviorPack ビヘイビアパックの情報
     * @param behavior     ビヘイビアパックでの変換結果
     * @param notConverted 変換しない Java のロジック（MOD ごとの entrypoint・Mixin）。黙って欠落させないために記録する
     * @param diagnostics  パック生成時の診断
     */
    public record Mapping(String schema, int schemaVersion, String generator, PackInfo pack, List<Entry> entries,
            List<Texture> textures, PackInfo behaviorPack, List<BehaviorPack.Entry> behavior, List<String> notConverted,
            List<Diagnostic> diagnostics) {
        public Mapping(String schema, int schemaVersion, String generator, PackInfo pack, List<Entry> entries,
                List<Texture> textures, PackInfo behaviorPack, List<BehaviorPack.Entry> behavior, List<String> notConverted) {
            this(schema, schemaVersion, generator, pack, entries, textures, behaviorPack, behavior, notConverted, List.of());
        }
    }

    public record PackInfo(String name, String headerUuid, String moduleUuid, List<Integer> version, List<Integer> minEngineVersion,
            String contentSha256) {}

    /** 定義 1 件の変換結果。{@code status} は GENERATED / PARTIAL / SKIPPED。 */
    public record Entry(String java, String kind, String bedrock, String status, Map<String, String> textures, String render,
            List<String> notes) {}

    public record Texture(String shortName, String java, String path, int width, int height, String alpha) {}

    /** 生成の結果。{@code ok} が false のときは {@code outputs} が空で、何も公開しない。 */
    public record Result(boolean ok, List<Diagnostic> diagnostics, Map<String, byte[]> outputs, Mapping mapping) {}

    private final BridgeIr ir;
    private final Map<String, Path> jars;
    private final Settings settings;
    private final List<Diagnostic> diags = new ArrayList<>();

    private PackCompiler(BridgeIr ir, Map<String, Path> jars, Settings settings) {
        this.ir = ir;
        this.jars = jars;
        this.settings = settings;
    }

    /**
     * @param jars 入力 JAR のファイル名 → パス（IR の {@code inputs[].name} と同じ名前）
     */
    public static Result compile(BridgeIr ir, Map<String, Path> jars, Settings settings) {
        return new PackCompiler(ir, jars, settings).run();
    }

    private void diag(Severity sev, String code, String subject, String message) {
        diags.add(new Diagnostic(sev, code, subject, message));
    }

    private Result fail() {
        return new Result(false, List.copyOf(diags), Map.of(), null);
    }

    private Result run() {
        if (ir.diagnostics().stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
            diag(Severity.ERROR, "IR_HAS_ERRORS", "-", "解析に ERROR 診断があるので、パックを作らない（fail closed）");
            return fail();
        }
        Map<String, String> versions = new TreeMap<>();
        ir.mods().forEach(mod -> versions.put(mod.id(), mod.version()));
        AdapterRegistry adapters;
        try {
            adapters = AdapterRegistry.load(List.of(), Set.of(), versions, Mode.B, settings.adapterRules().adapters());
        } catch (IllegalArgumentException failure) {
            diag(Severity.ERROR, "ADAPTER_RULES_INVALID", "-", failure.getMessage());
            return fail();
        }
        try (adapters) {
            return run(adapters);
        }
    }

    private Result run(AdapterRegistry adapters) {
        List<Definition> targets = ir.definitions().stream().filter(d -> d.kind() == Kind.ITEM || d.kind() == Kind.BLOCK).toList();
        Map<String, JsonObject> recipes = new TreeMap<>();
        Map<String, Map<String, Integer>> unconvertedContent = new TreeMap<>(); // MOD → 変換しないカテゴリ → ファイル数
        Map<String, String> recipeSkips = new TreeMap<>();

        Map<String, String> identifiers = new TreeMap<>();
        Map<String, String> recipeIdentifiers = new TreeMap<>();
        for (Definition d : ir.definitions()) {
            var mapping = adapters.mapping(d.kind(), d.id());
            if (mapping.isEmpty()) {
                continue;
            }
            String destination = mapping.get();
            if (!BedrockIds.identifier(destination).equals(destination) || destination.startsWith("minecraft:")) {
                diag(Severity.ERROR, "ADAPTER_RULES_INVALID", d.id(), "Bedrock のカスタム識別子を指定する: " + destination);
                continue;
            }
            Map<String, String> selected = d.kind() == Kind.RECIPE ? recipeIdentifiers : identifiers;
            String previous = selected.putIfAbsent(d.id(), destination);
            if (previous != null && !previous.equals(destination)) {
                diag(Severity.ERROR, "ADAPTER_RULES_INVALID", d.id(), "ブロックと同名のアイテムは同じ識別子にする");
            }
        }
        // Bedrock 識別子の衝突（アイテムとブロックは同じ名前空間。同じ Java ID のアイテムとブロックはブロックアイテムなので可）
        Map<String, Set<String>> byBedrock = new TreeMap<>();
        targets.forEach(d -> byBedrock.computeIfAbsent(bedrockId(identifiers, d.id()), k -> new TreeSet<>()).add(d.id()));
        byBedrock.forEach((bid, javaIds) -> {
            if (javaIds.size() > 1) {
                diag(Severity.ERROR, "ID_COLLISION", bid, "別々の Java 識別子が同じ Bedrock 識別子になる: " + String.join(", ", javaIds));
            }
        });
        // レシピの識別子の衝突（レシピはアイテム・ブロックとは別の名前空間）
        Map<String, Set<String>> recipeIds = new TreeMap<>();
        ir.definitions().stream().filter(d -> d.kind() == Kind.RECIPE)
                .forEach(d -> recipeIds.computeIfAbsent(bedrockId(recipeIdentifiers, d.id()), k -> new TreeSet<>()).add(d.id()));
        recipeIds.forEach((bid, javaIds) -> {
            if (javaIds.size() > 1) {
                diag(Severity.ERROR, "ID_COLLISION", bid, "別々の Java のレシピが同じ Bedrock 識別子になる: " + String.join(", ", javaIds));
            }
        });
        if (!diags.isEmpty()) {
            return fail();
        }

        Map<String, String> modFile = new TreeMap<>();
        ir.mods().forEach(m -> modFile.put(m.id(), m.sourceFile()));
        Map<String, String> sha = new TreeMap<>();
        ir.inputs().forEach(i -> sha.put(i.name(), i.sha256()));
        Map<String, FeaturePlan> modeB = new TreeMap<>();
        ir.plans().stream().filter(p -> p.mode() == Mode.B).forEach(p -> modeB.put(p.subject(), p));

        Map<String, Entry> entries = new TreeMap<>();
        Map<String, Texture> textures = new TreeMap<>(); // テクスチャ ID → 情報
        Map<String, byte[]> textureBytes = new TreeMap<>();
        Set<String> itemAtlas = new TreeSet<>();
        Set<String> terrainAtlas = new TreeSet<>();
        Map<String, Map<String, String>> lang = new TreeMap<>(); // Bedrock の言語 → キー → 値

        Map<String, List<Definition>> byMod = new TreeMap<>();
        // 定義を持たない MOD（データだけの MOD など）も開き、変換しないデータを報告する
        ir.mods().forEach(m -> byMod.computeIfAbsent(m.id(), k -> new ArrayList<>()));
        ir.definitions().stream().filter(d -> d.kind() != null)
                .forEach(d -> byMod.computeIfAbsent(d.modId(), k -> new ArrayList<>()).add(d));
        for (var modEntry : byMod.entrySet()) {
            String file = modFile.get(modEntry.getKey());
            Path path = file == null ? null : jars.get(file);
            if (path == null) {
                diag(Severity.ERROR, "INPUT_MISSING", modEntry.getKey(), "MOD の JAR が入力に無い");
                return fail();
            }
            // 1 回目はテキストだけを読んでモデルを解決し、必要な PNG を決める。2 回目にその PNG だけを読む
            SafeArchive meta = open(path, file, sha.get(file), n -> false);
            if (meta == null) {
                return fail();
            }
            Set<String> needed = neededPngs(new ModelResolver(meta), modEntry.getValue());
            unconvertedContent.put(modEntry.getKey(), unconvertedCategories(meta));
            if (modEntry.getValue().isEmpty()) {
                continue;
            }
            SafeArchive jar = open(path, file, sha.get(file), needed::contains);
            if (jar == null) {
                return fail();
            }
            ModelResolver resolver = new ModelResolver(jar);
            List<Definition> defs = new ArrayList<>(modEntry.getValue());
            defs.sort((a, b) -> a.kind() == b.kind() ? a.id().compareTo(b.id()) : b.kind().compareTo(a.kind())); // BLOCK を先に
            for (Definition d : defs) {
                String subject = d.kind().name().toLowerCase() + ":" + d.id();
                String bid = bedrockId(identifiers, d.id());
                String skip = skipReason(d, modeB.get(subject));
                if (d.kind() == Kind.RECIPE) {
                    if (skip != null) {
                        recipeSkips.put(d.id(), skip);
                    } else {
                        recipes.put(d.id(), recipeJson(jar, d));
                    }
                    continue;
                }
                if (skip != null) {
                    entries.put(subject, skipped(d, bid, skip));
                    continue;
                }
                if (d.kind() == Kind.BLOCK) {
                    compileBlock(d, bid, subject, resolver, jar, entries, textures, textureBytes, terrainAtlas);
                } else {
                    compileItem(d, bid, subject, resolver, jar, entries, textures, textureBytes, itemAtlas);
                }
            }
            collectLang(jar, modEntry.getValue(), entries, lang);
        }

        // テクスチャ短縮名の衝突
        Map<String, Set<String>> byShort = new TreeMap<>();
        textures.forEach((id, t) -> byShort.computeIfAbsent(t.shortName(), k -> new TreeSet<>()).add(id));
        byShort.forEach((s, ids) -> {
            if (ids.size() > 1) {
                diag(Severity.ERROR, "TEXTURE_ID_COLLISION", s, "別々のテクスチャが同じ短縮名になる: " + String.join(", ", ids));
            }
        });
        if (textures.size() > MAX_TEXTURES) {
            diag(Severity.ERROR, "PACK_LIMIT_EXCEEDED", "-", "テクスチャが多すぎる（" + textures.size() + "、上限 " + MAX_TEXTURES + "）");
        }
        if (diags.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
            return fail();
        }
        if (entries.values().stream().noneMatch(e -> !e.status().equals("SKIPPED"))) {
            diag(Severity.WARN, "NOTHING_COMPILED", "-", "変換できた定義が無い（空のパックを作る）");
        }

        // ---- パックの中身 ----
        Map<String, byte[]> files = new TreeMap<>();
        textures.forEach((id, t) -> files.put(t.path() + ".png", textureBytes.get(id)));
        files.put("textures/item_texture.json", json(atlas("atlas.items", itemAtlas, textures, false)));
        files.put("textures/terrain_texture.json", json(atlas("atlas.terrain", terrainAtlas, textures, true)));
        List<String> languages = new ArrayList<>(lang.keySet());
        if (!languages.isEmpty()) {
            files.put("texts/languages.json", json(languages));
            lang.forEach((code, kv) -> {
                StringBuilder b = new StringBuilder();
                kv.forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
                files.put("texts/" + code + ".lang", b.toString().getBytes(StandardCharsets.UTF_8));
            });
        }
        String contentSha = contentHash(files);
        UUID header = UUID.nameUUIDFromBytes(("modbridge:rp:header:" + settings.packId()).getBytes(StandardCharsets.UTF_8));
        UUID module = UUID.nameUUIDFromBytes(("modbridge:rp:module:" + settings.packId()).getBytes(StandardCharsets.UTF_8));
        List<Integer> version = packVersion(contentSha);
        files.put("manifest.json", json(manifest(header, module, version, "resources", settings.packName(), false)));

        byte[] pack = zip(files);
        if (pack.length > MAX_PACK_BYTES) {
            diag(Severity.ERROR, "PACK_LIMIT_EXCEEDED", "-", "パックが大きすぎる（" + pack.length + " バイト、上限 " + MAX_PACK_BYTES + "）");
            return fail();
        }
        // ---- ビヘイビアパック ----
        BehaviorPack.Result bp = BehaviorPack.build(entries, recipes, recipeSkips, recipeIdentifiers);
        diags.addAll(bp.diagnostics());
        List<String> notConverted = new ArrayList<>();
        unconvertedContent.forEach((mod, cats) -> {
            if (cats.isEmpty()) {
                return;
            }
            List<String> parts = new ArrayList<>();
            cats.forEach((c, n) -> parts.add(c + " " + n));
            notConverted.add(mod + ": " + String.join("、", parts));
            diag(Severity.WARN, "DATA_NOT_CONVERTED", mod, "変換しないデータ・素材がある（カテゴリ ファイル数）: " + String.join("、", parts));
        });
        for (var m : ir.mods()) {
            if (!m.entrypoints().isEmpty() || !m.mixinConfigs().isEmpty()) {
                String what = m.id() + ": entrypoint " + m.entrypoints().size() + " 個・Mixin 設定 " + m.mixinConfigs().size() + " 個";
                notConverted.add(what);
                diag(Severity.WARN, "JAVA_LOGIC_NOT_CONVERTED", m.id(),
                        "Java のコード（entrypoint " + m.entrypoints().size() + " 個・Mixin 設定 " + m.mixinConfigs().size()
                                + " 個）は Mode B に変換しない。宣言データ（素材・レシピ）だけを変換する");
            }
        }
        Map<String, byte[]> bpFiles = new TreeMap<>(bp.files());
        List<BehaviorPack.Entry> behavior = new ArrayList<>(bp.entries());
        var menus = adapters.menus();
        boolean hasScript = !menus.isEmpty();
        if (hasScript) {
            try {
                BedrockMachineMenus.addScript(menus, entries, bpFiles, behavior);
            } catch (IllegalArgumentException failure) {
                diag(Severity.ERROR, "ADAPTER_RULES_INVALID", "-", failure.getMessage());
                return fail();
            }
        }
        List<Integer> bpMinEngine = hasScript ? BedrockMachineMenus.MIN_ENGINE_VERSION : MIN_ENGINE_VERSION;
        String bpSha = contentHash(withDependency(bpFiles, header.toString(), version));
        UUID bpHeader = UUID.nameUUIDFromBytes(("modbridge:bp:header:" + settings.packId()).getBytes(StandardCharsets.UTF_8));
        UUID bpModule = UUID.nameUUIDFromBytes(("modbridge:bp:module:" + settings.packId()).getBytes(StandardCharsets.UTF_8));
        List<Integer> bpVersion = packVersion(bpSha);
        Map<String, Object> bpManifest = manifest(bpHeader, bpModule, bpVersion, "data", settings.packName() + " (behavior)", hasScript);
        // Map.of は要素が 2 つ以上だと反復の順序が実行ごとに変わりうるので、順序を固定する
        Map<String, Object> dependency = new LinkedHashMap<>();
        dependency.put("uuid", header.toString());
        dependency.put("version", version);
        List<Map<String, Object>> dependencies = new ArrayList<>();
        dependencies.add(dependency);
        if (hasScript) {
            dependencies.addAll(BedrockMachineMenus.dependencies());
        }
        bpManifest.put("dependencies", dependencies);
        bpFiles.put("manifest.json", json(bpManifest));
        byte[] bpPack = zip(bpFiles);
        if (bpPack.length > MAX_PACK_BYTES) {
            diag(Severity.ERROR, "PACK_LIMIT_EXCEEDED", "-", "ビヘイビアパックが大きすぎる（" + bpPack.length + " バイト）");
            return fail();
        }

        Mapping mapping = new Mapping(MAPPING_SCHEMA, MAPPING_SCHEMA_VERSION, "modbridge " + TargetVersions.get("modbridge"),
                new PackInfo(settings.packName(), header.toString(), module.toString(), version, MIN_ENGINE_VERSION, contentSha),
                List.copyOf(entries.values()), List.copyOf(textures.values()),
                new PackInfo(settings.packName() + " (behavior)", bpHeader.toString(), bpModule.toString(), bpVersion, bpMinEngine, bpSha),
                List.copyOf(behavior), List.copyOf(notConverted), List.copyOf(diags));
        Map<String, byte[]> outputs = new LinkedHashMap<>();
        outputs.put(PACK_FILE, pack);
        outputs.put(BEHAVIOR_PACK_FILE, bpPack);
        outputs.put(ADDON_FILE, addon(pack, bpPack));
        outputs.put(MAPPING_FILE, (GSON.toJson(mapping) + "\n").getBytes(StandardCharsets.UTF_8));
        return new Result(true, mapping.diagnostics(), outputs, mapping);
    }

    /** Bedrock で読み込める成分の範囲に収めつつ、内容ハッシュの先頭 28 ビットを保持する。 */
    private static List<Integer> packVersion(String contentSha) {
        int hashPrefix = Integer.parseInt(contentSha.substring(0, 7), 16);
        int componentMask = (1 << 14) - 1;
        return List.of(1, hashPrefix >>> 14, hashPrefix & componentMask);
    }

    /** JAR を開き、IR に記録した SHA-256 と照合する。失敗なら ERROR を記録して null。 */
    private SafeArchive open(Path path, String file, String expectedSha, java.util.function.Predicate<String> alsoRead) {
        SafeArchive jar;
        try {
            jar = SafeArchive.open(path, SafeArchive.Limits.DEFAULT, alsoRead);
        } catch (SafeArchive.ArchiveException e) {
            diag(Severity.ERROR, e.code(), file, e.getMessage());
            return null;
        }
        if (!jar.sha256().equals(expectedSha)) {
            diag(Severity.ERROR, "INPUT_CHANGED", file, "解析の後で JAR が変わった（SHA-256 が IR と違う）");
            return null;
        }
        return jar;
    }

    /** 解決できたモデルが参照する、MOD の名前空間の PNG。 */
    private static Set<String> neededPngs(ModelResolver resolver, List<Definition> defs) {
        Set<String> out = new TreeSet<>();
        for (Definition d : defs) {
            if (d.kind() == Kind.RECIPE) {
                continue;
            }
            ModelResolver.Resolved r = d.kind() == Kind.BLOCK ? resolver.block(d.id()) : resolver.item(d.id());
            if (r.ok()) {
                r.textures().values().stream().filter(id -> !id.startsWith("minecraft:")).forEach(id -> out.add(pngName(id)));
            }
        }
        return out;
    }

    private static String pngName(String textureId) {
        int c = textureId.indexOf(':');
        return "assets/" + textureId.substring(0, c) + "/textures/" + textureId.substring(c + 1) + ".png";
    }

    private static String skipReason(Definition d, FeaturePlan modeB) {
        if (d.source() == Source.RUNTIME_REGISTRY) {
            return "素材が無い（コードでだけ登録）";
        }
        if (modeB == null || modeB.status() == Status.ERROR || modeB.method() == Method.UNSUPPORTED) {
            return "Mode B の計画が対応不可";
        }
        return null;
    }

    private static Entry skipped(Definition d, String bid, String reason) {
        return new Entry(d.id(), d.kind().name(), bid, "SKIPPED", Map.of(), null, List.of(reason));
    }

    private void compileBlock(Definition d, String bid, String subject, ModelResolver resolver, SafeArchive jar,
            Map<String, Entry> entries, Map<String, Texture> textures, Map<String, byte[]> bytes, Set<String> atlas) {
        ModelResolver.Resolved r = resolver.block(d.id());
        if (!r.ok()) {
            diag(Severity.WARN, "MODEL_UNSUPPORTED", d.id(), "BLOCK: " + r.unsupported());
            entries.put(subject, skipped(d, bid, r.unsupported()));
            return;
        }
        Map<String, String> faces = new TreeMap<>();
        Map<String, Loaded> loaded = new TreeMap<>();
        Png.Alpha worst = Png.Alpha.OPAQUE;
        for (var f : r.textures().entrySet()) {
            Loaded l = texture(f.getValue(), d.id(), jar, textures, bytes);
            if (l == null) {
                // 一部の面だけ読めたテクスチャは登録しない（使われないテクスチャをパックに入れない）
                entries.put(subject, skipped(d, bid, "テクスチャを使えない: " + f.getValue()));
                return;
            }
            loaded.put(f.getValue(), l);
            faces.put(f.getKey(), l.texture().shortName());
            Png.Alpha a = Png.Alpha.valueOf(l.texture().alpha());
            worst = a.compareTo(worst) > 0 ? a : worst;
        }
        loaded.forEach((id, l) -> {
            textures.putIfAbsent(id, l.texture());
            bytes.putIfAbsent(id, l.bytes());
            atlas.add(id);
        });
        String render = switch (worst) {
            case OPAQUE -> "opaque";
            case CUTOUT -> "alpha_test";
            case TRANSLUCENT -> "blend";
        };
        entries.put(subject, new Entry(d.id(), d.kind().name(), bid, "GENERATED", faces, render,
                List.of("親モデル " + r.parentModel())));
    }

    private void compileItem(Definition d, String bid, String subject, ModelResolver resolver, SafeArchive jar,
            Map<String, Entry> entries, Map<String, Texture> textures, Map<String, byte[]> bytes, Set<String> atlas) {
        ModelResolver.Resolved r = resolver.item(d.id());
        if ("BLOCK_MODEL".equals(r.unsupported())) {
            Entry block = entries.get("block:" + d.id());
            if (block != null && !block.status().equals("SKIPPED")) {
                entries.put(subject, new Entry(d.id(), d.kind().name(), bid, "GENERATED", Map.of(), null,
                        List.of("ブロックアイテム（見た目は同じ ID のブロックから作る）")));
            } else {
                diag(Severity.WARN, "MODEL_UNSUPPORTED", d.id(), "ITEM: ブロックのモデルを使うが、同じ ID のブロックを変換できない");
                entries.put(subject, skipped(d, bid, "ブロックのモデルを使うが、同じ ID のブロックを変換できない"));
            }
            return;
        }
        if (!r.ok()) {
            diag(Severity.WARN, "MODEL_UNSUPPORTED", d.id(), "ITEM: " + r.unsupported());
            entries.put(subject, skipped(d, bid, r.unsupported()));
            return;
        }
        String layer0 = r.textures().get("layer0");
        Loaded l = texture(layer0, d.id(), jar, textures, bytes);
        if (l == null) {
            entries.put(subject, skipped(d, bid, "テクスチャを使えない: " + layer0));
            return;
        }
        Texture t = l.texture();
        textures.putIfAbsent(layer0, t);
        bytes.putIfAbsent(layer0, l.bytes());
        atlas.add(layer0);
        List<String> notes = new ArrayList<>();
        notes.add("親モデル " + r.parentModel());
        notes.addAll(r.notes());
        entries.put(subject, new Entry(d.id(), d.kind().name(), bid, r.notes().isEmpty() ? "GENERATED" : "PARTIAL",
                Map.of("icon", t.shortName()), null, List.copyOf(notes)));
    }

    /** 読み込んだテクスチャ。定義の変換が成功してから登録する。 */
    private record Loaded(Texture texture, byte[] bytes) {}

    /** 使えなかったテクスチャ（同じ警告を何度も出さないため）。 */
    private final Set<String> failedTextures = new TreeSet<>();

    /**
     * テクスチャを読んで検査する。使えなければ WARN を出して null。
     * 別の JAR が同じテクスチャ ID で違う内容を持っていたら、TEXTURE_CONFLICT（ERROR）にする。
     */
    private Loaded texture(String id, String subject, SafeArchive jar, Map<String, Texture> textures, Map<String, byte[]> bytes) {
        if (failedTextures.contains(id)) {
            return null;
        }
        Loaded l = loadTexture(id, subject, jar);
        if (l == null) {
            failedTextures.add(id);
            return null;
        }
        byte[] known = bytes.get(id);
        if (known != null && !java.util.Arrays.equals(known, l.bytes())) {
            diag(Severity.ERROR, "TEXTURE_CONFLICT", id, "別々の JAR が、同じテクスチャ ID で違う画像を持っている");
            failedTextures.add(id);
            return null;
        }
        return l;
    }

    private Loaded loadTexture(String id, String subject, SafeArchive jar) {
        int c = id.indexOf(':');
        String ns = id.substring(0, c);
        if (ns.equals("minecraft")) {
            diag(Severity.WARN, "TEXTURE_VANILLA_UNSUPPORTED", subject, "バニラのテクスチャの参照は非対応: " + id);
            return null;
        }
        String name = pngName(id);
        byte[] png = jar.text(name);
        if (png == null) {
            diag(Severity.WARN, "TEXTURE_MISSING", subject, "テクスチャが JAR に無い: " + name);
            return null;
        }
        if (jar.has(name + ".mcmeta")) {
            diag(Severity.WARN, "TEXTURE_ANIMATED_UNSUPPORTED", subject, "アニメーションするテクスチャは非対応: " + name);
            return null;
        }
        Png.Info info;
        try {
            info = Png.inspect(png);
        } catch (Png.PngException e) {
            diag(Severity.WARN, "TEXTURE_INVALID", subject, e.getMessage() + ": " + name);
            return null;
        }
        return new Loaded(new Texture(BedrockIds.textureShortName(id), id, BedrockIds.texturePath(id), info.width(), info.height(),
                info.alpha().name()), png);
    }

    /** Java の言語ファイルから、変換した定義の名前だけを Bedrock の .lang に移す。 */
    private static void collectLang(SafeArchive jar, List<Definition> defs, Map<String, Entry> entries, Map<String, Map<String, String>> out) {
        Map<String, List<String>> keys = new TreeMap<>(); // Java のキー → Bedrock のキー
        for (Definition d : defs) {
            Entry e = entries.get(d.kind().name().toLowerCase() + ":" + d.id());
            if (e == null || e.status().equals("SKIPPED")) {
                continue;
            }
            int c = d.id().indexOf(':');
            String javaKey = (d.kind() == Kind.BLOCK ? "block." : "item.") + d.id().substring(0, c) + "." + d.id().substring(c + 1).replace('/', '.');
            // ブロックは tile.<id>.name。アイテムは資料で item.<id>.name と item.<id> が混在するので両方を出す（実機で確認後に絞る）
            keys.put(javaKey, d.kind() == Kind.BLOCK ? List.of("tile." + e.bedrock() + ".name")
                    : List.of("item." + e.bedrock() + ".name", "item." + e.bedrock()));
        }
        if (keys.isEmpty()) {
            return;
        }
        for (String name : jar.names()) {
            Matcher m = LANG.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String code = m.group(2) + "_" + m.group(3).toUpperCase(java.util.Locale.ROOT);
            if (!BEDROCK_LANGUAGES.contains(code)) {
                continue;
            }
            byte[] text = jar.text(name);
            JsonObject o;
            try {
                o = text != null && JsonParser.parseString(new String(text, StandardCharsets.UTF_8)) instanceof JsonObject obj ? obj : null;
            } catch (JsonParseException e) {
                o = null;
            }
            if (o == null) {
                continue;
            }
            for (var k : keys.entrySet()) {
                if (o.get(k.getKey()) instanceof JsonPrimitive p && p.isString()) {
                    // .lang は 1 行 1 項目。改行は空白にする
                    String value = p.getAsString().replaceAll("[\\r\\n]+", " ");
                    k.getValue().forEach(bk -> out.computeIfAbsent(code, x -> new TreeMap<>()).put(bk, value));
                }
            }
        }
    }

    private Map<String, Object> atlas(String name, Set<String> ids, Map<String, Texture> textures, boolean terrain) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("resource_pack_name", settings.packId());
        root.put("texture_name", name);
        if (terrain) {
            root.put("padding", 8);
            root.put("num_mip_levels", 4);
        }
        Map<String, Object> data = new TreeMap<>();
        for (String id : ids) {
            Texture t = textures.get(id);
            data.put(t.shortName(), Map.of("textures", t.path()));
        }
        root.put("texture_data", data);
        return root;
    }

    /** .mcaddon（2 つの .mcpack をまとめた ZIP）。Bedrock で開くと両方を読み込む。 */
    static byte[] addon(byte[] rp, byte[] bp) {
        Map<String, byte[]> m = new TreeMap<>();
        m.put(PACK_FILE, rp);
        m.put(BEHAVIOR_PACK_FILE, bp);
        return zip(m);
    }

    private static JsonObject recipeJson(SafeArchive jar, Definition d) {
        for (String ev : d.evidence()) {
            byte[] b = jar.text(ev);
            if (b != null) {
                try {
                    return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)) instanceof JsonObject o ? o : null;
                } catch (JsonParseException e) {
                    return null;
                }
            }
        }
        return null;
    }

    static String bedrockId(Map<String, String> mappings, String javaId) {
        String mapped = mappings.get(javaId);
        return mapped == null ? BedrockIds.identifier(javaId) : mapped;
    }

    private Map<String, Object> manifest(UUID header, UUID module, List<Integer> version, String moduleType, String name, boolean hasScript) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("name", name);
        h.put("description", "modbridge " + TargetVersions.get("modbridge") + " で生成（Java " + TargetVersions.get("minecraft") + " の MOD から）");
        h.put("uuid", header.toString());
        h.put("version", version);
        h.put("min_engine_version", hasScript ? BedrockMachineMenus.MIN_ENGINE_VERSION : MIN_ENGINE_VERSION);
        Map<String, Object> mod = new LinkedHashMap<>();
        mod.put("type", moduleType);
        mod.put("uuid", module.toString());
        mod.put("version", version);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", 2);
        root.put("header", h);
        root.put("modules", hasScript ? List.of(mod, BedrockMachineMenus.module(header)) : List.of(mod));
        return root;
    }

    static byte[] json(Object o) {
        return (GSON.toJson(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * パックと対応表を照合し、診断を含む生成結果を復元する。読み取れないキャッシュは作り直す。
     */
    static Result restoreCachedResult(Map<String, byte[]> outputs) {
        try {
            Mapping mapping = GSON.fromJson(new String(outputs.get(MAPPING_FILE), StandardCharsets.UTF_8), Mapping.class);
            if (mapping == null || !MAPPING_SCHEMA.equals(mapping.schema()) || mapping.schemaVersion() != MAPPING_SCHEMA_VERSION
                    || mapping.pack() == null || mapping.behaviorPack() == null || mapping.behavior() == null || mapping.entries() == null
                    || mapping.diagnostics() == null) {
                return null;
            }
            if (mapping.diagnostics().stream().anyMatch(d -> d == null || d.severity() == null || d.severity() == Severity.ERROR
                    || d.code() == null || d.subject() == null || d.message() == null)
                    || mapping.entries().stream().anyMatch(e -> e == null || e.status() == null)
                    || mapping.behavior().stream().anyMatch(e -> e == null || e.kind() == null || e.status() == null)
                    || !checkConsistent(outputs, mapping)) {
                return null;
            }
            return new Result(true, List.copyOf(mapping.diagnostics()), outputs, mapping);
        } catch (RuntimeException e) {
            return null; // 型の違う manifest など
        }
    }

    private static boolean checkConsistent(Map<String, byte[]> outputs, Mapping m) {
        byte[] rp = outputs.get(PACK_FILE);
        byte[] bp = outputs.get(BEHAVIOR_PACK_FILE);
        byte[] addon = outputs.get(ADDON_FILE);
        if (rp == null || bp == null || addon == null) {
            return false;
        }
        Map<String, byte[]> rpFiles = unzipBounded(rp);
        Map<String, byte[]> bpFiles = unzipBounded(bp);
        if (rpFiles == null || bpFiles == null || !java.util.Arrays.equals(addon, addon(rp, bp))) {
            return false;
        }
        JsonObject rpManifest = parseObject(rpFiles.remove("manifest.json"));
        JsonObject bpManifest = parseObject(bpFiles.remove("manifest.json"));
        if (!headerMatches(rpManifest, m.pack()) || !headerMatches(bpManifest, m.behaviorPack())) {
            return false;
        }
        // BP の依存先は RP のヘッダー
        boolean hasScript = bpFiles.containsKey(BedrockMachineMenus.ENTRY);
        if (!(bpManifest.get("dependencies") instanceof com.google.gson.JsonArray deps) || deps.size() != (hasScript ? 3 : 1)
                || !(deps.get(0) instanceof JsonObject dep)
                || !m.pack().headerUuid().equals(dep.has("uuid") ? dep.get("uuid").getAsString() : null)
                || !GSON.toJsonTree(m.pack().version()).equals(dep.get("version"))) {
            return false;
        }
        if (!(bpManifest.get("modules") instanceof com.google.gson.JsonArray modules) || modules.size() != (hasScript ? 2 : 1)) {
            return false;
        }
        if (hasScript && (!GSON.toJsonTree(BedrockMachineMenus.dependencies().get(0)).equals(deps.get(1))
                || !GSON.toJsonTree(BedrockMachineMenus.dependencies().get(1)).equals(deps.get(2))
                || !GSON.toJsonTree(BedrockMachineMenus.module(UUID.fromString(m.behaviorPack().headerUuid()))).equals(modules.get(1)))) {
            return false;
        }
        // 対応表の BP のファイルと、BP の中身が一致する
        Set<String> listed = new TreeSet<>();
        m.behavior().forEach(e -> {
            if (e.file() != null) {
                listed.add(e.file());
            }
        });
        if (!listed.equals(bpFiles.keySet())) {
            return false;
        }
        return contentHash(rpFiles).equals(m.pack().contentSha256())
                && contentHash(withDependency(bpFiles, m.pack().headerUuid(), m.pack().version())).equals(m.behaviorPack().contentSha256());
    }

    private static boolean headerMatches(JsonObject manifest, PackInfo info) {
        if (manifest == null || !(manifest.get("header") instanceof JsonObject h) || !h.has("uuid")) {
            return false;
        }
        return info.headerUuid().equals(h.get("uuid").getAsString()) && GSON.toJsonTree(info.version()).equals(h.get("version"))
                && GSON.toJsonTree(info.minEngineVersion()).equals(h.get("min_engine_version"));
    }

    private static JsonObject parseObject(byte[] b) {
        if (b == null) {
            return null;
        }
        try {
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)) instanceof JsonObject o ? o : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * BP の内容のハッシュに、RP への依存（UUID と版）も含める。RP が変われば BP の版も変わり、
     * Bedrock が古い依存先の BP を取得済みとして使い回さないようにする。
     */
    static Map<String, byte[]> withDependency(Map<String, byte[]> files, String rpUuid, List<Integer> rpVersion) {
        Map<String, byte[]> m = new TreeMap<>(files);
        m.put("\0dependency", (rpUuid + " " + rpVersion).getBytes(StandardCharsets.UTF_8));
        if (files.containsKey(BedrockMachineMenus.ENTRY)) {
            m.put("\0script_dependencies", json(BedrockMachineMenus.dependencies()));
        }
        return m;
    }

    /** 上限付きで展開する。壊れている・大きすぎる・名前が重複するなら null。 */
    private static Map<String, byte[]> unzipBounded(byte[] pack) {
        if (pack.length > MAX_PACK_BYTES) {
            return null;
        }
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(pack))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] b = in.readNBytes((int) Math.min(MAX_PACK_BYTES - total + 1, Integer.MAX_VALUE - 8));
                total += b.length;
                if (total > MAX_PACK_BYTES || files.put(e.getName(), b) != null) {
                    return null;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return files;
    }

    /**
     * 変換しないデータ・素材のカテゴリとファイル数。data/ はレシピ以外のすべて（タグ・ルートテーブル・worldgen など）、
     * assets/ はモデル・テクスチャ・blockstate・アイテム定義・言語以外（音・パーティクル・シェーダーなど）。
     */
    static Map<String, Integer> unconvertedCategories(SafeArchive jar) {
        Set<String> convertedAssets = Set.of("models", "textures", "blockstates", "items", "lang");
        Map<String, Integer> out = new TreeMap<>();
        for (String name : jar.names()) {
            String[] p = name.split("/", 4);
            if (p.length < 3) {
                continue;
            }
            // 名前空間の直下のファイル（assets/<ns>/sounds.json など）は、ファイル名をカテゴリにする
            if (p[0].equals("data") && !p[2].equals("recipe") && !p[2].equals("recipes")) {
                out.merge("data/" + p[1] + "/" + p[2], 1, Integer::sum);
            } else if (p[0].equals("assets") && !convertedAssets.contains(p[2])) {
                out.merge("assets/" + p[1] + "/" + p[2], 1, Integer::sum);
            }
        }
        return out;
    }

    /** パスと内容のハッシュから、パック全体の内容のハッシュを作る（manifest は含めない）。 */
    static String contentHash(Map<String, byte[]> files) {
        MessageDigest md = sha256();
        files.forEach((path, b) -> {
            md.update(path.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(sha256().digest(b));
        });
        return HexFormat.of().formatHex(md.digest());
    }

    /** エントリーの順序と時刻を固定した ZIP。 */
    static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (var f : new TreeMap<>(files).entrySet()) {
                ZipEntry e = new ZipEntry(f.getKey());
                e.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                z.putNextEntry(e);
                z.write(f.getValue());
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException(e); // メモリ上の書込みでは起きない
        }
        return out.toByteArray();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
