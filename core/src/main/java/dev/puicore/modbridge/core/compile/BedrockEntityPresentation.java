package dev.puicore.modbridge.core.compile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import dev.puicore.modbridge.core.scan.SafeArchive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Mode B で実体と描画を同じ識別子に結び付ける、単純な回転エンティティ。 */
final class BedrockEntityPresentation {
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern TEXTURE = Pattern.compile("entity/[a-z0-9_-]+(?:/[a-z0-9_-]+)*");
    private static final Set<String> FIELDS = Set.of("id", "texture", "axis", "rpm", "smoke");

    record Entity(String id, String texture, String axis, int rpm, boolean smoke) {}
    record Plan(String source, List<Entity> entities) {
        Set<String> assets() {
            Set<String> assets = new TreeSet<>();
            entities.forEach(entity -> assets.add(entity.texture()));
            return assets;
        }
    }

    private BedrockEntityPresentation() {}

    static Plan plan(String mod, SafeArchive archive) {
        String path = "assets/" + mod + "/modbridge/presentation.json";
        if (!archive.has(path)) {
            return new Plan(path, List.of());
        }
        try {
            JsonElement parsed = GSON.fromJson(new String(archive.text(path), StandardCharsets.UTF_8), JsonElement.class);
            if (!NAME.matcher(mod).matches() || mod.equals("minecraft")) {
                throw new IllegalArgumentException("MOD ID は Bedrock のカスタム名前空間に使える [a-z0-9_] にする");
            }
            if (!(parsed instanceof JsonObject root) || !Set.of("schemaVersion", "entities").equals(root.keySet())
                    || integer(root.get("schemaVersion"), 1, 1) != 1
                    || !(root.get("entities") instanceof JsonArray entities) || entities.isEmpty() || entities.size() > 64) {
                throw new IllegalArgumentException("schemaVersion 1 と 1〜64 件の entities を指定する");
            }
            return new Plan(path, parseEntities(mod, entities));
        } catch (JsonParseException | IllegalArgumentException failure) {
            throw new IllegalArgumentException(path + " の形式が不正: " + failure.getMessage(), failure);
        }
    }

    private static List<Entity> parseEntities(String mod, JsonArray entities) {
        List<Entity> result = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (JsonElement entry : entities) {
            if (!(entry instanceof JsonObject config) || !FIELDS.containsAll(config.keySet())) {
                throw new IllegalArgumentException("未知のエンティティ設定がある");
            }
            String id = text(config.get("id"));
            String texture = text(config.get("texture"));
            String axis = text(config.get("axis"));
            if (!NAME.matcher(id).matches() || !ids.add(id) || texture.length() > 160 || !TEXTURE.matcher(texture).matches()
                    || !Set.of("x", "y", "z").contains(axis)) {
                throw new IllegalArgumentException("id、textures 以下のパス、回転軸のいずれかが不正か id が重複: " + id);
            }
            int rpm = integer(config.get("rpm"), 1, 120);
            JsonElement smoke = config.get("smoke");
            if (smoke != null && (!(smoke instanceof JsonPrimitive value) || !value.isBoolean())) {
                throw new IllegalArgumentException("smoke は真偽値にする");
            }
            result.add(new Entity(mod + ":" + id, "assets/" + mod + "/textures/" + texture + ".png", axis, rpm,
                    smoke != null && smoke.getAsBoolean()));
        }
        return List.copyOf(result);
    }

    static void add(Plan plan, SafeArchive archive, Map<String, byte[]> rp, Map<String, byte[]> bp,
            List<PackCompiler.Entry> mappings, List<BehaviorPack.Entry> behavior) {
        for (Entity entity : plan.entities()) {
            String filename = entity.id().replace(':', '/');
            String suffix = entity.id().replace(':', '.');
            String geometry = "geometry.modbridge." + suffix;
            String animation = "animation.modbridge." + suffix + ".active";
            String texture = "textures/modbridge/entity/" + filename;
            String property = "modbridge:active";
            String controller = "controller.render.modbridge." + suffix;

            byte[] png = archive.text(entity.texture());
            Png.Info image;
            try {
                if (png == null) {
                    throw new IllegalArgumentException("テクスチャが JAR に無い: " + entity.texture());
                }
                image = Png.inspect(png);
                if (image.width() != 32 || image.height() != 16) {
                    throw new IllegalArgumentException("回転体のテクスチャは 32×16 px にする: " + entity.texture());
                }
            } catch (Png.PngException failure) {
                throw new IllegalArgumentException(entity.texture() + ": " + failure.getMessage(), failure);
            }
            put(rp, texture + ".png", png);
            put(rp, "models/entity/" + filename + ".geo.json", json(geometry(geometry, image)));
            put(rp, "animations/" + filename + ".animation.json", json(animation(animation, entity)));
            put(rp, "render_controllers/" + filename + ".render_controllers.json", json(Map.of(
                    "format_version", "1.8.0", "render_controllers", Map.of(controller, Map.of(
                            "geometry", "Geometry.default", "materials", List.of(Map.of("*", "Material.default")),
                            "textures", List.of("Texture.default"))))));
            put(rp, "entity/" + filename + ".entity.json", json(client(entity, image, property, geometry,
                    texture, animation, controller)));
            put(bp, "entities/" + filename + ".json", json(behavior(entity.id(), property)));

            List<String> notes = List.of("Mode B の宣言エンティティ。回転と表示はサーバーが同期する active プロパティで切り替える");
            mappings.add(new PackCompiler.Entry(entity.id(), "ENTITY", entity.id(), "PARTIAL", Map.of("default", texture), geometry, notes));
            behavior.add(new BehaviorPack.Entry(entity.id(), "ENTITY", entity.id(), "PARTIAL", "entities/" + filename + ".json", notes));
        }
    }

    private static Map<String, Object> geometry(String id, Png.Info image) {
        Map<String, Object> description = Map.of("identifier", id, "texture_width", image.width(), "texture_height", image.height(),
                "visible_bounds_width", 2, "visible_bounds_height", 2, "visible_bounds_offset", List.of(0, 0.5, 0));
        Map<String, Object> cube = Map.of("origin", List.of(-4, 4, -4), "size", List.of(8, 8, 8), "uv", List.of(0, 0));
        Map<String, Object> rotor = Map.of("name", "rotor", "pivot", List.of(0, 8, 0), "cubes", List.of(cube),
                "locators", Map.of("smoke", List.of(0, 12, 0)));
        return Map.of("format_version", "1.12.0", "minecraft:geometry",
                List.of(Map.of("description", description, "bones", List.of(rotor))));
    }

    private static Map<String, Object> animation(String id, Entity entity) {
        List<Object> rotation = new ArrayList<>(List.of(0, 0, 0));
        rotation.set("xyz".indexOf(entity.axis()), "math.mod(query.life_time * " + entity.rpm() * 6 + ", 360)");
        Map<String, Object> active = new TreeMap<>();
        active.put("loop", true);
        active.put("animation_length", 1);
        active.put("bones", Map.of("rotor", Map.of("rotation", rotation)));
        if (entity.smoke()) {
            active.put("particle_effects", Map.of("0.0", Map.of("effect", "smoke", "locator", "smoke")));
        }
        return Map.of("format_version", "1.8.0", "animations", Map.of(id, active));
    }

    private static Map<String, Object> client(Entity entity, Png.Info image, String property, String geometry, String texture,
            String animation, String controller) {
        String material = switch (image.alpha()) {
            case OPAQUE -> "entity";
            case CUTOUT -> "entity_alphatest";
            case TRANSLUCENT -> "entity_alphablend";
        };
        Map<String, Object> description = new TreeMap<>(Map.of(
                "identifier", entity.id(), "materials", Map.of("default", material), "textures", Map.of("default", texture),
                "geometry", Map.of("default", geometry), "render_controllers", List.of(controller),
                "animations", Map.of("active", animation),
                "scripts", Map.of("animate", List.of(Map.of("active", "query.property('" + property + "')")))));
        if (entity.smoke()) {
            description.put("particle_effects", Map.of("smoke", "minecraft:basic_smoke_particle"));
        }
        return Map.of("format_version", "1.10.0", "minecraft:client_entity", Map.of("description", description));
    }

    private static Map<String, Object> behavior(String id, String property) {
        return Map.of("format_version", "1.21.80", "minecraft:entity", Map.of(
                "description", Map.of("identifier", id, "is_spawnable", false, "is_summonable", true, "properties",
                        Map.of(property, Map.of("type", "bool", "default", false, "client_sync", true))),
                "components", Map.of(
                        "minecraft:physics", Map.of("has_gravity", false, "has_collision", true),
                        "minecraft:collision_box", Map.of("width", 0.75, "height", 0.75),
                        "minecraft:pushable", Map.of("is_pushable", false, "is_pushable_by_piston", false),
                        "minecraft:interact", Map.of("interactions", List.of(Map.of(
                                "on_interact", Map.of("event", "modbridge:toggle", "target", "self"),
                                "interact_text", "Toggle Rotation", "cooldown", 0.5, "swing", false)))),
                "events", Map.of(
                        "modbridge:activate", Map.of("set_property", Map.of(property, true)),
                        "modbridge:deactivate", Map.of("set_property", Map.of(property, false)),
                        "modbridge:toggle", Map.of("set_property", Map.of(property, "!query.property('" + property + "')")))));
    }

    private static byte[] json(Map<String, Object> value) {
        return PackCompiler.json(sorted(value));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, element) -> sorted.put((String) key, sorted(element)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(BedrockEntityPresentation::sorted).toList();
        }
        return value;
    }

    private static void put(Map<String, byte[]> files, String path, byte[] bytes) {
        if (files.putIfAbsent(path, bytes) != null) {
            throw new IllegalArgumentException("エンティティの生成先が重複: " + path);
        }
    }

    private static String text(JsonElement element) {
        if (!(element instanceof JsonPrimitive value) || !value.isString()) {
            throw new IllegalArgumentException("エンティティの必須設定に文字列が必要");
        }
        return value.getAsString();
    }

    private static int integer(JsonElement element, int min, int max) {
        if (!(element instanceof JsonPrimitive value) || !value.isNumber()) {
            throw new IllegalArgumentException("整数の設定値が必要");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) {
            throw new IllegalArgumentException("整数の設定は " + min + "〜" + max + " の範囲にする");
        }
        return (int) number;
    }
}
