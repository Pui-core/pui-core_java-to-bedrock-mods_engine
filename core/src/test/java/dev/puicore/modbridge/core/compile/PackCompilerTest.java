package dev.puicore.modbridge.core.compile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.puicore.modbridge.core.adapter.AdapterRules;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.scan.Analyzer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackCompilerTest {
    @TempDir
    Path dir;

    // ---- テスト用の JAR と PNG ----

    /** 16x16 の PNG。alpha は全画素の不透明度。 */
    static byte[] png(int rgb, int alpha) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, (alpha << 24) | rgb);
            }
        }
        // 切り抜きの判定用に、alpha=255 でも 1 画素だけ透明にする場合は呼出し側で作る
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static byte[] pngWithHole() throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, (x == 0 && y == 0) ? 0 : 0xff336699);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0);
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return p;
    }

    private static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** fixture と同じ構成（アイテム 1、ブロック 1 とそのアイテム、言語 2 つ）。 */
    static Map<String, byte[]> fixture(String ns) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("fabric.mod.json", u("{\"schemaVersion\":1,\"id\":\"" + ns + "\",\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\"~26.3\"},"
                + "\"entrypoints\":{\"main\":[\"x.FixtureMod\"]}}"));
        m.put("assets/" + ns + "/items/test_ingot.json", u("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + ns + ":item/test_ingot\"}}"));
        m.put("assets/" + ns + "/items/test_block.json", u("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + ns + ":block/test_block\"}}"));
        m.put("assets/" + ns + "/models/item/test_ingot.json", u("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + ns + ":item/test_ingot\"}}"));
        m.put("assets/" + ns + "/models/block/test_block.json", u("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"" + ns + ":block/test_block\"}}"));
        m.put("assets/" + ns + "/blockstates/test_block.json", u("{\"variants\":{\"\":{\"model\":\"" + ns + ":block/test_block\"}}}"));
        m.put("assets/" + ns + "/textures/item/test_ingot.png", png(0xcc9933, 255));
        m.put("assets/" + ns + "/textures/block/test_block.png", png(0x888888, 255));
        m.put("assets/" + ns + "/lang/en_us.json", u("{\"item." + ns + ".test_ingot\":\"Test Ingot\",\"block." + ns + ".test_block\":\"Test Block\"}"));
        m.put("assets/" + ns + "/lang/ja_jp.json", u("{\"item." + ns + ".test_ingot\":\"テストインゴット\",\"block." + ns + ".test_block\":\"テストブロック\"}"));
        m.put("assets/" + ns + "/lang/xx_zz.json", u("{\"item." + ns + ".test_ingot\":\"?\"}"));
        m.put("data/" + ns + "/recipe/test_block.json", u("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"###\",\"###\",\"###\"],"
                + "\"key\":{\"#\":\"" + ns + ":test_ingot\"},\"result\":{\"id\":\"" + ns + ":test_block\",\"count\":1}}"));
        m.put("data/" + ns + "/recipe/test_ingot_from_block.json", u("{\"type\":\"minecraft:crafting_shapeless\","
                + "\"ingredients\":[\"" + ns + ":test_block\"],\"result\":{\"id\":\"" + ns + ":test_ingot\",\"count\":9}}"));
        return m;
    }

    private PackCompiler.Result compile(List<Path> inputs) {
        BridgeIr ir = Analyzer.analyzePaths(inputs);
        return PackCompiler.compile(ir, Analyzer.jarFiles(inputs), PackCompiler.Settings.DEFAULT);
    }

    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    private static String text(Map<String, byte[]> files, String name) {
        return new String(files.get(name), StandardCharsets.UTF_8);
    }

    private static PackCompiler.Entry entry(PackCompiler.Result r, String kind, String id) {
        return r.mapping().entries().stream().filter(e -> e.kind().equals(kind) && e.java().equals(id)).findFirst().orElseThrow();
    }

    // ---- テスト ----

    @Test
    void fixtureからリソースパックと対応表を作る() throws IOException {
        PackCompiler.Result r = compile(List.of(jar("fixture.jar", fixture("pmb_fixture"))));
        assertTrue(r.ok(), r.diagnostics().toString());
        Map<String, byte[]> pack = unzip(r.outputs().get(PackCompiler.PACK_FILE));
        assertTrue(pack.containsKey("manifest.json"));
        assertTrue(pack.containsKey("textures/pmb_fixture/item/test_ingot.png"));
        assertTrue(pack.containsKey("textures/pmb_fixture/block/test_block.png"));
        assertTrue(text(pack, "textures/item_texture.json").contains("\"pmb_fixture:item_test_ingot\""));
        assertTrue(text(pack, "textures/terrain_texture.json").contains("\"pmb_fixture:block_test_block\""));
        assertTrue(text(pack, "texts/en_US.lang").contains("item.pmb_fixture:test_ingot.name=Test Ingot\n"));
        assertTrue(text(pack, "texts/en_US.lang").contains("tile.pmb_fixture:test_block.name=Test Block\n"));
        assertTrue(text(pack, "texts/ja_JP.lang").contains("tile.pmb_fixture:test_block.name=テストブロック\n"));
        assertEquals("[\"en_US\",\"ja_JP\"]", text(pack, "texts/languages.json").replaceAll("\\s", ""),
                "Bedrock に無い言語（xx_ZZ）は入れない");

        assertEquals("GENERATED", entry(r, "ITEM", "pmb_fixture:test_ingot").status());
        var block = entry(r, "BLOCK", "pmb_fixture:test_block");
        assertEquals("GENERATED", block.status());
        assertEquals("opaque", block.render());
        assertEquals(6, block.textures().size());
        assertEquals("GENERATED", entry(r, "ITEM", "pmb_fixture:test_block").status(), "ブロックアイテム");
        String manifest = text(pack, "manifest.json");
        assertTrue(manifest.contains(r.mapping().pack().headerUuid()));
    }

    @Test
    void 同じ入力からは同じバイト列を作る_入力順にもよらない() throws IOException {
        Path a = jar("a.jar", fixture("mod_a"));
        Path b = jar("b.jar", fixture("mod_b"));
        PackCompiler.Result first = compile(List.of(a, b));
        PackCompiler.Result second = compile(List.of(b, a));
        assertTrue(first.ok(), first.diagnostics().toString());
        assertArrayEquals(first.outputs().get(PackCompiler.PACK_FILE), second.outputs().get(PackCompiler.PACK_FILE));
        assertArrayEquals(first.outputs().get(PackCompiler.MAPPING_FILE), second.outputs().get(PackCompiler.MAPPING_FILE));
        assertFalse(new String(first.outputs().get(PackCompiler.MAPPING_FILE), StandardCharsets.UTF_8).contains(dir.toString()));
    }

    @Test
    void 内容が変わればパックの版も変わり_ヘッダーのUUIDは変わらない() throws IOException {
        PackCompiler.Result first = compile(List.of(jar("f.jar", fixture("pmb_fixture"))));
        Map<String, byte[]> changed = fixture("pmb_fixture");
        changed.put("assets/pmb_fixture/textures/item/test_ingot.png", png(0x112233, 255));
        PackCompiler.Result second = compile(List.of(jar("f.jar", changed)));
        assertEquals(first.mapping().pack().headerUuid(), second.mapping().pack().headerUuid());
        assertNotEquals(first.mapping().pack().version(), second.mapping().pack().version());
        assertNotEquals(first.mapping().behaviorPack().version(), second.mapping().behaviorPack().version());
        for (var pack : List.of(first.mapping().pack(), first.mapping().behaviorPack(),
                second.mapping().pack(), second.mapping().behaviorPack())) {
            var version = pack.version();
            assertEquals(1, version.getFirst());
            assertTrue(version.get(1) >= 0 && version.get(1) <= 16_383);
            assertTrue(version.get(2) >= 0 && version.get(2) <= 16_383);
            assertEquals(pack.contentSha256().substring(0, 7),
                    "%07x".formatted((version.get(1) << 14) | version.get(2)));
        }
    }

    @Test
    void キャッシュの鍵は入力と設定で変わり_壊れたキャッシュは使わない() throws IOException {
        Path f = jar("f.jar", fixture("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(f));
        String key = PackPublisher.cacheKey(ir, PackCompiler.Settings.DEFAULT);
        assertEquals(key, PackPublisher.cacheKey(Analyzer.analyzePaths(List.of(f)), PackCompiler.Settings.DEFAULT));
        assertNotEquals(key, PackPublisher.cacheKey(ir, new PackCompiler.Settings("Other", "modbridge")));

        Map<String, byte[]> changed = fixture("pmb_fixture");
        changed.put("assets/pmb_fixture/textures/item/test_ingot.png", png(0x112233, 255));
        Files.createDirectories(dir.resolve("v2"));
        assertNotEquals(key, PackPublisher.cacheKey(Analyzer.analyzePaths(List.of(jar("v2/f.jar", changed))), PackCompiler.Settings.DEFAULT),
                "入力 JAR が変われば鍵も変わる（キャッシュの失効）");

        Path cache = dir.resolve("cache");
        PackCompiler.Result r = PackCompiler.compile(ir, Map.of("f.jar", f), PackCompiler.Settings.DEFAULT);
        assertTrue(r.ok(), r.diagnostics().toString());
        PackPublisher.writeCache(cache, key, r.outputs());
        Map<String, byte[]> hit = PackPublisher.readCache(cache, key);
        assertArrayEquals(r.outputs().get(PackCompiler.PACK_FILE), hit.get(PackCompiler.PACK_FILE));
        assertNull(PackPublisher.readCache(cache, "0".repeat(64)));
        Files.write(cache.resolve(key).resolve(PackCompiler.PACK_FILE), new byte[] {1, 2, 3});
        assertNull(PackPublisher.readCache(cache, key), "チェックサムが合わないキャッシュは使わない");
    }

    @Test
    void 同じ鍵のキャッシュへ並行で保存しても完全な成果物が残る() throws Exception {
        Path input = jar("f.jar", fixture("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(input));
        String key = PackPublisher.cacheKey(ir, PackCompiler.Settings.DEFAULT);
        PackCompiler.Result original = PackCompiler.compile(ir, Map.of("f.jar", input), PackCompiler.Settings.DEFAULT);
        assertTrue(original.ok());
        Path cache = dir.resolve("shared-cache");
        PackPublisher.writeCache(cache, key, original.outputs());

        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(writers + 1)) {
            List<Future<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < writers; worker++) {
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int write = 0; write < 3; write++) {
                        PackPublisher.writeCache(cache, key, original.outputs());
                    }
                    return null;
                }));
            }
            var reader = executor.submit(() -> {
                start.await();
                for (int read = 0; read < 24; read++) {
                    var result = PackPublisher.readCachedResult(cache, key);
                    if (result != null) {
                        assertArrayEquals(original.outputs().get(PackCompiler.ADDON_FILE),
                                result.outputs().get(PackCompiler.ADDON_FILE));
                    }
                }
                return null;
            });
            start.countDown();
            for (Future<Void> task : tasks) {
                task.get(15, TimeUnit.SECONDS);
            }
            reader.get(15, TimeUnit.SECONDS);
        }

        var restored = PackPublisher.readCachedResult(cache, key);
        assertNotNull(restored);
        assertEquals(original.diagnostics(), restored.diagnostics());
        for (String file : PackCompiler.OUTPUT_FILES) {
            assertArrayEquals(original.outputs().get(file), restored.outputs().get(file), file);
        }
        try (var paths = Files.list(cache)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith("." + key + ".tmp")
                    || p.getFileName().toString().contains(".old-")));
        }
    }

    @Test
    void 新しいフォルダーの移動に失敗すると既存のキャッシュを復元する() throws IOException {
        Path input = jar("f.jar", fixture("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(input));
        String key = PackPublisher.cacheKey(ir, PackCompiler.Settings.DEFAULT);
        PackCompiler.Result original = PackCompiler.compile(ir, Map.of("f.jar", input), PackCompiler.Settings.DEFAULT);
        assertTrue(original.ok());
        Path cache = dir.resolve("cache");
        PackPublisher.writeCache(cache, key, original.outputs());

        assertThrows(IOException.class, () -> PackPublisher.replaceDirectory(cache.resolve("missing"), cache.resolve(key)));

        var restored = PackPublisher.readCachedResult(cache, key);
        assertNotNull(restored);
        for (String file : PackCompiler.OUTPUT_FILES) {
            assertArrayEquals(original.outputs().get(file), restored.outputs().get(file), file);
        }
    }

    @Test
    void Bedrock識別子が衝突したら何も作らない() throws IOException {
        Map<String, byte[]> m = fixture("clash");
        m.put("assets/clash/items/a/b.json", m.get("assets/clash/items/test_ingot.json"));
        m.put("assets/clash/items/a_b.json", m.get("assets/clash/items/test_ingot.json"));
        PackCompiler.Result r = compile(List.of(jar("c.jar", m)));
        assertFalse(r.ok());
        assertTrue(r.outputs().isEmpty());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("ID_COLLISION") && d.subject().equals("clash:a_b")),
                r.diagnostics().toString());
    }

    @Test
    void 解析にERRORがあれば何も作らない() throws IOException {
        Map<String, byte[]> m = fixture("needs");
        m.put("fabric.mod.json", u("{\"id\":\"needs\",\"version\":\"1\",\"depends\":{\"absent\":\"*\"}}"));
        PackCompiler.Result r = compile(List.of(jar("n.jar", m)));
        assertFalse(r.ok());
        assertEquals("IR_HAS_ERRORS", r.diagnostics().get(0).code());
    }

    @Test
    void 解析の後でJARが変わったら拒否する() throws IOException {
        Path f = jar("f.jar", fixture("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(f));
        Map<String, byte[]> changed = fixture("pmb_fixture");
        changed.put("assets/pmb_fixture/lang/en_us.json", u("{}"));
        jar("f.jar", changed);
        PackCompiler.Result r = PackCompiler.compile(ir, Map.of("f.jar", f), PackCompiler.Settings.DEFAULT);
        assertFalse(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("INPUT_CHANGED")));
    }

    @Test
    void 非対応のモデルは理由を付けて外し_透過の種類を判定する() throws IOException {
        Map<String, byte[]> m = fixture("odd");
        // 変種が 2 つの blockstate（向き）
        m.put("assets/odd/blockstates/facing.json", u("{\"variants\":{\"facing=north\":{\"model\":\"odd:block/test_block\"},"
                + "\"facing=south\":{\"model\":\"odd:block/test_block\",\"y\":180}}}"));
        // 独自の形
        m.put("assets/odd/blockstates/shape.json", u("{\"variants\":{\"\":{\"model\":\"odd:block/shape\"}}}"));
        m.put("assets/odd/models/block/shape.json", u("{\"parent\":\"minecraft:block/block\",\"elements\":[]}"));
        // アニメーション
        m.put("assets/odd/items/anim.json", u("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"odd:item/anim\"}}"));
        m.put("assets/odd/models/item/anim.json", u("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"odd:item/anim\"}}"));
        m.put("assets/odd/textures/item/anim.png", png(0xffffff, 255));
        m.put("assets/odd/textures/item/anim.png.mcmeta", u("{\"animation\":{}}"));
        // 半透明と切り抜き
        m.put("assets/odd/blockstates/glass.json", u("{\"variants\":{\"\":{\"model\":\"odd:block/glass\"}}}"));
        m.put("assets/odd/models/block/glass.json", u("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"odd:block/glass\"}}"));
        m.put("assets/odd/textures/block/glass.png", png(0xffffff, 128));
        m.put("assets/odd/blockstates/leaf.json", u("{\"variants\":{\"\":{\"model\":\"odd:block/leaf\"}}}"));
        m.put("assets/odd/models/block/leaf.json", u("{\"parent\":\"minecraft:block/cube_column\",\"textures\":{\"end\":\"odd:block/leaf\",\"side\":\"#end\"}}"));
        m.put("assets/odd/textures/block/leaf.png", pngWithHole());

        PackCompiler.Result r = compile(List.of(jar("odd.jar", m)));
        assertTrue(r.ok(), r.diagnostics().toString());
        assertEquals("SKIPPED", entry(r, "BLOCK", "odd:facing").status());
        assertTrue(entry(r, "BLOCK", "odd:facing").notes().get(0).contains("変種"));
        assertEquals("SKIPPED", entry(r, "BLOCK", "odd:shape").status());
        assertTrue(entry(r, "BLOCK", "odd:shape").notes().get(0).contains("elements"));
        assertEquals("SKIPPED", entry(r, "ITEM", "odd:anim").status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("TEXTURE_ANIMATED_UNSUPPORTED")));
        assertEquals("blend", entry(r, "BLOCK", "odd:glass").render());
        assertEquals("alpha_test", entry(r, "BLOCK", "odd:leaf").render());
        assertEquals(6, entry(r, "BLOCK", "odd:leaf").textures().size(), "#end の参照を解決する");
    }

    @Test
    void 欠けたキャッシュや重複行のあるキャッシュは使わない() throws IOException {
        Path f = jar("f.jar", fixture("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(f));
        String key = PackPublisher.cacheKey(ir, PackCompiler.Settings.DEFAULT);
        PackCompiler.Result r = PackCompiler.compile(ir, Map.of("f.jar", f), PackCompiler.Settings.DEFAULT);
        Path cache = dir.resolve("cache");
        PackPublisher.writeCache(cache, key, r.outputs());
        Path sums = cache.resolve(key).resolve(PackPublisher.CHECKSUMS);
        List<String> lines = Files.readAllLines(sums);
        // 対応表だけの行にする（パックが欠ける）
        Files.write(sums, lines.stream().filter(l -> l.endsWith(PackCompiler.MAPPING_FILE)).toList());
        assertNull(PackPublisher.readCache(cache, key));
        // 同じ行の重複
        Files.write(sums, List.of(lines.get(0), lines.get(0), lines.get(1)));
        assertNull(PackPublisher.readCache(cache, key));
        // パックと対応表が別の生成のもの（チェックサムは書き直してある）
        Map<String, byte[]> other = fixture("pmb_fixture");
        other.put("assets/pmb_fixture/textures/item/test_ingot.png", png(0x010203, 255));
        Files.createDirectories(dir.resolve("o"));
        Path g = jar("o/f.jar", other);
        PackCompiler.Result r2 = compile(List.of(g));
        Map<String, byte[]> mixed = new LinkedHashMap<>();
        mixed.putAll(r.outputs());
        mixed.put(PackCompiler.PACK_FILE, r2.outputs().get(PackCompiler.PACK_FILE));
        PackPublisher.writeCache(cache, key, mixed);
        assertNull(PackPublisher.readCache(cache, key), "パックと対応表の内容のハッシュが合わない");
        var legacy = new LinkedHashMap<>(r.outputs());
        var mapping = com.google.gson.JsonParser.parseString(
                new String(legacy.get(PackCompiler.MAPPING_FILE), StandardCharsets.UTF_8)).getAsJsonObject();
        mapping.remove("diagnostics");
        legacy.put(PackCompiler.MAPPING_FILE, PackCompiler.json(mapping));
        PackPublisher.writeCache(cache, key, legacy);
        assertNull(PackPublisher.readCachedResult(cache, key), "診断を持たない旧キャッシュは作り直す");
        PackPublisher.writeCache(cache, key, r.outputs());
        assertTrue(PackPublisher.readCache(cache, key) != null);
    }

    @Test
    void 壊れたPNGと状態を持つblockstateは使わない() throws IOException {
        Map<String, byte[]> m = fixture("bad");
        // カラータイプ 2（アルファなし）で、IDAT が壊れている PNG
        byte[] good = png(0x123456, 255);
        byte[] broken = java.util.Arrays.copyOf(good, good.length);
        for (int i = 41; i < broken.length - 12; i++) {
            broken[i] = (byte) 0x55;
        }
        broken[25] = 2;
        m.put("assets/bad/textures/block/test_block.png", broken);
        m.put("assets/bad/blockstates/facing.json", u("{\"variants\":{\"facing=north\":{\"model\":\"bad:block/test_block\"}}}"));
        PackCompiler.Result r = compile(List.of(jar("bad.jar", m)));
        assertTrue(r.ok(), r.diagnostics().toString());
        assertEquals("SKIPPED", entry(r, "BLOCK", "bad:test_block").status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("TEXTURE_INVALID")), r.diagnostics().toString());
        assertEquals("SKIPPED", entry(r, "BLOCK", "bad:facing").status(), "facing=north だけの変種も状態を持つ");
        assertFalse(unzip(r.outputs().get(PackCompiler.PACK_FILE)).containsKey("textures/bad/block/test_block.png"),
                "使えないテクスチャはパックに入れない");
    }

    @Test
    void 参照されないPNGは読まない() throws IOException {
        Map<String, byte[]> m = fixture("big");
        byte[] noise = new byte[9 << 20]; // 個別の上限（8 MiB）を超える。圧縮が効かない内容にする
        new java.util.Random(1).nextBytes(noise);
        m.put("assets/big/textures/unused/huge.png", noise);
        PackCompiler.Result r = compile(List.of(jar("big.jar", m)));
        assertTrue(r.ok(), r.diagnostics().toString());
    }

    @Test
    void 別のJARが同じテクスチャIDで違う画像を持つと何も作らない() throws IOException {
        Map<String, byte[]> a = fixture("mod_a");
        a.put("assets/mod_a/models/block/test_block.json", u("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"shared:block/t\"}}"));
        a.put("assets/shared/textures/block/t.png", png(0x111111, 255));
        Map<String, byte[]> b = fixture("mod_b");
        b.put("assets/mod_b/models/block/test_block.json", u("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\"shared:block/t\"}}"));
        b.put("assets/shared/textures/block/t.png", png(0x222222, 255));
        PackCompiler.Result r = compile(List.of(jar("a.jar", a), jar("b.jar", b)));
        assertFalse(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("TEXTURE_CONFLICT")), r.diagnostics().toString());
    }

    @Test
    void 現在のフォルダーやルートには公開しない() throws IOException {
        PackCompiler.Result r = compile(List.of(jar("f.jar", fixture("pmb_fixture"))));
        assertThrows(IOException.class, () -> PackPublisher.publish(Path.of("."), r.outputs()));
        Path out = dir.resolve("x/../out2");
        PackPublisher.publish(out, r.outputs());
        assertTrue(Files.exists(dir.resolve("out2").resolve(PackCompiler.PACK_FILE)));
    }

    @Test
    void 大きすぎるPNGは展開せずに拒否する() {
        byte[] head = new byte[40];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, head, 0, 8);
        ByteBuffer b = ByteBuffer.wrap(head);
        b.putInt(8, 13);
        System.arraycopy(u("IHDR"), 0, head, 12, 4);
        b.putInt(16, 100_000);
        b.putInt(20, 100_000);
        head[24] = 8;
        head[25] = 6;
        var e = assertThrows(Png.PngException.class, () -> Png.inspect(head));
        assertTrue(e.getMessage().contains("上限"));
        assertThrows(Png.PngException.class, () -> Png.inspect(u("not a png at all, definitely not a png")));
    }

    // ---- ビヘイビアパック（Mode B） ----

    private static PackCompiler.Mapping mapping(PackCompiler.Result r) {
        return r.mapping();
    }

    private static dev.puicore.modbridge.core.compile.BehaviorPack.Entry bpEntry(PackCompiler.Result r, String kind, String id) {
        return mapping(r).behavior().stream().filter(e -> e.kind().equals(kind) && e.java().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void ビヘイビアパックにアイテム_ブロック_レシピを作り_mcaddonにまとめる() throws IOException {
        PackCompiler.Result r = compile(List.of(jar("fixture.jar", fixture("pmb_fixture"))));
        assertTrue(r.ok(), r.diagnostics().toString());
        Map<String, byte[]> bp = unzip(r.outputs().get(PackCompiler.BEHAVIOR_PACK_FILE));
        String item = text(bp, "items/pmb_fixture/test_ingot.json");
        assertTrue(item.contains("\"identifier\": \"pmb_fixture:test_ingot\""), item);
        assertTrue(item.contains("\"default\": \"pmb_fixture:item_test_ingot\""), item);
        String block = text(bp, "blocks/pmb_fixture/test_block.json");
        assertTrue(block.contains("\"minecraft:geometry\": \"minecraft:geometry.full_block\""), block);
        assertTrue(block.contains("\"*\": {"), block);
        assertTrue(block.contains("\"render_method\": \"opaque\""), block);
        assertFalse(bp.containsKey("items/pmb_fixture/test_block.json"), "ブロックアイテムはファイルを作らない");
        String shaped = text(bp, "recipes/pmb_fixture/test_block.json");
        assertTrue(shaped.contains("minecraft:recipe_shaped") && shaped.contains("\"item\": \"pmb_fixture:test_ingot\""), shaped);
        String shapeless = text(bp, "recipes/pmb_fixture/test_ingot_from_block.json");
        assertTrue(shapeless.contains("minecraft:recipe_shapeless") && shapeless.contains("\"count\": 9"), shapeless);
        assertEquals("GENERATED", bpEntry(r, "RECIPE", "pmb_fixture:test_block").status());
        assertEquals("PARTIAL", bpEntry(r, "ITEM", "pmb_fixture:test_ingot").status(), "コードで決まる性質は変換しない");
        assertTrue(bpEntry(r, "BLOCK", "pmb_fixture:test_block").notes().get(0).contains("コードで決まる"));

        // ビヘイビアパックはリソースパックに依存する
        String manifest = text(bp, "manifest.json");
        assertTrue(manifest.contains("\"type\": \"data\""), manifest);
        assertTrue(manifest.contains(mapping(r).pack().headerUuid()), "依存先はリソースパックのヘッダー");
        assertNotEquals(mapping(r).pack().headerUuid(), mapping(r).behaviorPack().headerUuid());

        Map<String, byte[]> addon = unzip(r.outputs().get(PackCompiler.ADDON_FILE));
        assertEquals(List.of(PackCompiler.BEHAVIOR_PACK_FILE, PackCompiler.PACK_FILE), List.copyOf(addon.keySet()));
        assertArrayEquals(r.outputs().get(PackCompiler.PACK_FILE), addon.get(PackCompiler.PACK_FILE));

        // Java のコードを持つ MOD は、変換しないことを明示する
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("JAVA_LOGIC_NOT_CONVERTED") && d.subject().equals("pmb_fixture")));
        assertEquals(1, mapping(r).notConverted().size());
    }

    @Test
    void 対象版の加工ルールからスクリプトと識別子を反映しキャッシュでも復元する() throws IOException {
        Path fixture = jar("fixture.jar", fixture("pmb_fixture"));
        List<Path> inputs = List.of(fixture);
        BridgeIr ir = Analyzer.analyzePaths(inputs);
        Path ruleFile = dir.resolve("rules.json");
        String declaration = """
                {"schema":"modbridge.adapter-rules","schemaVersion":1,"adapters":[{
                  "id":"test:machine","targetMod":"pmb_fixture","versions":["1.0.0"],"modes":["B"],
                  "mappings":[{"kind":"BLOCK","source":"pmb_fixture:test_block","destination":"test:machine"},
                              {"kind":"ITEM","source":"pmb_fixture:test_ingot","destination":"test:ingot"}],
                  "menus":[{"id":"test:menu","title":"加工台","blocks":["pmb_fixture:test_block"],"maxDistance":5,
                    "recipes":[{"id":"test:recipe","label":"加工","input":{"item":"minecraft:copper_ingot","count":2},
                      "output":{"item":"pmb_fixture:test_ingot","count":1}}]}]
                }]}
                """;
        Files.writeString(ruleFile, declaration);
        var settings = new PackCompiler.Settings("Fixture", "fixture", AdapterRules.readDocument(ruleFile));
        var result = PackCompiler.compile(ir, Analyzer.jarFiles(inputs), settings);
        assertTrue(result.ok(), result.diagnostics().toString());
        var bp = unzip(result.outputs().get(PackCompiler.BEHAVIOR_PACK_FILE));
        assertTrue(bp.containsKey("blocks/test/machine.json"));
        assertFalse(bp.containsKey("items/test/machine.json"));
        assertTrue(bp.containsKey("items/test/ingot.json"));
        assertTrue(text(bp, "recipes/pmb_fixture/test_block.json").contains("\"item\": \"test:ingot\""));
        assertTrue(text(bp, "recipes/pmb_fixture/test_block.json").contains("\"item\": \"test:machine\""));
        assertTrue(text(bp, BedrockMachineMenus.ENTRY).contains("\"block\": \"test:machine\""));
        assertTrue(text(bp, BedrockMachineMenus.ENTRY).contains("\"item\": \"test:ingot\""));
        assertTrue(text(bp, "manifest.json").contains("\"module_name\": \"@minecraft/server-ui\""));
        assertEquals(List.of(1, 21, 100), result.mapping().behaviorPack().minEngineVersion());
        String key = PackPublisher.cacheKey(ir, settings);
        Path cache = dir.resolve("cache");
        PackPublisher.writeCache(cache, key, result.outputs());
        assertEquals(result.mapping(), PackPublisher.readCachedResult(cache, key).mapping());

        Files.writeString(ruleFile, declaration.replace("1.0.0", "0.9.0"));
        var inactiveSettings = new PackCompiler.Settings("Fixture", "fixture", AdapterRules.readDocument(ruleFile));
        assertNotEquals(key, PackPublisher.cacheKey(ir, inactiveSettings));
        var inactive = PackCompiler.compile(ir, Analyzer.jarFiles(inputs), inactiveSettings);
        assertTrue(inactive.ok(), inactive.diagnostics().toString());
        assertFalse(unzip(inactive.outputs().get(PackCompiler.BEHAVIOR_PACK_FILE)).containsKey(BedrockMachineMenus.ENTRY));

        Files.writeString(ruleFile, declaration.replace(
                "\"output\":{\"item\":\"pmb_fixture:test_ingot\"", "\"output\":{\"item\":\"pmb_fixture:missing\""));
        var invalidSettings = new PackCompiler.Settings("Fixture", "fixture", AdapterRules.readDocument(ruleFile));
        var invalid = PackCompiler.compile(ir, Analyzer.jarFiles(inputs), invalidSettings);
        assertFalse(invalid.ok());
        assertTrue(invalid.outputs().isEmpty());
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.code().equals("ADAPTER_RULES_INVALID")));
    }

    @Test
    void 変換できないレシピは理由を付けて外し_バニラのIDは注記する() throws IOException {
        Map<String, byte[]> m = fixture("rec");
        m.put("data/rec/recipe/tagged.json", u("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"#c:ingots\"],"
                + "\"result\":{\"id\":\"rec:test_ingot\"}}"));
        m.put("data/rec/recipe/smelt.json", u("{\"type\":\"minecraft:smelting\",\"ingredient\":\"rec:test_block\","
                + "\"result\":{\"id\":\"rec:test_ingot\"}}"));
        m.put("data/rec/recipe/vanilla.json", u("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"minecraft:iron_ingot\","
                + "\"rec:test_ingot\"],\"result\":{\"id\":\"rec:test_block\"}}"));
        m.put("data/rec/recipe/unknown_result.json", u("{\"type\":\"minecraft:crafting_shapeless\",\"ingredients\":[\"rec:test_ingot\"],"
                + "\"result\":{\"id\":\"rec:not_converted\"}}"));
        PackCompiler.Result r = compile(List.of(jar("rec.jar", m)));
        assertTrue(r.ok(), r.diagnostics().toString());
        assertEquals("SKIPPED", bpEntry(r, "RECIPE", "rec:tagged").status());
        assertTrue(bpEntry(r, "RECIPE", "rec:tagged").notes().get(0).contains("タグ"));
        assertEquals("SKIPPED", bpEntry(r, "RECIPE", "rec:smelt").status());
        assertEquals("SKIPPED", bpEntry(r, "RECIPE", "rec:unknown_result").status());
        assertEquals("PARTIAL", bpEntry(r, "RECIPE", "rec:vanilla").status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("VANILLA_ID_ASSUMED") && d.subject().equals("rec:vanilla")));
        assertTrue(r.diagnostics().stream().filter(d -> d.code().equals("RECIPE_UNSUPPORTED")).count() >= 3);
    }

    @Test
    void レシピの識別子が衝突したら何も作らない() throws IOException {
        Map<String, byte[]> m = fixture("rc");
        byte[] recipe = m.get("data/rc/recipe/test_ingot_from_block.json");
        m.put("data/rc/recipe/a/b.json", recipe);
        m.put("data/rc/recipe/a_b.json", recipe);
        PackCompiler.Result r = compile(List.of(jar("rc.jar", m)));
        assertFalse(r.ok());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("ID_COLLISION") && d.subject().equals("rc:a_b")),
                r.diagnostics().toString());
    }

    @Test
    void patternとkeyが対応しない定形レシピは外す() throws IOException {
        Map<String, byte[]> m = fixture("pk");
        m.put("data/pk/recipe/undefined_char.json", u("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"XX\",\"X \"],"
                + "\"key\":{\"#\":\"pk:test_ingot\"},\"result\":{\"id\":\"pk:test_block\"}}"));
        m.put("data/pk/recipe/unused_key.json", u("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"##\"],"
                + "\"key\":{\"#\":\"pk:test_ingot\",\"X\":\"pk:test_block\"},\"result\":{\"id\":\"pk:test_block\"}}"));
        PackCompiler.Result r = compile(List.of(jar("pk.jar", m)));
        assertTrue(r.ok(), r.diagnostics().toString());
        assertEquals("SKIPPED", bpEntry(r, "RECIPE", "pk:undefined_char").status());
        assertEquals("SKIPPED", bpEntry(r, "RECIPE", "pk:unused_key").status());
    }

    @Test
    void 変換しないデータと素材のカテゴリを報告する() throws IOException {
        Map<String, byte[]> m = fixture("dn");
        m.put("data/dn/worldgen/configured_feature/ore.json", u("{}"));
        m.put("data/dn/loot_table/blocks/test_block.json", u("{}"));
        m.put("data/minecraft/tags/block/mineable/pickaxe.json", u("{\"values\":[\"dn:test_block\"]}"));
        m.put("assets/dn/sounds.json", u("{}"));
        m.put("assets/dn/sounds/hit.ogg", u("ogg"));
        PackCompiler.Result r = compile(List.of(jar("dn.jar", m)));
        assertTrue(r.ok(), r.diagnostics().toString());
        var d = r.diagnostics().stream().filter(x -> x.code().equals("DATA_NOT_CONVERTED")).findFirst().orElseThrow();
        assertTrue(d.message().contains("data/dn/worldgen 1"), d.message());
        assertTrue(d.message().contains("data/dn/loot_table 1"), d.message());
        assertTrue(d.message().contains("data/minecraft/tags 1"), d.message());
        assertTrue(d.message().contains("assets/dn/sounds 1"), d.message());
        assertTrue(d.message().contains("assets/dn/sounds.json 1"), "名前空間の直下のファイルも数える: " + d.message());
        assertFalse(d.message().contains("recipe"), "レシピは変換するので数えない");
        assertTrue(mapping(r).notConverted().stream().anyMatch(s -> s.startsWith("dn: ") && s.contains("worldgen")));
    }

    @Test
    void 対応表が書き換えられたキャッシュは使わない() throws IOException {
        Path f = jar("f.jar", fixture("pmb_fixture"));
        BridgeIr ir = Analyzer.analyzePaths(List.of(f));
        String key = PackPublisher.cacheKey(ir, PackCompiler.Settings.DEFAULT);
        PackCompiler.Result r = PackCompiler.compile(ir, Map.of("f.jar", f), PackCompiler.Settings.DEFAULT);
        Path cache = dir.resolve("cache");
        String json = new String(r.outputs().get(PackCompiler.MAPPING_FILE), StandardCharsets.UTF_8);
        Map<String, byte[]> tampered = new LinkedHashMap<>(r.outputs());
        tampered.put(PackCompiler.MAPPING_FILE, json.replaceFirst("\"behavior\": \\[[\\s\\S]*?\\n  \\]", "\"behavior\": []")
                .getBytes(StandardCharsets.UTF_8));
        assertNotEquals(json, new String(tampered.get(PackCompiler.MAPPING_FILE), StandardCharsets.UTF_8));
        PackPublisher.writeCache(cache, key, tampered);
        assertNull(PackPublisher.readCache(cache, key), "BP のファイルと対応表が合わない");
        PackPublisher.writeCache(cache, key, r.outputs());
        assertTrue(PackPublisher.readCache(cache, key) != null);
    }

    @Test
    void 公開に失敗しても旧成果物を残し_他のフォルダーは消さない() throws IOException {
        PackCompiler.Result r = compile(List.of(jar("f.jar", fixture("pmb_fixture"))));
        Path out = dir.resolve("out");
        PackPublisher.publish(out, r.outputs());
        byte[] published = Files.readAllBytes(out.resolve(PackCompiler.PACK_FILE));
        assertArrayEquals(r.outputs().get(PackCompiler.PACK_FILE), published);

        // 書込みに失敗する出力（存在しないサブフォルダー）
        Map<String, byte[]> broken = new LinkedHashMap<>(r.outputs());
        broken.put("missing/dir/file.bin", new byte[] {1});
        assertThrows(IOException.class, () -> PackPublisher.publish(out, broken));
        assertArrayEquals(published, Files.readAllBytes(out.resolve(PackCompiler.PACK_FILE)), "旧成果物が残る");
        try (var s = Files.list(dir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith(".out.")), "一時フォルダーを残さない");
        }

        // 印の無い、空でないフォルダーには公開しない
        Path user = Files.createDirectories(dir.resolve("user"));
        Files.writeString(user.resolve("important.txt"), "keep");
        assertThrows(IOException.class, () -> PackPublisher.publish(user, r.outputs()));
        assertEquals("keep", Files.readString(user.resolve("important.txt")));
    }
}
