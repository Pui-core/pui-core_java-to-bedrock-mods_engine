package dev.puicore.modbridge.core.compile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
        m.put("fabric.mod.json", u("{\"schemaVersion\":1,\"id\":\"" + ns + "\",\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\"~26.3\"}}"));
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
