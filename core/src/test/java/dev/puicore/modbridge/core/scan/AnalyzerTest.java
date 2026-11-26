package dev.puicore.modbridge.core.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.BridgeIr.Diagnostic;
import dev.puicore.modbridge.core.ir.BridgeIr.Kind;
import dev.puicore.modbridge.core.ir.BridgeIr.Method;
import dev.puicore.modbridge.core.ir.BridgeIr.Mode;
import dev.puicore.modbridge.core.ir.BridgeIr.Severity;
import dev.puicore.modbridge.core.ir.BridgeIr.Status;
import dev.puicore.modbridge.core.ir.IrJson;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerTest {

    @TempDir
    Path dir;

    // ---- テスト用 JAR の組み立て ----

    private Path jar(String name, Map<String, String> entries) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(p); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (var e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0);
                zip.putNextEntry(ze);
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return p;
    }

    private static String modJson(String id, String depends) {
        return """
                {"schemaVersion":1,"id":"%s","version":"1.0.0","name":"%s",
                 "entrypoints":{"main":["x.Main"]},"depends":{%s}}""".formatted(id, id, depends);
    }

    /** fixture MOD と同じ構成（アイテム 1・ブロック 1、素材とルートテーブル付き）。 */
    private Map<String, String> fixtureEntries(String ns) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fabric.mod.json", modJson(ns, "\"minecraft\":\"~26.3\",\"fabricloader\":\">=0.20.1\""));
        m.put("assets/" + ns + "/items/test_ingot.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + ns + ":item/test_ingot\"}}");
        m.put("assets/" + ns + "/items/test_block.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + ns + ":block/test_block\"}}");
        m.put("assets/" + ns + "/models/item/test_ingot.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + ns + ":item/test_ingot\"}}");
        m.put("assets/" + ns + "/models/block/test_block.json", "{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"" + ns + ":block/test_block\"}}");
        m.put("assets/" + ns + "/blockstates/test_block.json", "{\"variants\":{\"\":{\"model\":\"" + ns + ":block/test_block\"}}}");
        m.put("assets/" + ns + "/textures/item/test_ingot.png", "png");
        m.put("assets/" + ns + "/textures/block/test_block.png", "png");
        m.put("assets/" + ns + "/lang/en_us.json", "{\"item." + ns + ".test_ingot\":\"Test Ingot\",\"block." + ns + ".test_block\":\"Test Block\"}");
        m.put("data/" + ns + "/loot_table/blocks/test_block.json", "{\"type\":\"minecraft:block\"}");
        m.put("data/" + ns + "/recipe/test_block.json", "{\"type\":\"minecraft:crafting_shaped\"}");
        return m;
    }

    private static List<Diagnostic> codes(BridgeIr ir, String code) {
        return ir.diagnostics().stream().filter(d -> d.code().equals(code)).toList();
    }

    private static boolean hasErrors(BridgeIr ir) {
        return ir.diagnostics().stream().anyMatch(d -> d.severity() == Severity.ERROR);
    }

    // ---- テスト ----

    @Test
    void fixture相当のJARからアイテムとブロックとレシピを推定する() throws IOException {
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("fixture.jar", fixtureEntries("pmb_fixture"))));
        assertFalse(hasErrors(ir), ir.diagnostics().toString());
        assertEquals(1, ir.mods().size());
        // 並び順は識別子 → 種類（ITEM, BLOCK, RECIPE の順）
        assertEquals(List.of("item:pmb_fixture:test_block", "block:pmb_fixture:test_block", "recipe:pmb_fixture:test_block",
                "item:pmb_fixture:test_ingot"), ir.definitions().stream().map(d -> d.kind().name().toLowerCase() + ":" + d.id()).toList());
        var block = ir.definitions().stream().filter(d -> d.kind() == Kind.BLOCK).findFirst().orElseThrow();
        assertTrue(block.assets().contains("assets/pmb_fixture/textures/block/test_block.png"));
        assertTrue(block.evidence().contains("data/pmb_fixture/loot_table/blocks/test_block.json"));
        assertEquals(64, ir.inputs().get(0).sha256().length());
    }

    @Test
    void 検証記録のない計画はSUPPORTEDにしない() throws IOException {
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("fixture.jar", fixtureEntries("pmb_fixture"))));
        assertFalse(ir.plans().isEmpty());
        assertTrue(ir.plans().stream().allMatch(p -> p.status() == Status.UNTESTED), ir.plans().toString());
        // Java コードを持つ MOD は Mode B で自動変換しない
        var modB = ir.plans().stream().filter(p -> p.subject().equals("mod:pmb_fixture") && p.mode() == Mode.B).findFirst().orElseThrow();
        assertEquals(Method.UNSUPPORTED, modB.method());
    }

    @Test
    void 同じ入力からは同じJSONを出力する_入力順にも依存しない() throws IOException {
        Path a = jar("a.jar", fixtureEntries("mod_a"));
        Path b = jar("b.jar", fixtureEntries("mod_b"));
        String first = IrJson.write(Analyzer.analyzePaths(List.of(a, b)));
        String second = IrJson.write(Analyzer.analyzePaths(List.of(b, a)));
        assertEquals(first, second);
        assertFalse(first.contains(dir.toString()), "絶対パスを含めない");
    }

    @Test
    void 壊れたJARはJAR_CORRUPTになり他の入力の解析は続ける() throws IOException {
        Path broken = dir.resolve("broken.jar");
        Files.write(broken, "this is not a zip".getBytes(StandardCharsets.UTF_8));
        BridgeIr ir = Analyzer.analyzePaths(List.of(broken, jar("ok.jar", fixtureEntries("ok_mod"))));
        assertEquals(1, codes(ir, "JAR_CORRUPT").size());
        assertEquals("broken.jar", codes(ir, "JAR_CORRUPT").get(0).subject());
        assertEquals(1, ir.mods().size());
    }

    @Test
    void ZIP_Slipのエントリー名を拒否する() throws IOException {
        Map<String, String> m = fixtureEntries("slip");
        m.put("../../evil.json", "{}");
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("slip.jar", m)));
        assertEquals(1, codes(ir, "UNSAFE_PATH").size());
        assertTrue(ir.mods().isEmpty(), "危険な JAR は解析しない");
    }

    @Test
    void 危険なエントリー名のパターン() {
        for (String bad : List.of("../a", "a/../../b", "/abs", "C:/x", "a\\b", "a/./b", "")) {
            assertThrows(SafeArchive.ArchiveException.class, () -> SafeArchive.checkName(bad), bad);
        }
    }

    @Test
    void ZIP爆弾を読込上限で止める() throws IOException {
        Map<String, String> m = fixtureEntries("bomb");
        m.put("assets/bomb/lang/zz_zz.json", "0".repeat(3 << 20)); // 3 MiB の同一文字（圧縮率は数百倍）
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("bomb.jar", m)));
        assertEquals(1, codes(ir, "JAR_LIMIT_EXCEEDED").size(), ir.diagnostics().toString());

        Path big = jar("big.jar", Map.of("fabric.mod.json", modJson("big", ""), "data/big/x.json", "x".repeat(1024)));
        var tiny = new SafeArchive.Limits(10, 1 << 20, 512, 1 << 20, 200);
        var e = assertThrows(SafeArchive.ArchiveException.class, () -> SafeArchive.open(big, tiny));
        assertEquals("JAR_LIMIT_EXCEEDED", e.code());
    }

    @Test
    void 同じMOD_IDのJARが複数あるとエラー() throws IOException {
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("a.jar", fixtureEntries("same")), jar("b.jar", fixtureEntries("same"))));
        assertEquals(1, codes(ir, "DUPLICATE_MOD_ID").size());
        assertTrue(hasErrors(ir));
    }

    @Test
    void 別のMODが同じ識別子を定義するとエラーになりfail_closed() throws IOException {
        Map<String, String> other = new LinkedHashMap<>();
        other.put("fabric.mod.json", modJson("other", ""));
        other.put("assets/shared/blockstates/thing.json", "{\"variants\":{}}");
        Map<String, String> first = new LinkedHashMap<>();
        first.put("fabric.mod.json", modJson("first", ""));
        first.put("assets/shared/blockstates/thing.json", "{\"variants\":{}}");
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("o.jar", other), jar("f.jar", first)));
        var dup = codes(ir, "DUPLICATE_DEFINITION");
        assertEquals(1, dup.size());
        assertEquals("shared:thing", dup.get(0).subject());
        assertTrue(ir.plans().stream().filter(p -> p.subject().equals("block:shared:thing"))
                .allMatch(p -> p.method() == Method.UNSUPPORTED && p.status() == Status.ERROR));
    }

    @Test
    void 依存の欠落と循環を検出する() throws IOException {
        Path a = jar("a.jar", Map.of("fabric.mod.json", modJson("mod_a", "\"mod_b\":\"*\",\"minecraft\":\"*\"")));
        Path b = jar("b.jar", Map.of("fabric.mod.json", modJson("mod_b", "\"mod_a\":\"*\",\"fabric-api\":\"*\"")));
        BridgeIr ir = Analyzer.analyzePaths(List.of(a, b));
        var missing = codes(ir, "DEPENDENCY_MISSING");
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).message().contains("fabric-api"));
        // 依存が欠けた MOD（mod_b）は fail closed。依存を満たす mod_a は計画上 UNTESTED のまま
        assertTrue(ir.plans().stream().filter(p -> p.subject().equals("mod:mod_b")).allMatch(p -> p.status() == Status.ERROR));
        assertTrue(ir.plans().stream().filter(p -> p.subject().equals("mod:mod_a")).allMatch(p -> p.status() == Status.UNTESTED));
        var cycle = codes(ir, "DEPENDENCY_CYCLE");
        assertEquals(1, cycle.size());
        assertEquals("依存が循環している: mod_a -> mod_b -> mod_a", cycle.get(0).message());
    }

    @Test
    void provides_で依存を満たせる() throws IOException {
        Path a = jar("a.jar", Map.of("fabric.mod.json", modJson("mod_a", "\"alias\":\"*\"")));
        Path b = jar("b.jar", Map.of("fabric.mod.json",
                "{\"schemaVersion\":1,\"id\":\"mod_b\",\"version\":\"1\",\"provides\":[\"alias\"]}"));
        assertTrue(codes(Analyzer.analyzePaths(List.of(a, b)), "DEPENDENCY_MISSING").isEmpty());
    }

    @Test
    void 欠落した素材を報告する() throws IOException {
        Map<String, String> m = fixtureEntries("lack");
        m.remove("assets/lack/textures/block/test_block.png");
        m.remove("assets/lack/models/item/test_ingot.json");
        m.put("assets/lack/lang/ja_jp.json", "{\"item.lack.ghost\":\"幽霊\"}");
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("lack.jar", m)));
        var missing = codes(ir, "MISSING_ASSET").stream().map(d -> d.subject() + " " + d.message()).toList();
        assertEquals(3, missing.size(), missing.toString());
        assertTrue(missing.stream().anyMatch(s -> s.contains("textures/block/test_block.png")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("models/item/test_ingot.json")));
        assertTrue(missing.stream().anyMatch(s -> s.startsWith("lack:ghost")));
        assertFalse(hasErrors(ir), "素材の欠落は警告（変換自体は試せる）");
    }

    @Test
    void メタデータの無いJARとNeoForgeのJARはエラー() throws IOException {
        BridgeIr ir = Analyzer.analyzePaths(List.of(
                jar("plain.jar", Map.of("a.txt", "x")),
                jar("neo.jar", Map.of("META-INF/neoforge.mods.toml", "modLoader=\"javafml\""))));
        assertEquals(1, codes(ir, "MISSING_MOD_METADATA").size());
        assertEquals(1, codes(ir, "UNSUPPORTED_LOADER").size());
    }

    @Test
    void 壊れたfabric_mod_jsonはエラー() throws IOException {
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("bad.jar", Map.of("fabric.mod.json", "{ not json"))));
        assertEquals(Severity.ERROR, codes(ir, "INVALID_JSON").get(0).severity());
    }

    @Test
    void IRはJSONで往復でき_別スキーマは拒否する() throws IOException {
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar("fixture.jar", fixtureEntries("pmb_fixture"))));
        String json = IrJson.write(ir);
        assertEquals(json, IrJson.write(IrJson.read(json)));
        assertThrows(IllegalArgumentException.class, () -> IrJson.read("{\"schema\":\"other\",\"schemaVersion\":1}"));
        assertThrows(IllegalArgumentException.class, () -> IrJson.read(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")));
    }
}
