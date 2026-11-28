package dev.puicore.modbridge.core.compile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.puicore.modbridge.core.scan.SafeArchive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Java のモデルを、Bedrock で表せる単純な形に解決する。初期の対象は次の 2 つだけ。
 *
 * <ul>
 *   <li>アイテム: {@code minecraft:item/generated} / {@code item/handheld} を親に持つ平面アイテム（{@code layer0}）</li>
 *   <li>ブロック: 変種が 1 つで回転の無い blockstate と、{@code cube_all} / {@code cube_column} /
 *       {@code cube_bottom_top} / {@code cube} を親に持つ立方体</li>
 * </ul>
 *
 * <p>それ以外（独自の elements、変種・回転・multipart、items 定義の select や condition など）は、理由を付けて非対応にする。
 */
final class ModelResolver {
    static final List<String> FACES = List.of("up", "down", "north", "south", "east", "west");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.\\-/]+");
    private static final int MAX_DEPTH = 16;

    /** 解決の結果。{@code textures} は面またはレイヤー → テクスチャ ID。失敗なら {@code unsupported} に理由。 */
    record Resolved(Map<String, String> textures, String parentModel, List<String> notes, String unsupported) {
        static Resolved fail(String reason) {
            return new Resolved(Map.of(), null, List.of(), reason);
        }

        boolean ok() {
            return unsupported == null;
        }
    }

    private final SafeArchive jar;

    ModelResolver(SafeArchive jar) {
        this.jar = jar;
    }

    /** {@code assets/<ns>/items/<path>.json}（1.21.4 以降のアイテム定義）を解決する。 */
    Resolved item(String id) {
        JsonObject def = json(assetPath(id, "items", ".json"));
        if (def == null) {
            return Resolved.fail("アイテム定義（items/*.json）が無いか読めない");
        }
        if (!(def.get("model") instanceof JsonObject m) || !"minecraft:model".equals(normalize(str(m, "type"))) || str(m, "model") == null) {
            return Resolved.fail("アイテム定義の model が minecraft:model ではない（select・condition などは非対応）");
        }
        if (m.has("tints")) {
            return Resolved.fail("色付け（tints）は非対応");
        }
        return itemModel(str(m, "model"));
    }

    /** Create の静的プレビューでは従来の単層アイコンも扱う。 */
    Resolved previewItem(String id) {
        if (jar.has(assetPath(id, "items", ".json"))) {
            return item(id);
        }
        String model = id.replace(":", ":item/");
        JsonObject legacy = json(assetPath(model, "models", ".json"));
        String parent = legacy == null ? null : normalize(str(legacy, "parent"));
        if (!"minecraft:item/generated".equals(parent) && !"minecraft:item/handheld".equals(parent)) {
            return Resolved.fail("従来モデルは generated／handheld の直接参照だけに対応");
        }
        if (legacy.has("elements") || legacy.has("overrides") || legacy.has("loader")) {
            return Resolved.fail("従来モデルの独自形状・条件・ローダーはプレビューしない");
        }
        return itemModel(model);
    }

    private Resolved itemModel(String model) {
        Chain c = chain(model);
        if (c.error != null) {
            return Resolved.fail(c.error);
        }
        if (c.terminal.equals("minecraft:item/generated") || c.terminal.equals("minecraft:item/handheld")) {
            String layer0 = resolveVar(c.textures, "layer0");
            if (layer0 == null) {
                return Resolved.fail("layer0 のテクスチャが無い");
            }
            List<String> notes = new ArrayList<>();
            if (c.textures.keySet().stream().anyMatch(k -> k.startsWith("layer") && !k.equals("layer0"))) {
                notes.add("layer1 以降は重ねず、layer0 だけを使う");
            }
            return new Resolved(Map.of("layer0", layer0), c.terminal, notes, null);
        }
        if (c.terminal.startsWith("minecraft:block/")) {
            // ブロックのモデルを使うアイテム（ブロックアイテム）。見た目はブロックから作る
            return new Resolved(Map.of(), c.terminal, List.of(), "BLOCK_MODEL");
        }
        return Resolved.fail("親モデル " + c.terminal + " は非対応");
    }

    /** {@code assets/<ns>/blockstates/<path>.json} を解決する。 */
    Resolved block(String id) {
        JsonObject state = json(assetPath(id, "blockstates", ".json"));
        if (state == null) {
            return Resolved.fail("blockstate が無いか読めない");
        }
        if (state.has("multipart")) {
            return Resolved.fail("multipart の blockstate は非対応");
        }
        // 状態を持たないブロック（変種のキーが "" だけ）に限る。facing=north だけの変種などは、状態ごとの見た目なので非対応
        if (!(state.get("variants") instanceof JsonObject variants) || variants.size() != 1 || !variants.has("")) {
            return Resolved.fail("blockstate の変種が \"\" だけではない（状態ごとの見た目は非対応）");
        }
        JsonElement v = variants.get("");
        if (v instanceof JsonArray arr) {
            if (arr.size() != 1) {
                return Resolved.fail("見た目のランダムな変種は非対応");
            }
            v = arr.get(0);
        }
        if (!(v instanceof JsonObject vo) || str(vo, "model") == null) {
            return Resolved.fail("blockstate の変種に model が無い");
        }
        for (String k : List.of("x", "y", "uvlock")) {
            if (vo.has(k)) {
                return Resolved.fail("blockstate の回転（" + k + "）は非対応");
            }
        }
        Chain c = chain(str(vo, "model"));
        if (c.error != null) {
            return Resolved.fail(c.error);
        }
        Map<String, String> faces = new LinkedHashMap<>();
        switch (c.terminal) {
            case "minecraft:block/cube_all" -> FACES.forEach(f -> faces.put(f, resolveVar(c.textures, "all")));
            case "minecraft:block/cube_column" -> {
                for (String f : FACES) {
                    faces.put(f, resolveVar(c.textures, f.equals("up") || f.equals("down") ? "end" : "side"));
                }
            }
            case "minecraft:block/cube_bottom_top" -> {
                for (String f : FACES) {
                    faces.put(f, resolveVar(c.textures, f.equals("up") ? "top" : f.equals("down") ? "bottom" : "side"));
                }
            }
            case "minecraft:block/cube" -> FACES.forEach(f -> faces.put(f, resolveVar(c.textures, f)));
            default -> {
                return Resolved.fail("親モデル " + c.terminal + " は非対応（立方体以外の形は v0.5.0 以降）");
            }
        }
        if (faces.containsValue(null)) {
            return Resolved.fail("面のテクスチャが足りない");
        }
        return new Resolved(faces, c.terminal, List.of(), null);
    }

    /** モデルの親をたどった結果。{@code terminal} は最初に出会った minecraft 名前空間のモデル。 */
    private record Chain(String terminal, Map<String, String> textures, String error) {}

    private Chain chain(String modelRef) {
        Map<String, String> textures = new HashMap<>();
        String ref = normalize(modelRef);
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (ref == null) {
                return new Chain(null, textures, "モデル参照が不正");
            }
            if (ref.startsWith("minecraft:") || ref.startsWith("builtin")) {
                return new Chain(ref.equals("builtin/generated") ? "minecraft:item/generated" : ref, textures, null);
            }
            JsonObject model = json(assetPath(ref, "models", ".json"));
            if (model == null) {
                return new Chain(null, textures, "モデルが無いか読めない: " + ref);
            }
            if (model.has("elements")) {
                return new Chain(null, textures, "独自の形（elements）を持つモデルは非対応（v0.5.0 以降）: " + ref);
            }
            if (model.get("textures") instanceof JsonObject t) {
                for (var e : new TreeMap<>(t.asMap()).entrySet()) {
                    if (e.getValue() instanceof JsonPrimitive p && p.isString()) {
                        textures.putIfAbsent(e.getKey(), p.getAsString()); // 子の指定を優先する
                    }
                }
            }
            String parent = str(model, "parent");
            if (parent == null) {
                return new Chain(null, textures, "親モデルが無い（独自の形）: " + ref);
            }
            ref = normalize(parent);
        }
        return new Chain(null, textures, "親モデルが深すぎる");
    }

    /** {@code #var} の参照をたどって、テクスチャ ID にする。解決できなければ null。 */
    private static String resolveVar(Map<String, String> textures, String var) {
        String v = textures.get(var);
        for (int i = 0; v != null && v.startsWith("#") && i < MAX_DEPTH; i++) {
            v = textures.get(v.substring(1));
        }
        return v == null || v.startsWith("#") ? null : normalize(v);
    }

    static String normalize(String ref) {
        if (ref == null) {
            return null;
        }
        if (ref.startsWith("builtin/")) {
            return ref;
        }
        String r = ref.contains(":") ? ref : "minecraft:" + ref;
        return IDENTIFIER.matcher(r).matches() ? r : null;
    }

    static String assetPath(String id, String folder, String suffix) {
        int c = id.indexOf(':');
        return "assets/" + id.substring(0, c) + "/" + folder + "/" + id.substring(c + 1) + suffix;
    }

    private JsonObject json(String name) {
        byte[] b = jar.text(name);
        if (b == null) {
            return null;
        }
        try {
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)) instanceof JsonObject o ? o : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
