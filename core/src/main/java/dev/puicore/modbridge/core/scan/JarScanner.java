package dev.puicore.modbridge.core.scan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.puicore.modbridge.core.ir.BridgeIr.Definition;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Loader;
import dev.puicore.modbridge.core.ir.BridgeIr.ModDescriptor;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.BridgeIr.Source;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1 つの JAR を静的に解析する。メタデータと素材ファイルから、アイテム・ブロック・レシピの定義を推定する。
 *
 * <p>静的解析では、コードでだけ登録される定義（素材を持たないもの）は見つからない。
 * 実行時レジストリとの差分は、Mode A の実行時取得（別工程）で埋める。
 */
public final class JarScanner {

    /** 1 JAR の解析結果。読み込んだ内容（SafeArchive）は保持しない（大量の JAR でメモリを使い切らないため）。 */
    public record JarScan(List<ModDescriptor> mods, List<Definition> definitions,
            List<Diagnostic> diagnostics) {}

    static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.\\-/]+");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern ITEM_DEF = Pattern.compile("assets/([^/]+)/items/(.+)\\.json");
    private static final Pattern BLOCKSTATE = Pattern.compile("assets/([^/]+)/blockstates/(.+)\\.json");
    private static final Pattern RECIPE = Pattern.compile("data/([^/]+)/recipes?/(.+)\\.json");
    private static final Pattern BLOCK_LOOT = Pattern.compile("data/([^/]+)/loot_tables?/blocks/(.+)\\.json");
    private static final Pattern LANG = Pattern.compile("assets/([^/]+)/lang/([a-z_]+)\\.json");

    private final List<Diagnostic> diags = new ArrayList<>();
    private final SafeArchive jar;

    private JarScanner(SafeArchive jar) {
        this.jar = jar;
    }

    public static JarScan scan(SafeArchive jar) {
        JarScanner s = new JarScanner(jar);
        List<ModDescriptor> mods = s.readMetadata();
        String ownerMod = mods.isEmpty() ? null : mods.get(0).id();
        List<Definition> defs = ownerMod == null ? List.of() : s.inferDefinitions(ownerMod);
        return new JarScan(mods, defs, List.copyOf(s.diags));
    }

    private void diag(Severity sev, String code, String subject, String message) {
        diags.add(new Diagnostic(sev, code, subject, message));
    }

    // ---- メタデータ ----

    private List<ModDescriptor> readMetadata() {
        byte[] fabric = jar.text("fabric.mod.json");
        if (fabric != null) {
            if (jar.has("META-INF/neoforge.mods.toml") || jar.has("META-INF/mods.toml")) {
                diag(Severity.INFO, "MULTI_LOADER_JAR", jar.fileName(), "NeoForge/Forge のメタデータもあるが、Fabric の MOD として解析する");
            }
            ModDescriptor d = readFabric(fabric);
            return d == null ? List.of() : List.of(d);
        }
        if (jar.has("META-INF/neoforge.mods.toml") || jar.has("META-INF/mods.toml")) {
            diag(Severity.ERROR, "UNSUPPORTED_LOADER", jar.fileName(),
                    "NeoForge/Forge の MOD は未対応（v0.8.0 で検証予定）");
            return List.of();
        }
        diag(Severity.ERROR, "MISSING_MOD_METADATA", jar.fileName(), "fabric.mod.json が無い。Fabric の MOD として扱えない");
        return List.of();
    }

    /**
     * fabric.mod.json を読む。必須でない欄の型が違っても例外は出さず、{@code INVALID_MOD_METADATA}（ERROR）を記録する。
     * null を返すときは、必ず ERROR 診断を 1 件以上記録している。
     */
    private ModDescriptor readFabric(byte[] bytes) {
        JsonObject o = parseObject("fabric.mod.json", bytes);
        if (o == null) {
            return null; // parseObject が ERROR を記録済み
        }
        String id = str(o, "id");
        if (id == null || !NAMESPACE.matcher(id).matches()) {
            diag(Severity.ERROR, "INVALID_MOD_METADATA", jar.fileName(), "fabric.mod.json の id が無いか不正");
            return null;
        }
        Set<String> invalid = new TreeSet<>();

        Map<String, String> depends = new TreeMap<>();
        JsonElement depEl = o.get("depends");
        if (depEl instanceof JsonObject dep) {
            for (var e : dep.entrySet()) {
                String spec = versionSpec(e.getValue());
                if (spec == null) {
                    invalid.add("depends." + e.getKey());
                } else {
                    depends.put(e.getKey(), spec);
                }
            }
        } else if (depEl != null && !depEl.isJsonNull()) {
            invalid.add("depends");
        }

        Set<String> provides = new TreeSet<>();
        forEachIn(o, "provides", invalid, v -> {
            String s = asString(v);
            if (s == null) {
                return false;
            }
            provides.add(s);
            return true;
        });

        Set<String> entrypoints = new TreeSet<>();
        JsonElement epEl = o.get("entrypoints");
        if (epEl instanceof JsonObject ep) {
            for (var e : ep.entrySet()) {
                if (!(e.getValue() instanceof JsonArray arr)) {
                    invalid.add("entrypoints." + e.getKey());
                    continue;
                }
                for (JsonElement v : arr) {
                    // 文字列か、{"adapter": ..., "value": "..."}
                    String s = v instanceof JsonObject vo ? str(vo, "value") : asString(v);
                    if (s == null) {
                        invalid.add("entrypoints." + e.getKey());
                    } else {
                        entrypoints.add(e.getKey() + "=" + s);
                    }
                }
            }
        } else if (epEl != null && !epEl.isJsonNull()) {
            invalid.add("entrypoints");
        }

        Set<String> mixins = new TreeSet<>();
        forEachIn(o, "mixins", invalid, v -> {
            // 文字列か、{"config": "...", "environment": ...}
            String s = v instanceof JsonObject vo ? str(vo, "config") : asString(v);
            if (s == null) {
                return false;
            }
            mixins.add(s);
            return true;
        });

        Set<String> nested = new TreeSet<>();
        forEachIn(o, "jars", invalid, v -> {
            String s = v instanceof JsonObject vo ? str(vo, "file") : null;
            if (s == null) {
                return false;
            }
            nested.add(s);
            return true;
        });

        for (String field : new String[] {"version", "name", "environment"}) {
            JsonElement f = o.get(field);
            if (f != null && !f.isJsonNull() && asString(f) == null) {
                invalid.add(field);
            }
        }
        if (!invalid.isEmpty()) {
            diag(Severity.ERROR, "INVALID_MOD_METADATA", id, "fabric.mod.json の欄の型が不正: " + String.join(", ", invalid));
        }

        Set<String> nestedMods = readNestedMods(id, nested);
        if (!mixins.isEmpty()) {
            diag(Severity.INFO, "HAS_MIXINS", id, "Mixin を使う（Mode A ではサーバー側で動作。Mode B では再現できない）");
        }
        String env = str(o, "environment");
        return new ModDescriptor(id, nz(str(o, "version")), nz(str(o, "name")), Loader.FABRIC, jar.fileName(),
                env == null ? "*" : env, Collections.unmodifiableMap(depends), List.copyOf(provides), List.copyOf(entrypoints),
                List.copyOf(mixins), List.copyOf(nested), List.copyOf(nestedMods));
    }

    /**
     * 同梱 JAR の fabric.mod.json から、MOD ID と provides だけを読む。中の素材・定義は解析しない。
     * 読めない同梱 JAR は WARN にする（その JAR に依存する MOD は DEPENDENCY_MISSING になりうる）。
     */
    private Set<String> readNestedMods(String ownerId, Set<String> files) {
        Set<String> ids = new TreeSet<>();
        for (String file : files) {
            SafeArchive.NestedJar n = jar.nested(file);
            if (n == null) {
                diag(Severity.WARN, "NESTED_JAR_UNREADABLE", ownerId, "jars に書かれた同梱 JAR が無い: " + file);
                continue;
            }
            if (n.error() != null) {
                diag(Severity.WARN, "NESTED_JAR_UNREADABLE", ownerId, "同梱 JAR を読めない: " + file + "（" + n.error() + "）");
                continue;
            }
            if (n.fabricModJson() == null) {
                continue; // MOD ではないライブラリ
            }
            JsonObject meta;
            try {
                meta = JsonParser.parseString(new String(n.fabricModJson(), StandardCharsets.UTF_8)) instanceof JsonObject mo ? mo : null;
            } catch (JsonParseException | IllegalStateException e) {
                meta = null;
            }
            String nid = meta == null ? null : str(meta, "id");
            if (nid == null || !NAMESPACE.matcher(nid).matches()) {
                diag(Severity.WARN, "NESTED_JAR_UNREADABLE", ownerId, "同梱 JAR の fabric.mod.json が不正: " + file);
                continue;
            }
            ids.add(nid);
            if (meta.get("provides") instanceof JsonArray arr) {
                arr.forEach(v -> {
                    String s = asString(v);
                    if (s != null) {
                        ids.add(s);
                    }
                });
            }
        }
        if (!files.isEmpty()) {
            diag(Severity.INFO, "NESTED_JAR_NOT_SCANNED", ownerId, "同梱 JAR " + files.size()
                    + " 個は MOD ID だけを依存の判定に使い、素材・定義は解析しない（一覧は mods[].nestedJars / nestedMods）");
        }
        return ids;
    }

    /** 配列の各要素を処理する。欄が配列でない場合や、処理が false を返した要素があれば {@code invalid} に記録する。 */
    private static void forEachIn(JsonObject o, String key, Set<String> invalid, java.util.function.Predicate<JsonElement> each) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (!(el instanceof JsonArray arr)) {
            invalid.add(key);
            return;
        }
        for (JsonElement v : arr) {
            if (!each.test(v)) {
                invalid.add(key);
            }
        }
    }

    /** 文字列なら値、それ以外（数値・null・配列・オブジェクト）は null。 */
    private static String asString(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** depends の値（文字列か、文字列の配列）。型が違えば null。 */
    private static String versionSpec(JsonElement v) {
        if (v instanceof JsonArray arr) {
            List<String> parts = new ArrayList<>();
            for (JsonElement e : arr) {
                String s = asString(e);
                if (s == null) {
                    return null;
                }
                parts.add(s);
            }
            return String.join(" || ", parts);
        }
        return asString(v);
    }

    // ---- 定義の推定 ----

    private List<Definition> inferDefinitions(String modId) {
        Map<String, DefBuilder> defs = new TreeMap<>();
        Map<String, String> langKeys = readLangKeys();

        for (String name : jar.names()) {
            Matcher m;
            if ((m = ITEM_DEF.matcher(name)).matches() && isModNamespace(m.group(1))) {
                DefBuilder b = def(defs, Kind.ITEM, id(m));
                b.evidence.add(name);
                collectModelRefs(name, b);
            } else if ((m = BLOCKSTATE.matcher(name)).matches() && isModNamespace(m.group(1))) {
                DefBuilder b = def(defs, Kind.BLOCK, id(m));
                b.evidence.add(name);
                collectModelRefs(name, b);
            } else if ((m = BLOCK_LOOT.matcher(name)).matches() && isModNamespace(m.group(1))) {
                def(defs, Kind.BLOCK, id(m)).evidence.add(name);
            } else if ((m = RECIPE.matcher(name)).matches() && isModNamespace(m.group(1))) {
                DefBuilder b = def(defs, Kind.RECIPE, id(m));
                b.evidence.add(name);
                JsonObject r = parseObject(name, jar.text(name));
                if (r != null && str(r, "type") != null) {
                    b.attributes.put("type", str(r, "type"));
                }
            }
        }
        // 言語ファイルにだけ現れる定義も記録する（素材が欠けている兆候として）
        for (var e : langKeys.entrySet()) {
            String[] p = e.getKey().split("\\.", 3);
            if (p.length == 3 && (p[0].equals("item") || p[0].equals("block")) && isModNamespace(p[1])
                    && !p[2].contains(".")) {
                Kind kind = p[0].equals("item") ? Kind.ITEM : Kind.BLOCK;
                String id = p[1] + ":" + p[2];
                DefBuilder b = def(defs, kind, id);
                b.evidence.add(e.getValue());
            }
        }

        List<Definition> out = new ArrayList<>();
        for (DefBuilder b : defs.values()) {
            if (!IDENTIFIER.matcher(b.id).matches()) {
                diag(Severity.ERROR, "INVALID_IDENTIFIER", b.id, "識別子に使えない文字がある");
                continue;
            }
            boolean hasDefinitionFile = b.evidence.stream().anyMatch(ev -> !ev.contains("/lang/"));
            if (!hasDefinitionFile && b.kind != Kind.RECIPE) {
                diag(Severity.WARN, "MISSING_ASSET", b.id,
                        "言語ファイルにだけ名前がある（" + (b.kind == Kind.ITEM ? "assets/<ns>/items" : "assets/<ns>/blockstates")
                                + " が無い）。Bedrock 側で表示できない可能性がある");
            }
            out.add(new Definition(b.id, b.kind, modId, Source.STATIC_ASSET, List.copyOf(b.evidence),
                    List.copyOf(b.assets), Collections.unmodifiableMap(new TreeMap<>(b.attributes))));
        }
        return out;
    }

    private Map<String, String> readLangKeys() {
        Map<String, String> keys = new TreeMap<>();
        for (String name : jar.names()) {
            Matcher m = LANG.matcher(name);
            if (!m.matches() || !isModNamespace(m.group(1))) {
                continue;
            }
            JsonObject o = parseObject(name, jar.text(name));
            if (o != null) {
                o.keySet().forEach(k -> keys.putIfAbsent(k, name));
            }
        }
        return keys;
    }

    static boolean isModNamespace(String ns) {
        return !ns.equals("minecraft") && !ns.equals("c") && !ns.equals("fabric");
    }

    private static DefBuilder def(Map<String, DefBuilder> defs, Kind kind, String id) {
        return defs.computeIfAbsent(kind + "|" + id, k -> new DefBuilder(id, kind));
    }

    private static String id(Matcher m) {
        return m.group(1) + ":" + m.group(2);
    }

    /** blockstate / item 定義 → モデル → 親モデル・テクスチャをたどり、jar 内に無い参照を報告する。 */
    private void collectModelRefs(String file, DefBuilder b) {
        JsonObject o = parseObject(file, jar.text(file));
        if (o == null) {
            return;
        }
        Set<String> models = new TreeSet<>();
        collectStrings(o, "model", models);
        Set<String> visited = new TreeSet<>();
        for (String model : models) {
            followModel(model, b, visited, 0);
        }
    }

    private void followModel(String ref, DefBuilder b, Set<String> visited, int depth) {
        String rid = normalize(ref);
        if (rid == null) {
            diag(Severity.WARN, "INVALID_IDENTIFIER", b.id, "モデル参照が不正: " + ref);
            return;
        }
        if (!visited.add(rid) || depth > 16) {
            return;
        }
        String ns = rid.substring(0, rid.indexOf(':'));
        String path = "assets/" + ns + "/models/" + rid.substring(ns.length() + 1) + ".json";
        if (!isModNamespace(ns)) {
            return; // バニラ・共通のモデルは Minecraft 側にある前提
        }
        b.assets.add(path);
        if (!jar.has(path)) {
            diag(Severity.WARN, "MISSING_ASSET", b.id, "モデルが見つからない: " + path);
            return;
        }
        JsonObject model = parseObject(path, jar.text(path));
        if (model == null) {
            return;
        }
        if (model.get("textures") instanceof JsonObject tex) {
            for (var e : new TreeMap<>(tex.asMap()).entrySet()) {
                if (!(e.getValue() instanceof JsonPrimitive p) || !p.isString() || p.getAsString().startsWith("#")) {
                    continue;
                }
                String t = normalize(p.getAsString());
                if (t == null) {
                    diag(Severity.WARN, "INVALID_IDENTIFIER", b.id, "テクスチャ参照が不正: " + p.getAsString());
                    continue;
                }
                String tns = t.substring(0, t.indexOf(':'));
                if (!isModNamespace(tns)) {
                    continue;
                }
                String tpath = "assets/" + tns + "/textures/" + t.substring(tns.length() + 1) + ".png";
                b.assets.add(tpath);
                if (!jar.has(tpath)) {
                    diag(Severity.WARN, "MISSING_ASSET", b.id, "テクスチャが見つからない: " + tpath);
                }
            }
        }
        if (str(model, "parent") != null) {
            followModel(str(model, "parent"), b, visited, depth + 1);
        }
    }

    private static void collectStrings(JsonElement e, String key, Set<String> out) {
        if (e instanceof JsonObject o) {
            for (var en : o.entrySet()) {
                if (en.getKey().equals(key) && en.getValue() instanceof JsonPrimitive p && p.isString()) {
                    out.add(p.getAsString());
                } else {
                    collectStrings(en.getValue(), key, out);
                }
            }
        } else if (e instanceof JsonArray a) {
            a.forEach(x -> collectStrings(x, key, out));
        }
    }

    /** {@code path} だけの参照は minecraft 名前空間として扱う（Minecraft の解決規則と同じ）。 */
    static String normalize(String ref) {
        String r = ref.contains(":") ? ref : "minecraft:" + ref;
        return IDENTIFIER.matcher(r).matches() ? r : null;
    }

    private JsonObject parseObject(String name, byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (e instanceof JsonObject o) {
                return o;
            }
            // 空ファイルは JsonNull になる
            diag(severityFor(name), "INVALID_JSON", jar.fileName() + "!" + name, "JSON オブジェクトではない");
        } catch (JsonParseException | IllegalStateException ex) {
            diag(severityFor(name), "INVALID_JSON", jar.fileName() + "!" + name, "JSON として読めない: " + ex.getMessage());
        }
        return null;
    }

    /** fabric.mod.json が読めなければ MOD として扱えないので ERROR。素材ファイルは WARN（変換自体は試せる）。 */
    private static Severity severityFor(String name) {
        return name.equals("fabric.mod.json") ? Severity.ERROR : Severity.WARN;
    }

    private static String str(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static final class DefBuilder {
        final String id;
        final Kind kind;
        final Set<String> evidence = new TreeSet<>();
        final Set<String> assets = new TreeSet<>();
        final Map<String, String> attributes = new TreeMap<>();

        DefBuilder(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }
    }
}
