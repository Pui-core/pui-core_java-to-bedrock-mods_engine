package dev.puicore.modbridge.core.ir;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** IR の JSON 入出力。出力は決定的（フィールド順は record の宣言順、リストは生成時にソート済み、末尾改行あり）。 */
public final class IrJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private IrJson() {}

    public static String write(BridgeIr ir) {
        return GSON.toJson(ir) + "\n";
    }

    /** IR を読む。スキーマ名と版が合わないものは拒否する。 */
    public static BridgeIr read(String json) {
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException("IR として読めない JSON: " + e.getMessage(), e);
        }
        if (!o.has("schema") || !BridgeIr.SCHEMA.equals(o.get("schema").getAsString())) {
            throw new IllegalArgumentException("IR ではない（schema が " + BridgeIr.SCHEMA + " でない）");
        }
        int v = o.get("schemaVersion").getAsInt();
        if (v != BridgeIr.SCHEMA_VERSION) {
            throw new IllegalArgumentException("未対応の IR スキーマ版: " + v + "（対応: " + BridgeIr.SCHEMA_VERSION + "）");
        }
        return GSON.fromJson(o, BridgeIr.class);
    }
}
