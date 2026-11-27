package dev.puicore.modbridge.core.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 実行時レジストリ（{@code modbridge.runtime-registry} 版 1）。Fabric サーバー上の取得 MOD（platform-fabric）が書き出す。
 *
 * <p>読込時に形式を検証し、不正なものは {@link IllegalArgumentException} で拒否する。
 *
 * @param minecraft 取得したサーバーの Minecraft の版
 * @param mods      読み込まれていた MOD の ID と版（同梱 JAR の MOD を含む）
 * @param entries   {@code minecraft} 名前空間以外の登録（ソート済み）
 */
public record RuntimeRegistry(String fileName, String sha256, long size, String minecraft, Map<String, String> mods,
        List<Entry> entries) {

    public static final String SCHEMA = "modbridge.runtime-registry";
    public static final int SCHEMA_VERSION = 1;
    /** 読み込むファイルの上限。数万件の登録でも数 MiB に収まる。 */
    static final long MAX_BYTES = 64L << 20;
    static final Set<String> REGISTRIES = Set.of("item", "block", "recipe");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.\\-/]+");

    /** 1 件の登録。{@code registry} は item / block / recipe のいずれか。 */
    public record Entry(String registry, String id) {
        public String namespace() {
            return id.substring(0, id.indexOf(':'));
        }
    }

    public static RuntimeRegistry read(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException("実行時レジストリが大きすぎる（" + size + " バイト、上限 " + MAX_BYTES + "）");
        }
        byte[] bytes = Files.readAllBytes(path);
        return parse(String.valueOf(path.getFileName()), sha256(bytes), bytes.length, new String(bytes, StandardCharsets.UTF_8));
    }

    static RuntimeRegistry parse(String fileName, String sha256, long size, String json) {
        JsonObject o;
        try {
            if (!(JsonParser.parseString(json) instanceof JsonObject obj)) {
                throw new IllegalArgumentException("実行時レジストリではない（JSON オブジェクトでない）");
            }
            o = obj;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("実行時レジストリとして読めない JSON: " + e.getMessage(), e);
        }
        if (!SCHEMA.equals(string(o.get("schema")))) {
            throw new IllegalArgumentException("実行時レジストリではない（schema が " + SCHEMA + " でない）");
        }
        if (!(o.get("schemaVersion") instanceof JsonPrimitive v) || !v.isNumber() || v.getAsDouble() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("未対応の実行時レジストリの版（対応: " + SCHEMA_VERSION + "）");
        }
        String minecraft = string(o.get("minecraft"));
        if (minecraft == null) {
            throw new IllegalArgumentException("実行時レジストリの minecraft が無いか不正");
        }
        Map<String, String> mods = new TreeMap<>();
        for (JsonElement m : array(o, "mods")) {
            String id = m instanceof JsonObject mo ? string(mo.get("id")) : null;
            String version = m instanceof JsonObject mo ? string(mo.get("version")) : null;
            if (id == null || version == null) {
                throw new IllegalArgumentException("実行時レジストリの mods[] が不正");
            }
            if (mods.put(id, version) != null) {
                throw new IllegalArgumentException("実行時レジストリの mods[] が重複している: " + id);
            }
        }
        List<Entry> entries = new ArrayList<>();
        Set<Entry> unique = new HashSet<>();
        for (JsonElement e : array(o, "entries")) {
            String registry = e instanceof JsonObject eo ? string(eo.get("registry")) : null;
            String id = e instanceof JsonObject eo ? string(eo.get("id")) : null;
            if (registry == null || !REGISTRIES.contains(registry) || id == null || !IDENTIFIER.matcher(id).matches()) {
                throw new IllegalArgumentException("実行時レジストリの entries[] が不正: " + registry + " " + id);
            }
            Entry entry = new Entry(registry, id);
            if (!unique.add(entry)) {
                throw new IllegalArgumentException("実行時レジストリの entries[] が重複している: " + registry + " " + id);
            }
            entries.add(entry);
        }
        entries.sort((a, b) -> a.registry().equals(b.registry()) ? a.id().compareTo(b.id()) : a.registry().compareTo(b.registry()));
        return new RuntimeRegistry(fileName, sha256, size, minecraft, java.util.Collections.unmodifiableMap(mods), List.copyOf(entries));
    }

    private static JsonArray array(JsonObject o, String key) {
        if (!(o.get(key) instanceof JsonArray a)) {
            throw new IllegalArgumentException("実行時レジストリの " + key + " が無いか配列でない");
        }
        return a;
    }

    private static String string(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
