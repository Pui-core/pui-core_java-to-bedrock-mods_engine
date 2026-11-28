package dev.puicore.modbridge.core.compile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.scan.SafeArchive;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Java の sounds.json にある自 MOD の直接参照を Bedrock のサウンドイベントにする。 */
final class BedrockSounds {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    private static final Pattern EVENT = Pattern.compile("[a-z0-9_][a-z0-9_.-]{0,127}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_-]+(?:[./][a-z0-9_-]+)*");
    private static final Set<String> EVENT_FIELDS = Set.of("sounds", "subtitle", "replace");
    private static final Set<String> CLIP_FIELDS = Set.of("name", "type", "weight", "volume", "pitch", "stream", "preload");

    record Clip(String source, String target, JsonObject definition) {}
    record Event(String javaId, String bedrockId, List<Clip> clips) {}
    record Plan(String source, List<Event> events, boolean complete) {
        Set<String> assets() {
            Set<String> paths = new TreeSet<>();
            events.forEach(event -> event.clips().forEach(clip -> paths.add(clip.source())));
            return paths;
        }
    }

    private BedrockSounds() {}

    static Plan plan(String mod, SafeArchive archive, List<Diagnostic> diagnostics) {
        String file = "assets/" + mod + "/sounds.json";
        byte[] bytes = archive.text(file);
        if (bytes == null) {
            return new Plan(file, List.of(), true);
        }
        List<Event> events = new ArrayList<>();
        int skipped = 0;
        try {
            if (!NAMESPACE.matcher(mod).matches() || mod.equals("minecraft")) {
                throw new IllegalArgumentException("カスタム名前空間を指定する");
            }
            JsonObject definitions = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : new TreeMap<>(definitions.asMap()).entrySet()) {
                String event = entry.getKey();
                try {
                    events.add(event(mod, event, entry.getValue().getAsJsonObject(), archive));
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    skipped++;
                    warn(diagnostics, mod + ":" + event, failure.getMessage());
                }
            }
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException failure) {
            warn(diagnostics, file, failure.getMessage());
            return new Plan(file, List.of(), false);
        }
        return new Plan(file, List.copyOf(events), skipped == 0);
    }

    private static Event event(String mod, String id, JsonObject value, SafeArchive archive) {
        if (!EVENT.matcher(id).matches() || !EVENT_FIELDS.containsAll(value.keySet())
                || !(value.get("sounds") instanceof JsonArray clips) || clips.isEmpty() || clips.size() > 128
                || Boolean.TRUE.equals(flag(value, "replace"))) {
            throw new IllegalArgumentException("未対応のイベント定義");
        }
        List<Clip> result = new ArrayList<>();
        for (JsonElement clip : clips) {
            JsonObject settings = clip.isJsonObject() ? clip.getAsJsonObject() : new JsonObject();
            if (!CLIP_FIELDS.containsAll(settings.keySet())) {
                throw new IllegalArgumentException("未対応の音声オプション");
            }
            if (settings.has("type") && !"file".equals(string(settings.get("type")))) {
                throw new IllegalArgumentException("別イベントの参照には未対応");
            }
            String ref = string(clip.isJsonObject() ? settings.get("name") : clip);
            String[] parts = ref.split(":", -1);
            if (parts.length != 2 || !mod.equals(parts[0]) || parts[1].length() > 160 || !PATH.matcher(parts[1]).matches()) {
                throw new IllegalArgumentException("音声は自 MOD の明示的な namespaced ID を指定する");
            }
            String source = "assets/" + mod + "/sounds/" + parts[1] + ".ogg";
            if (!archive.has(source)) {
                throw new IllegalArgumentException("参照した Ogg が JAR に無い: " + ref);
            }
            String target = "sounds/modbridge/" + mod + "/" + parts[1];
            JsonObject definition = new JsonObject();
            definition.addProperty("name", target);
            number(settings, definition, "weight", 1, 1000, true);
            number(settings, definition, "volume", 0, 16, false);
            number(settings, definition, "pitch", 0.001, 16, false);
            Boolean stream = flag(settings, "stream");
            if (stream != null) {
                definition.addProperty("stream", stream);
            }
            flag(settings, "preload"); // Bedrock 側にこのオプションは引き継がない
            result.add(new Clip(source, target, definition));
        }
        // ドットでつないだ名前空間と Java のイベント名を Bedrock のサウンドキーにする。
        return new Event(mod + ":" + id, "modbridge." + mod + "." + id, List.copyOf(result));
    }

    /** 読み込んだ音声がすべて使用できる場合にイベントを追加する。 */
    static Set<String> add(Plan plan, SafeArchive archive, Map<String, byte[]> files, Map<String, JsonObject> definitions,
            List<PackCompiler.Entry> entries, List<Diagnostic> diagnostics, long remaining) {
        Set<String> used = new TreeSet<>();
        boolean complete = plan.complete();
        for (Event event : plan.events()) {
            if (event.clips().stream().anyMatch(clip -> !ogg(archive.text(clip.source())))) {
                warn(diagnostics, event.javaId(), "Vorbis の Ogg を読み取れないか、任意素材の読込上限を超えた");
                complete = false;
                continue;
            }
            long size = event.clips().stream().filter(clip -> !files.containsKey(clip.target() + ".ogg"))
                    .map(Clip::source).distinct().mapToLong(source -> archive.text(source).length).sum();
            if (size > remaining) {
                warn(diagnostics, event.javaId(), "サウンド素材の容量上限を超えた");
                complete = false;
                continue;
            }
            remaining -= size;
            JsonObject value = new JsonObject();
            value.addProperty("category", "neutral");
            JsonArray clips = new JsonArray();
            for (Clip clip : event.clips()) {
                clips.add(clip.definition());
                files.put(clip.target() + ".ogg", archive.text(clip.source()));
                used.add(clip.source());
            }
            value.add("sounds", clips);
            if (definitions.putIfAbsent(event.bedrockId(), value) != null) {
                diagnostics.add(new Diagnostic(Severity.ERROR, "SOUND_ID_COLLISION", event.bedrockId(), "サウンドキーが重複した"));
                continue;
            }
            entries.add(new PackCompiler.Entry(event.javaId(), "SOUND", event.bedrockId(), "PARTIAL", Map.of(), null,
                    List.of("直接の音声ファイルを収録。Java の再生イベント、字幕、減衰設定は引き継がない")));
        }
        if (complete && !plan.events().isEmpty()) {
            used.add(plan.source());
        }
        return used;
    }

    private static boolean ogg(byte[] data) {
        if (data == null || data.length < 35 || data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S'
                || data[4] != 0 || (data[5] & 2) == 0) {
            return false;
        }
        int segments = Byte.toUnsignedInt(data[26]);
        int packet = 27 + segments;
        return segments > 0 && data.length - packet >= 7 && Byte.toUnsignedInt(data[27]) >= 7
                && data[packet] == 1 && new String(data, packet + 1, 6, StandardCharsets.US_ASCII).equals("vorbis");
    }

    private static String string(JsonElement value) {
        if (!(value instanceof JsonPrimitive text) || !text.isString()) {
            throw new IllegalArgumentException("文字列にする");
        }
        return text.getAsString();
    }

    private static Boolean flag(JsonObject values, String name) {
        if (!values.has(name)) {
            return null;
        }
        if (!(values.get(name) instanceof JsonPrimitive flag) || !flag.isBoolean()) {
            throw new IllegalArgumentException(name + " は真偽値にする");
        }
        return flag.getAsBoolean();
    }

    private static void number(JsonObject from, JsonObject to, String key, double min, double max, boolean integer) {
        if (!from.has(key)) {
            return;
        }
        if (!(from.get(key) instanceof JsonPrimitive number) || !number.isNumber()) {
            throw new IllegalArgumentException(key + " は数値にする");
        }
        double value = number.getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max || (integer && value != Math.rint(value))) {
            throw new IllegalArgumentException(key + " が範囲外");
        }
        if (integer) {
            to.addProperty(key, (int) value);
        } else {
            to.addProperty(key, value);
        }
    }

    private static void warn(List<Diagnostic> diagnostics, String subject, String message) {
        diagnostics.add(new Diagnostic(Severity.WARN, "SOUND_UNSUPPORTED", subject, message));
    }
}
