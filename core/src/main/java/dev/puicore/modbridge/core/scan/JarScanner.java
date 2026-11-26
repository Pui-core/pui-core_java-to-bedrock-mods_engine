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

    /** 1 JAR の解析結果。 */
    public record JarScan(SafeArchive archive, List<ModDescriptor> mods, List<Definition> definitions,
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
        return new JarScan(jar, mods, defs, List.copyOf(s.diags));
    }

    private void diag(Severity sev, String code, String subject, String message) {
        diags.add(new Diagnostic(sev, code, subject, message));
    }

    // ---- メタデータ ----

    private List<ModDescriptor> readMetadata() {
        byte[] fabric = jar.text("fabric.mod.json");
        if (fabric != null) {
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

    private ModDescriptor readFabric(byte[] bytes) {
        JsonObject o = parseObject("fabric.mod.json", bytes);
        if (o == null) {
            return null;
        }
        String id = str(o, "id");
        if (id == null || !NAMESPACE.matcher(id).matches()) {
            diag(Severity.ERROR, "INVALID_MOD_METADATA", jar.fileName(), "fabric.mod.json の id が無いか不正: " + id);
            return null;
        }
        Map<String, String> depends = new TreeMap<>();
        if (o.get("depends") instanceof JsonObject dep) {
            for (var e : dep.entrySet()) {
                depends.put(e.getKey(), versionSpec(e.getValue()));
            }
        }
        List<String> provides = new ArrayList<>();
        if (o.get("provides") instanceof JsonArray arr) {
            arr.forEach(e -> {
                if (e.isJsonPrimitive()) {
                    provides.add(e.getAsString());
                }
            });
        }
        Set<String> entrypoints = new TreeSet<>();
        if (o.get("entrypoints") instanceof JsonObject ep) {
            for (var e : ep.entrySet()) {
                if (e.getValue() instanceof JsonArray arr) {
                    arr.forEach(v -> entrypoints.add(e.getKey() + "=" + (v.isJsonObject() ? str(v.getAsJsonObject(), "value") : v.getAsString())));
                }
            }
        }
        Set<String> mixins = new TreeSet<>();
        if (o.get("mixins") instanceof JsonArray arr) {
            arr.forEach(v -> mixins.add(v.isJsonObject() ? String.valueOf(str(v.getAsJsonObject(), "config")) : v.getAsString()));
        }
        Set<String> nested = new TreeSet<>();
        if (o.get("jars") instanceof JsonArray arr) {
            arr.forEach(v -> {
                if (v.isJsonObject() && str(v.getAsJsonObject(), "file") != null) {
                    nested.add(str(v.getAsJsonObject(), "file"));
                }
            });
        }
        if (!nested.isEmpty()) {
            diag(Severity.INFO, "NESTED_JAR_NOT_SCANNED", id, "同梱 JAR " + nested.size() + " 個は未解析（v0.1 の対象外）。一覧は mods[].nestedJars");
        }
        if (!mixins.isEmpty()) {
            diag(Severity.INFO, "HAS_MIXINS", id, "Mixin を使う（Mode A ではサーバー側で動作。Mode B では再現できない）");
        }
        String env = str(o, "environment");
        return new ModDescriptor(id, nz(str(o, "version")), nz(str(o, "name")), Loader.FABRIC, jar.fileName(),
                env == null ? "*" : env, Collections.unmodifiableMap(depends), List.copyOf(new TreeSet<>(provides)), List.copyOf(entrypoints),
                List.copyOf(mixins), List.copyOf(nested));
    }

    private static String versionSpec(JsonElement v) {
        if (v instanceof JsonArray arr) {
            List<String> parts = new ArrayList<>();
            arr.forEach(e -> parts.add(e.getAsString()));
            return String.join(" || ", parts);
        }
        return v.isJsonPrimitive() ? v.getAsString() : v.toString();
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

    private static boolean isModNamespace(String ns) {
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
        if (rid == null || !visited.add(rid) || depth > 16) {
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
            diag(Severity.WARN, "INVALID_JSON", jar.fileName() + "!" + name, "JSON オブジェクトではない");
        } catch (JsonParseException | IllegalStateException ex) {
            diag(name.equals("fabric.mod.json") ? Severity.ERROR : Severity.WARN, "INVALID_JSON",
                    jar.fileName() + "!" + name, "JSON として読めない: " + ex.getMessage());
        }
        return null;
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
