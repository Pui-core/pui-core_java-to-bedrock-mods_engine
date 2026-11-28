package dev.puicore.modbridge.core.compile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Mode B のビヘイビアパック（アイテム・ブロック・レシピの定義）を作る。見た目はリソースパックの変換結果を使う。
 *
 * <ul>
 *   <li>アイテム: {@code minecraft:item}。アイコンは item_texture.json の短縮名</li>
 *   <li>ブロック: {@code minecraft:block}。形は {@code minecraft:geometry.full_block}、面は {@code material_instances}</li>
 *   <li>ブロックアイテム: Bedrock がブロックから自動で作るので、ファイルは作らない</li>
 *   <li>レシピ: {@code crafting_shaped} と {@code crafting_shapeless} のうち、材料と結果が単一のアイテムのものだけ</li>
 * </ul>
 *
 * <p>Java のコードで決まる性質（スタック数・硬さ・道具の条件・ドロップ数など）は静的には分からないので、
 * Bedrock の既定値になる。これは定義ごとに注記し、黙って欠落させない。
 */
public final class BehaviorPack {
    /** 1.21.80 以降、ブロックは geometry と material_instances を両方書く必要がある。 */
    static final String FORMAT_VERSION = "1.21.80";
    static final String RECIPE_FORMAT_VERSION = "1.20.10";
    private static final String MIRRORED_RECIPE_FORMAT_VERSION = "1.20.80";
    static final String CODE_PROPERTIES_NOTE = "スタック数・硬さ・道具の条件・ドロップなど、Java のコードで決まる性質は変換していない（Bedrock の既定値になる）";

    /** ビヘイビアパックでの定義 1 件の変換結果。{@code status} は GENERATED / PARTIAL / SKIPPED。 */
    public record Entry(String java, String kind, String bedrock, String status, String file, List<String> notes) {}

    record Result(Map<String, byte[]> files, List<Entry> entries, List<Diagnostic> diagnostics) {}

    private BehaviorPack() {}

    /**
     * @param rp      リソースパックの変換結果（Java のサブジェクト → 結果）
     * @param recipes レシピの Java 識別子 → レシピの JSON（読めなかったものは null）
     */
    static Result build(Map<String, PackCompiler.Entry> rp, Map<String, JsonObject> recipes, Map<String, String> recipeSkips) {
        Map<String, byte[]> files = new TreeMap<>();
        Map<String, Entry> entries = new TreeMap<>();
        List<Diagnostic> diags = new ArrayList<>();
        Set<String> available = new TreeSet<>(); // レシピで使える Java の識別子（変換できたアイテムとブロック）

        for (PackCompiler.Entry e : rp.values()) {
            String subject = e.kind().toLowerCase() + ":" + e.java();
            if (e.status().equals("SKIPPED")) {
                entries.put(subject, new Entry(e.java(), e.kind(), e.bedrock(), "SKIPPED", null, List.of("リソースパックで変換していない")));
                continue;
            }
            available.add(e.java());
            String file = (e.kind().equals("BLOCK") ? "blocks/" : "items/") + e.bedrock().replace(':', '/') + ".json";
            if (e.kind().equals("BLOCK")) {
                files.put(file, PackCompiler.json(block(e)));
                entries.put(subject, new Entry(e.java(), e.kind(), e.bedrock(), "PARTIAL", file, List.of(CODE_PROPERTIES_NOTE)));
            } else if (e.textures().containsKey("icon")) {
                files.put(file, PackCompiler.json(item(e)));
                entries.put(subject, new Entry(e.java(), e.kind(), e.bedrock(), "PARTIAL", file, List.of(CODE_PROPERTIES_NOTE)));
            } else {
                // ブロックアイテム。Bedrock がブロックから自動で作る
                entries.put(subject, new Entry(e.java(), e.kind(), e.bedrock(), "GENERATED", null,
                        List.of("ブロックアイテム（Bedrock がブロックから自動で作るので、ファイルは作らない）")));
            }
        }

        recipeSkips.forEach((id, reason) -> entries.put("recipe:" + id,
                new Entry(id, "RECIPE", BedrockIds.identifier(id), "SKIPPED", null, List.of(reason))));
        for (var r : new TreeMap<>(recipes).entrySet()) {
            String subject = "recipe:" + r.getKey();
            String bid = BedrockIds.identifier(r.getKey());
            Recipe converted = r.getValue() == null ? Recipe.fail("レシピの JSON を読めない") : recipe(bid, r.getValue(), available);
            if (converted.json == null) {
                diags.add(new Diagnostic(Severity.WARN, "RECIPE_UNSUPPORTED", r.getKey(), converted.reason));
                entries.put(subject, new Entry(r.getKey(), "RECIPE", bid, "SKIPPED", null, List.of(converted.reason)));
                continue;
            }
            String file = "recipes/" + bid.replace(':', '/') + ".json";
            files.put(file, PackCompiler.json(converted.json));
            List<String> notes = new ArrayList<>(converted.notes);
            entries.put(subject, new Entry(r.getKey(), "RECIPE", bid, notes.isEmpty() ? "GENERATED" : "PARTIAL", file, List.copyOf(notes)));
            if (!notes.isEmpty()) {
                diags.add(new Diagnostic(Severity.WARN, "VANILLA_ID_ASSUMED", r.getKey(), String.join("。", notes)));
            }
        }
        return new Result(files, List.copyOf(entries.values()), diags);
    }

    private static Map<String, Object> item(PackCompiler.Entry e) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", e.bedrock());
        description.put("menu_category", Map.of("category", "items"));
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("minecraft:icon", Map.of("textures", Map.of("default", e.textures().get("icon"))));
        components.put("minecraft:display_name", Map.of("value", "item." + e.bedrock() + ".name"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);
        body.put("components", components);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", FORMAT_VERSION);
        root.put("minecraft:item", body);
        return root;
    }

    private static Map<String, Object> block(PackCompiler.Entry e) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("identifier", e.bedrock());
        description.put("menu_category", Map.of("category", "construction"));
        String render = e.render() == null ? "opaque" : e.render();
        Map<String, Object> materials = new LinkedHashMap<>();
        Set<String> distinct = new TreeSet<>(e.textures().values());
        if (distinct.size() == 1) {
            materials.put("*", material(distinct.iterator().next(), render));
        } else {
            // 側面（north）を既定にして、違う面だけを書く
            String side = e.textures().get("north");
            materials.put("*", material(side, render));
            for (String face : ModelResolver.FACES) {
                String t = e.textures().get(face);
                if (!t.equals(side)) {
                    materials.put(face, material(t, render));
                }
            }
        }
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("minecraft:geometry", "minecraft:geometry.full_block");
        components.put("minecraft:material_instances", materials);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);
        body.put("components", components);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", FORMAT_VERSION);
        root.put("minecraft:block", body);
        return root;
    }

    private static Map<String, Object> material(String texture, String render) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("texture", texture);
        m.put("render_method", render);
        return m;
    }

    // ---- レシピ ----

    private record Recipe(Map<String, Object> json, List<String> notes, String reason) {
        static Recipe fail(String reason) {
            return new Recipe(null, List.of(), reason);
        }
    }

    private static Recipe recipe(String bid, JsonObject r, Set<String> available) {
        String type = str(r.get("type"));
        List<String> notes = new ArrayList<>();
        Set<String> vanilla = new TreeSet<>();
        Set<String> ingredientsForUnlock = new TreeSet<>();
        Map<String, Object> result = result(r.get("result"), available, vanilla);
        if (result == null) {
            return Recipe.fail("結果が単一のアイテムでないか、変換していないアイテム");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", Map.of("identifier", bid));
        body.put("tags", List.of("crafting_table"));
        String formatVersion = RECIPE_FORMAT_VERSION;
        String key;
        if ("minecraft:crafting_shaped".equals(type)) {
            if (!(r.get("pattern") instanceof JsonArray pattern) || pattern.isEmpty() || pattern.size() > 3
                    || !(r.get("key") instanceof JsonObject keys)) {
                return Recipe.fail("pattern または key が不正");
            }
            List<String> rows = new ArrayList<>();
            for (JsonElement row : pattern) {
                String s = str(row);
                if (s == null || s.isEmpty() || s.length() > 3) {
                    return Recipe.fail("pattern の行が不正");
                }
                rows.add(s);
            }
            Set<String> used = new TreeSet<>();
            rows.forEach(row -> row.chars().filter(c -> c != ' ').forEach(c -> used.add(String.valueOf((char) c))));
            if (used.isEmpty() || !used.equals(new TreeSet<>(keys.keySet()))
                    || keys.keySet().stream().anyMatch(k -> k.length() != 1 || k.equals(" "))) {
                return Recipe.fail("pattern の文字と key が対応していない（未定義の文字か、使われないキーがある）");
            }
            Map<String, Object> outKeys = new TreeMap<>();
            for (var k : keys.entrySet()) {
                String id = ingredient(k.getValue(), available, vanilla);
                if (id == null) {
                    return Recipe.fail("材料 " + k.getKey() + " がタグ・複数候補・変換していないアイテムのどれか（非対応）");
                }
                ingredientsForUnlock.add(id);
                outKeys.put(k.getKey(), Map.of("item", id));
            }
            body.put("pattern", rows);
            body.put("key", outKeys);
            // Java の定形レシピは左右反転も受け付ける。
            if (rows.stream().anyMatch(row -> !row.contentEquals(new StringBuilder(row).reverse()))) {
                body.put("assume_symmetry", true);
                formatVersion = MIRRORED_RECIPE_FORMAT_VERSION;
            }
            key = "minecraft:recipe_shaped";
        } else if ("minecraft:crafting_shapeless".equals(type)) {
            if (!(r.get("ingredients") instanceof JsonArray ingredients) || ingredients.isEmpty() || ingredients.size() > 9) {
                return Recipe.fail("ingredients が不正");
            }
            Map<String, Integer> counts = new TreeMap<>();
            for (JsonElement i : ingredients) {
                String id = ingredient(i, available, vanilla);
                if (id == null) {
                    return Recipe.fail("材料がタグ・複数候補・変換していないアイテムのどれか（非対応）");
                }
                counts.merge(id, 1, Integer::sum);
            }
            ingredientsForUnlock.addAll(counts.keySet());
            List<Object> outIngredients = new ArrayList<>();
            counts.forEach((id, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("item", id);
                m.put("count", n);
                outIngredients.add(m);
            });
            body.put("ingredients", outIngredients);
            key = "minecraft:recipe_shapeless";
        } else {
            return Recipe.fail("レシピの種類 " + type + " は非対応（作業台のレシピだけ。かまどなどは v0.4.0）");
        }
        // Bedrock の 1.20 以降の書式には発見条件が必要なので、変換した材料を使う。
        body.put("unlock", ingredientsForUnlock.stream().map(id -> Map.of("item", id)).toList());
        body.put("result", result);
        if (!vanilla.isEmpty()) {
            notes.add("バニラのアイテム ID は Java と同じとみなした（Bedrock で名前が違うものがある）: " + String.join(", ", vanilla));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format_version", formatVersion);
        root.put(key, body);
        return new Recipe(root, notes, null);
    }

    /** 材料を Bedrock の識別子にする。タグ・複数候補・変換していないアイテムは null。 */
    private static String ingredient(JsonElement e, Set<String> available, Set<String> vanilla) {
        String id = e instanceof JsonObject o ? str(o.get("item")) : str(e);
        return itemId(id, available, vanilla);
    }

    private static Map<String, Object> result(JsonElement e, Set<String> available, Set<String> vanilla) {
        if (!(e instanceof JsonObject o)) {
            return null;
        }
        String id = itemId(o.has("id") ? str(o.get("id")) : str(o.get("item")), available, vanilla);
        if (id == null || o.has("components")) {
            return null; // 成分付きの結果（NBT 相当）は非対応
        }
        int count = 1;
        if (o.get("count") instanceof JsonPrimitive p && p.isNumber()) {
            count = p.getAsInt();
        }
        if (count < 1 || count > 64) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", id);
        m.put("count", count);
        return m;
    }

    private static String itemId(String id, Set<String> available, Set<String> vanilla) {
        if (id == null || id.startsWith("#")) {
            return null;
        }
        String n = ModelResolver.normalize(id);
        if (n == null) {
            return null;
        }
        if (n.startsWith("minecraft:")) {
            vanilla.add(n);
            return n;
        }
        return available.contains(n) ? BedrockIds.identifier(n) : null;
    }

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
