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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        entries.forEach((k, v) -> bytes.put(k, v.getBytes(StandardCharsets.UTF_8)));
        Path p = dir.resolve(name);
        Files.write(p, zip(bytes));
        return p;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(os)) {
            for (var e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0);
                zip.putNextEntry(ze);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return os.toByteArray();
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
        // 3 MiB の同一文字。個別・合計の上限より小さいので、圧縮率（200 倍）の判定で止まる
        m.put("assets/bomb/lang/zz_zz.json", "0".repeat(3 << 20));
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
        // 依存が欠けた MOD（mod_b）は fail closed。mod_b に依存する mod_a にも伝わる
        assertTrue(ir.plans().stream().filter(p -> p.subject().equals("mod:mod_b")).allMatch(p -> p.status() == Status.ERROR));
        assertTrue(ir.plans().stream().filter(p -> p.subject().equals("mod:mod_a"))
                .allMatch(p -> p.status() == Status.ERROR && p.reason().contains("依存先 mod_b")), ir.plans().toString());
        var failed = codes(ir, "DEPENDENCY_FAILED");
        assertEquals(1, failed.size());
        assertEquals("mod_a", failed.get(0).subject());
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
    void オブジェクトでないfabric_mod_jsonと空ファイルはエラー() throws IOException {
        for (String body : List.of("[]", "", "\"x\"", "null")) {
            BridgeIr ir = Analyzer.analyzePaths(List.of(jar("bad.jar", Map.of("fabric.mod.json", body))));
            assertTrue(hasErrors(ir), "'" + body + "' " + ir.diagnostics());
            assertTrue(ir.mods().isEmpty());
        }
    }

    @Test
    void fabric_mod_jsonの型違いは例外ではなくエラー診断になり_他のJARの解析は続ける() throws IOException {
        List<String> bodies = List.of(
                "{\"id\":\"t1\",\"entrypoints\":{\"main\":[null]}}",
                "{\"id\":\"t2\",\"depends\":{\"a\":[{\"x\":1}]}}",
                "{\"id\":\"t3\",\"mixins\":[[1,2]]}",
                "{\"id\":\"t4\",\"entrypoints\":{\"main\":[[\"x\"]]}}",
                "{\"id\":\"t5\",\"entrypoints\":{\"main\":[{\"adapter\":\"kotlin\"}]}}",
                "{\"id\":\"t6\",\"provides\":\"alias\"}",
                "{\"id\":\"t7\",\"jars\":[1]}",
                "{\"id\":\"t8\",\"version\":3}");
        for (int i = 0; i < bodies.size(); i++) {
            String id = "t" + (i + 1);
            BridgeIr ir = Analyzer.analyzePaths(List.of(jar(id + ".jar", Map.of("fabric.mod.json", bodies.get(i))),
                    jar("ok.jar", fixtureEntries("ok_mod"))));
            var bad = codes(ir, "INVALID_MOD_METADATA");
            assertEquals(1, bad.size(), bodies.get(i) + " " + ir.diagnostics());
            assertEquals(id, bad.get(0).subject());
            assertTrue(ir.plans().stream().filter(p -> p.subject().equals("mod:" + id)).allMatch(p -> p.status() == Status.ERROR));
            assertTrue(ir.plans().stream().filter(p -> p.subject().equals("mod:ok_mod")).allMatch(p -> p.status() == Status.UNTESTED));
        }
    }

    @Test
    void JARが1つも無ければエラー() throws IOException {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        Files.writeString(empty.resolve("readme.txt"), "x");
        BridgeIr ir = Analyzer.analyzePaths(List.of(empty));
        assertEquals(1, codes(ir, "NO_INPUT").size());
        assertTrue(hasErrors(ir));
    }

    @Test
    void 拡張子が大文字のJARも読む() throws IOException {
        Path sub = Files.createDirectories(dir.resolve("upper"));
        Files.move(jar("UP.JAR", fixtureEntries("upper_mod")), sub.resolve("UP.JAR"));
        assertEquals(1, Analyzer.analyzePaths(List.of(sub)).mods().size());
    }

    @Test
    void 別フォルダーの同名JARはエラー() throws IOException {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Files.move(jar("x.jar", fixtureEntries("mod_x")), a.resolve("x.jar"));
        Files.move(jar("x.jar", fixtureEntries("mod_y")), b.resolve("x.jar"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(a, b));
        assertEquals(1, codes(ir, "DUPLICATE_INPUT_NAME").size());
        assertEquals(IrJson.write(ir), IrJson.write(Analyzer.analyzePaths(List.of(b, a))));
    }

    @Test
    void 同梱JARのMOD_IDで依存を満たせる() throws IOException {
        byte[] inner = zip(Map.of("fabric.mod.json",
                "{\"id\":\"inner_mod\",\"version\":\"1\",\"provides\":[\"inner_alias\"]}".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("fabric.mod.json", modJson("outer", "\"inner_mod\":\"*\",\"inner_alias\":\"*\"").replace("}}",
                "},\"jars\":[{\"file\":\"META-INF/jars/inner.jar\"},{\"file\":\"META-INF/jars/missing.jar\"}]}")
                .getBytes(StandardCharsets.UTF_8));
        outer.put("META-INF/jars/inner.jar", inner);
        Path p = dir.resolve("outer.jar");
        Files.write(p, zip(outer));
        BridgeIr ir = Analyzer.analyzePaths(List.of(p));
        assertTrue(codes(ir, "DEPENDENCY_MISSING").isEmpty(), ir.diagnostics().toString());
        assertEquals(List.of("inner_alias", "inner_mod"), ir.mods().get(0).nestedMods());
        assertEquals(1, codes(ir, "NESTED_JAR_UNREADABLE").size(), "jars に書かれているのに無い同梱 JAR は警告");
    }

    @Test
    void 同梱JARは宣言されたものだけを読み_展開量の合計で打ち切る() throws Exception {
        // 0.8 MiB のゼロを先頭に置き、fabric.mod.json に届くまで展開させる
        Map<String, byte[]> innerEntries = new LinkedHashMap<>();
        innerEntries.put("pad.bin", new byte[800 << 10]);
        innerEntries.put("fabric.mod.json", "{\"id\":\"inner\"}".getBytes(StandardCharsets.UTF_8));
        byte[] inner = zip(innerEntries);
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("fabric.mod.json", ("{\"id\":\"outer\",\"jars\":[{\"file\":\"META-INF/jars/n1.jar\"},"
                + "{\"file\":\"META-INF/jars/n2.jar\"},{\"file\":\"META-INF/jars/n3.jar\"}]}").getBytes(StandardCharsets.UTF_8));
        for (String n : List.of("n1", "n2", "n3", "extra")) {
            outer.put("META-INF/jars/" + n + ".jar", inner);
        }
        Path p = dir.resolve("nested-bomb.jar");
        Files.write(p, zip(outer));
        // 同梱 JAR の展開量の合計は 2 MiB まで（1 個あたり約 0.8 MiB なので 3 個目で止まる）
        var limits = new SafeArchive.Limits(1000, 64L << 20, 8L << 20, 2L << 20, 200);
        SafeArchive a = SafeArchive.open(p, limits);
        assertTrue(a.nested("META-INF/jars/n1.jar").fabricModJson() != null);
        assertTrue(a.nested("META-INF/jars/n2.jar").fabricModJson() != null);
        assertTrue(a.nested("META-INF/jars/n3.jar").error() != null, "合計の上限で打ち切る");
        assertEquals(null, a.nested("META-INF/jars/extra.jar"), "jars に書かれていない同梱 JAR は読まない");
    }

    @Test
    void 診断に絶対パスを入れない() throws IOException {
        Path broken = dir.resolve("broken.jar");
        Files.write(broken, new byte[] {'P', 'K', 3, 4, 0, 0});
        String json = IrJson.write(Analyzer.analyzePaths(List.of(broken, dir.resolve("missing.jar"))));
        assertFalse(json.contains(dir.toString()), json);
        assertFalse(json.contains(dir.toString().replace('\\', '/')), json);
    }

    @Test
    void 不正なIRは例外の種類によらずIllegalArgumentExceptionで拒否する() throws IOException {
        String good = IrJson.write(Analyzer.analyzePaths(List.of(jar("fixture.jar", fixtureEntries("pmb_fixture")))));
        for (String bad : List.of("[]", "1", "{\"schema\":[1]}", "{\"schema\":\"modbridge.bridge-ir\"}",
                "{\"schema\":\"modbridge.bridge-ir\",\"schemaVersion\":\"x\"}",
                good.replaceFirst("\"inputs\": \\[", "\"inputs\": 5, \"_\": ["),
                good.replace("\"status\": \"UNTESTED\"", "\"status\": \"SUPPORTED\""),
                good.replace("\"kind\": \"ITEM\"", "\"kind\": \"NOPE\""))) {
            assertThrows(IllegalArgumentException.class, () -> IrJson.read(bad), bad.length() > 80 ? bad.substring(0, 80) : bad);
        }
    }

    // ---- 実行時レジストリとの突き合わせ ----

    private Path runtime(String name, String minecraft, String mods, String... entries) throws IOException {
        StringBuilder e = new StringBuilder();
        for (String entry : entries) {
            String[] p = entry.split(" ");
            e.append(e.isEmpty() ? "" : ",").append("{\"registry\":\"").append(p[0]).append("\",\"id\":\"").append(p[1]).append("\"}");
        }
        Path p = dir.resolve(name);
        Files.writeString(p, "{\"schema\":\"modbridge.runtime-registry\",\"schemaVersion\":1,\"minecraft\":\"" + minecraft
                + "\",\"mods\":[" + mods + "],\"entries\":[" + e + "]}");
        return p;
    }

    private static final String LOADED = "{\"id\":\"pmb_fixture\",\"version\":\"1.0.0\"},{\"id\":\"other\",\"version\":\"1\"}";

    @Test
    void 実行時にだけある定義と静的にだけある定義を検出する() throws IOException {
        Path jar = jar("fixture.jar", fixtureEntries("pmb_fixture"));
        // 静的解析のレシピ pmb_fixture:test_block は実行時に無い。code_only_marker は実行時にだけある
        Path rt = runtime("rt.json", "26.3", LOADED, "item pmb_fixture:test_ingot", "item pmb_fixture:test_block",
                "block pmb_fixture:test_block", "item pmb_fixture:code_only_marker", "item other:thing", "block other:thing");
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar), rt);
        assertFalse(hasErrors(ir), ir.diagnostics().toString());

        var codeOnly = codes(ir, "CODE_ONLY_DEFINITION");
        assertEquals(1, codeOnly.size(), ir.diagnostics().toString());
        assertEquals("pmb_fixture:code_only_marker", codeOnly.get(0).subject());
        var def = ir.definitions().stream().filter(d -> d.id().equals("pmb_fixture:code_only_marker")).findFirst().orElseThrow();
        assertEquals(BridgeIr.Source.RUNTIME_REGISTRY, def.source());
        assertEquals("pmb_fixture", def.modId());
        assertTrue(ir.plans().stream().filter(p -> p.subject().equals("item:pmb_fixture:code_only_marker"))
                .allMatch(p -> p.status() == Status.UNTESTED
                        && p.method() == (p.mode() == Mode.A ? Method.ADAPTER : Method.UNSUPPORTED)), ir.plans().toString());

        var staticOnly = codes(ir, "STATIC_ONLY");
        assertEquals(1, staticOnly.size(), staticOnly.toString());
        assertTrue(staticOnly.get(0).message().startsWith("RECIPE"));

        var ingot = ir.definitions().stream().filter(d -> d.id().equals("pmb_fixture:test_ingot")).findFirst().orElseThrow();
        assertEquals("registered", ingot.attributes().get("runtime"));
        assertEquals(BridgeIr.Source.STATIC_ASSET, ingot.source());

        // 入力していない MOD（other）の登録は件数だけ
        var outside = codes(ir, "RUNTIME_OUTSIDE_INPUT");
        assertEquals(1, outside.size());
        assertTrue(outside.get(0).message().contains("2 件"));
        assertTrue(ir.inputs().stream().anyMatch(i -> i.name().equals("rt.json") && i.sha256().length() == 64));

        // 登録の並び順によらず同じ IR
        Path rt2 = runtime("rt.json", "26.3", LOADED, "block other:thing", "item other:thing", "item pmb_fixture:code_only_marker",
                "block pmb_fixture:test_block", "item pmb_fixture:test_block", "item pmb_fixture:test_ingot");
        String first = IrJson.write(ir);
        assertEquals(first.replaceAll("\"sha256\": \"[0-9a-f]+\"", ""),
                IrJson.write(Analyzer.analyzePaths(List.of(jar), rt2)).replaceAll("\"sha256\": \"[0-9a-f]+\"", ""));
    }

    @Test
    void 取得時に読み込まれていないMODは警告し_静的にだけある判定から外す() throws IOException {
        Path jar = jar("fixture.jar", fixtureEntries("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar), runtime("rt.json", "26.2", "{\"id\":\"other\",\"version\":\"1\"}"));
        assertEquals(1, codes(ir, "MOD_NOT_LOADED").size());
        assertTrue(codes(ir, "STATIC_ONLY").isEmpty());
        assertEquals(Severity.ERROR, codes(ir, "RUNTIME_MINECRAFT_MISMATCH").get(0).severity());
        // 入力した MOD が 1 つも読み込まれていない実行時レジストリは、突き合わせとして成り立たない
        assertEquals(Severity.ERROR, codes(ir, "RUNTIME_REGISTRY_UNRELATED").get(0).severity());
    }

    @Test
    void 入力と取得時でMODの版が違えばそのMODはfail_closed() throws IOException {
        Path jar = jar("fixture.jar", fixtureEntries("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar),
                runtime("rt.json", "26.3", "{\"id\":\"pmb_fixture\",\"version\":\"9.9\"}", "item pmb_fixture:test_ingot"));
        assertEquals(1, codes(ir, "MOD_VERSION_MISMATCH").size());
        assertTrue(ir.plans().stream().filter(p -> p.subject().equals("mod:pmb_fixture")).allMatch(p -> p.status() == Status.ERROR));
        assertTrue(codes(ir, "RUNTIME_REGISTRY_UNRELATED").isEmpty());
    }

    @Test
    void 不正な実行時レジストリはエラー() throws IOException {
        Path jar = jar("fixture.jar", fixtureEntries("pmb_fixture"));
        for (String bad : List.of("[]", "{\"schema\":\"modbridge.runtime-registry\",\"schemaVersion\":2}",
                "{\"schema\":\"modbridge.runtime-registry\",\"schemaVersion\":1,\"minecraft\":\"26.3\",\"mods\":[],\"entries\":[{\"registry\":\"entity\",\"id\":\"a:b\"}]}",
                "{\"schema\":\"modbridge.runtime-registry\",\"schemaVersion\":1,\"minecraft\":\"26.3\",\"mods\":[],\"entries\":[{\"registry\":\"item\",\"id\":\"A B\"}]}",
                "{\"schema\":\"modbridge.runtime-registry\",\"schemaVersion\":1,\"minecraft\":\"26.3\",\"mods\":{},\"entries\":[]}",
                "{\"schema\":\"modbridge.runtime-registry\",\"schemaVersion\":1,\"minecraft\":\"26.3\",\"mods\":[],\"entries\":[{\"registry\":\"item\",\"id\":\"m:b\"},{\"registry\":\"item\",\"id\":\"m:b\"}]}",
                "{\"schema\":\"modbridge.runtime-registry\",\"schemaVersion\":1,\"minecraft\":\"26.3\",\"mods\":[{\"id\":\"m\",\"version\":\"1\"},{\"id\":\"m\",\"version\":\"2\"}],\"entries\":[]}")) {
            Path rt = dir.resolve("bad-rt.json");
            Files.writeString(rt, bad);
            BridgeIr ir = Analyzer.analyzePaths(List.of(jar), rt);
            assertEquals(1, codes(ir, "RUNTIME_REGISTRY_INVALID").size(), bad);
        }
        BridgeIr missing = Analyzer.analyzePaths(List.of(jar), dir.resolve("none.json"));
        assertEquals(1, codes(missing, "IO_ERROR").size());
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
