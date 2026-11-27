package dev.puicore.modbridge.core.compile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.puicore.modbridge.core.TargetVersions;
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
    public static final String MAPPING_FILE = "mapping.json";
    /** 生成器の版。出力の形式を変えたら上げる（キャッシュの鍵に入る）。 */
    static final String COMPILER_VERSION = "pack-compiler/1";
    static final long MAX_PACK_BYTES = 256L << 20;
    static final int MAX_TEXTURES = 16_384;
    /** パックが要求する Bedrock の最低版。実機で未検証の暫定値（ADR-0004）。 */
    static final List<Integer> MIN_ENGINE_VERSION = List.of(1, 21, 0);
    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern LANG = Pattern.compile("assets/([^/]+)/lang/([a-z]{2,3})_([a-z]{2,3})\\.json");
    /** Bedrock が持つ言語（texts/languages.json に書けるもの）。 */
    static final Set<String> BEDROCK_LANGUAGES = Set.of("bg_BG", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_GB", "en_US", "es_ES",
            "es_MX", "fi_FI", "fr_CA", "fr_FR", "hu_HU", "id_ID", "it_IT", "ja_JP", "ko_KR", "nb_NO", "nl_NL", "pl_PL", "pt_BR",
            "pt_PT", "ru_RU", "sk_SK", "sv_SE", "tr_TR", "uk_UA", "zh_CN", "zh_TW");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 生成の設定。{@code packId} はヘッダーの UUID を決める（同じ ID なら、内容が変わっても同じパックとして更新される）。 */
    public record Settings(String packName, String packId) {
        public static final Settings DEFAULT = new Settings("PuiModBridge Resources", "modbridge");

        public Settings {
            if (packName == null || packName.isBlank() || packName.length() > 64) {
                throw new IllegalArgumentException("パック名は 1〜64 文字にする");
            }
            if (packId == null || !PACK_ID.matcher(packId).matches()) {
                throw new IllegalArgumentException("パック ID は [a-z0-9_.-] の 1〜64 文字にする");
            }
        }
    }

    // ---- 対応表（mapping.json） ----

    public record Mapping(String schema, int schemaVersion, String generator, PackInfo pack, List<Entry> entries,
            List<Texture> textures) {}

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
        List<Definition> targets = ir.definitions().stream().filter(d -> d.kind() == Kind.ITEM || d.kind() == Kind.BLOCK).toList();

        // Bedrock 識別子の衝突（アイテムとブロックは同じ名前空間。同じ Java ID のアイテムとブロックはブロックアイテムなので可）
        Map<String, Set<String>> byBedrock = new TreeMap<>();
        targets.forEach(d -> byBedrock.computeIfAbsent(BedrockIds.identifier(d.id()), k -> new TreeSet<>()).add(d.id()));
        byBedrock.forEach((bid, javaIds) -> {
            if (javaIds.size() > 1) {
                diag(Severity.ERROR, "ID_COLLISION", bid, "別々の Java 識別子が同じ Bedrock 識別子になる: " + String.join(", ", javaIds));
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
        targets.forEach(d -> byMod.computeIfAbsent(d.modId(), k -> new ArrayList<>()).add(d));
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
            SafeArchive jar = open(path, file, sha.get(file), needed::contains);
            if (jar == null) {
                return fail();
            }
            ModelResolver resolver = new ModelResolver(jar);
            List<Definition> defs = new ArrayList<>(modEntry.getValue());
            defs.sort((a, b) -> a.kind() == b.kind() ? a.id().compareTo(b.id()) : b.kind().compareTo(a.kind())); // BLOCK を先に
            for (Definition d : defs) {
                String subject = d.kind().name().toLowerCase() + ":" + d.id();
                String bid = BedrockIds.identifier(d.id());
                String skip = skipReason(d, modeB.get(subject));
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
        // 内容が変わったら版も変える（Bedrock は UUID と版で取得済みのパックを使い回すため）。単調増加ではない
        List<Integer> version = List.of(1, 0, Integer.parseUnsignedInt(contentSha.substring(0, 7), 16));
        files.put("manifest.json", json(manifest(header, module, version)));

        byte[] pack = zip(files);
        if (pack.length > MAX_PACK_BYTES) {
            diag(Severity.ERROR, "PACK_LIMIT_EXCEEDED", "-", "パックが大きすぎる（" + pack.length + " バイト、上限 " + MAX_PACK_BYTES + "）");
            return fail();
        }
        Mapping mapping = new Mapping(MAPPING_SCHEMA, MAPPING_SCHEMA_VERSION, "modbridge " + TargetVersions.get("modbridge"),
                new PackInfo(settings.packName(), header.toString(), module.toString(), version, MIN_ENGINE_VERSION, contentSha),
                List.copyOf(entries.values()), List.copyOf(textures.values()));
        Map<String, byte[]> outputs = new LinkedHashMap<>();
        outputs.put(PACK_FILE, pack);
        outputs.put(MAPPING_FILE, (GSON.toJson(mapping) + "\n").getBytes(StandardCharsets.UTF_8));
        return new Result(true, List.copyOf(diags), outputs, mapping);
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

    private Map<String, Object> manifest(UUID header, UUID module, List<Integer> version) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("name", settings.packName());
        h.put("description", "modbridge " + TargetVersions.get("modbridge") + " で生成（Java " + TargetVersions.get("minecraft") + " の MOD から）");
        h.put("uuid", header.toString());
        h.put("version", version);
        h.put("min_engine_version", MIN_ENGINE_VERSION);
        Map<String, Object> mod = new LinkedHashMap<>();
        mod.put("type", "resources");
        mod.put("uuid", module.toString());
        mod.put("version", version);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", 2);
        root.put("header", h);
        root.put("modules", List.of(mod));
        return root;
    }

    private static byte[] json(Object o) {
        return (GSON.toJson(o) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * パックと対応表が対応しているか（対応表の {@code pack.contentSha256} が、パックの中身から計算した値と一致するか）。
     * キャッシュの検証に使う。読めないものは不一致とする。
     */
    static boolean consistent(byte[] pack, byte[] mappingJson) {
        if (pack.length > MAX_PACK_BYTES) {
            return false;
        }
        String expected;
        try {
            Mapping m = GSON.fromJson(new String(mappingJson, StandardCharsets.UTF_8), Mapping.class);
            expected = m == null || m.pack() == null ? null : m.pack().contentSha256();
        } catch (RuntimeException e) {
            return false;
        }
        if (expected == null) {
            return false;
        }
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(pack))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] b = in.readNBytes((int) Math.min(MAX_PACK_BYTES - total + 1, Integer.MAX_VALUE - 8));
                total += b.length;
                if (total > MAX_PACK_BYTES || files.put(e.getName(), b) != null) {
                    return false;
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
        if (files.remove("manifest.json") == null) {
            return false;
        }
        return contentHash(files).equals(expected);
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
