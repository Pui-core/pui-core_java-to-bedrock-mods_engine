package dev.puicore.modbridge.core.ir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.function.Consumer;

/** IR の JSON 入出力。出力は決定的（フィールド順は record の宣言順、リストは生成時にソート済み、末尾改行あり）。 */
public final class IrJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private IrJson() {}

    public static String write(BridgeIr ir) {
        return GSON.toJson(ir) + "\n";
    }

    /**
     * IR を読む。スキーマ名・版が合わないもの、必須の欄が欠けたもの、型が違うものは
     * {@link IllegalArgumentException} で拒否する（ほかの例外は出さない）。
     *
     * <p>スキーマ 1 は検証の記録を持たないので、状態が SUPPORTED / PARTIAL の計画も拒否する。
     */
    public static BridgeIr read(String json) {
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!(root instanceof JsonObject obj)) {
                throw new IllegalArgumentException("IR ではない（JSON オブジェクトでない）");
            }
            o = obj;
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("IR として読めない JSON: " + e.getMessage(), e);
        }
        if (!(o.get("schema") instanceof JsonPrimitive s) || !s.isString() || !BridgeIr.SCHEMA.equals(s.getAsString())) {
            throw new IllegalArgumentException("IR ではない（schema が " + BridgeIr.SCHEMA + " でない）");
        }
        if (!(o.get("schemaVersion") instanceof JsonPrimitive vp) || !vp.isNumber()) {
            throw new IllegalArgumentException("IR の schemaVersion が無いか数値でない");
        }
        if (vp.getAsDouble() != BridgeIr.SCHEMA_VERSION) {
            throw new IllegalArgumentException("未対応の IR スキーマ版: " + vp.getAsString() + "（対応: " + BridgeIr.SCHEMA_VERSION + "）");
        }
        BridgeIr ir;
        try {
            ir = GSON.fromJson(o, BridgeIr.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("IR の形式が不正: " + e.getClass().getSimpleName(), e);
        }
        validate(ir);
        return ir;
    }

    private static void validate(BridgeIr ir) {
        require(ir.generator(), "generator");
        requireAll(ir.inputs(), "inputs", i -> require(i.name(), "inputs[].name"));
        requireAll(ir.mods(), "mods", m -> {
            require(m.id(), "mods[].id");
            require(m.loader(), "mods[].loader");
            require(m.depends(), "mods[].depends");
        });
        requireAll(ir.definitions(), "definitions", d -> {
            require(d.id(), "definitions[].id");
            require(d.kind(), "definitions[].kind");
            require(d.source(), "definitions[].source");
        });
        requireAll(ir.diagnostics(), "diagnostics", d -> {
            require(d.severity(), "diagnostics[].severity");
            require(d.code(), "diagnostics[].code");
        });
        requireAll(ir.plans(), "plans", p -> {
            require(p.subject(), "plans[].subject");
            require(p.mode(), "plans[].mode");
            require(p.method(), "plans[].method");
            require(p.status(), "plans[].status");
            if (p.status() == BridgeIr.Status.SUPPORTED || p.status() == BridgeIr.Status.PARTIAL) {
                throw new IllegalArgumentException("スキーマ 1 の IR は検証の記録を持たないため、状態 " + p.status() + " は不正: " + p.subject());
            }
        });
    }

    private static <T> void requireAll(List<T> list, String name, Consumer<T> each) {
        require(list, name);
        for (T t : list) {
            require(t, name + "[]");
            each.accept(t);
        }
    }

    /** 値が無い欄（列挙型で未知の値だった場合も含む）を拒否する。 */
    private static void require(Object v, String name) {
        if (v == null) {
            throw new IllegalArgumentException("IR の " + name + " が無いか不正");
        }
    }
}
