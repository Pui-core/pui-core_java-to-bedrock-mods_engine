package dev.puicore.modbridge.core.bds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.puicore.modbridge.core.compile.PackCompiler;
import dev.puicore.modbridge.core.compile.PackPublisher;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.scan.Analyzer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BdsInstallerTest {
    @TempDir
    Path dir;

    Path bds;
    Path world;
    Path packs;
    static final String OTHER = "[\n  {\"pack_id\": \"11111111-2222-3333-4444-555555555555\", \"version\": [1, 0, 0]}\n]\n";

    @BeforeEach
    void setUp() throws IOException {
        bds = Files.createDirectories(dir.resolve("bds"));
        Files.writeString(bds.resolve("bedrock_server.exe"), "");
        Files.writeString(bds.resolve("server.properties"), "server-name=test\nlevel-name=Bedrock level\n");
        world = Files.createDirectories(bds.resolve("worlds/Bedrock level/db"));
        Files.writeString(world.resolve("LOCK"), "");
        Files.writeString(world.resolve("CURRENT"), "MANIFEST-000001\n");
        world = world.getParent();
        Files.writeString(world.resolve("world_behavior_packs.json"), OTHER);
        packs = dir.resolve("packs");
        compilePacks(packs, "test_ingot");
    }

    /** アイテム 1 つの MOD から、compile の出力フォルダーを作る。 */
    void compilePacks(Path out, String item) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xff112233);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Map<String, byte[]> m = new TreeMap<>();
        m.put("fabric.mod.json", u("{\"id\":\"b\",\"version\":\"1\"}"));
        m.put("assets/b/items/" + item + ".json", u("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"b:item/" + item + "\"}}"));
        m.put("assets/b/models/item/" + item + ".json", u("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"b:item/" + item + "\"}}"));
        m.put("assets/b/textures/item/" + item + ".png", png.toByteArray());
        Path jar = dir.resolve(item + ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : m.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        BridgeIr ir = Analyzer.analyzePaths(List.of(jar));
        PackCompiler.Result r = PackCompiler.compile(ir, Analyzer.jarFiles(List.of(jar)), PackCompiler.Settings.DEFAULT);
        assertTrue(r.ok(), r.diagnostics().toString());
        PackPublisher.publish(out, r.outputs());
    }

    static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** BDS フォルダーの中身（パスと内容）。変更の有無を比べる。 */
    Map<String, String> snapshot() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(bds)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(bds.relativize(p).toString().replace('\\', '/'), new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void dry_runは計画だけを返して何も変更しない() throws IOException {
        Map<String, String> before = snapshot();
        BdsInstaller.Plan plan = BdsInstaller.plan(bds, packs, null);
        assertTrue(plan.ok(), plan.problems().toString());
        assertEquals("Bedrock level", plan.world());
        assertEquals(List.of("BACKUP", "CREATE_DIR", "CREATE_DIR", "UPDATE_JSON", "UPDATE_JSON"),
                plan.actions().stream().map(BdsInstaller.Action::type).toList());
        assertEquals(before, snapshot());
    }

    @Test
    void 止めたことを明示しないと配置しない() throws IOException {
        Map<String, String> before = snapshot();
        var e = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, false));
        assertTrue(e.getMessage().contains("--confirm-stopped"));
        assertEquals(before, snapshot());
    }

    @Test
    void ワールドのDBがロックされていればBDSが動いているとみなして配置しない() throws IOException {
        Map<String, String> before = snapshot();
        try (FileChannel ch = FileChannel.open(world.resolve("db/LOCK"), StandardOpenOption.WRITE); FileLock l = ch.lock()) {
            assertTrue(l.isValid());
            var e = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
            assertTrue(e.getMessage().contains("ロック"), e.getMessage());
        }
        assertEquals(before, snapshot());
    }

    @Test
    void 配置すると自分の項目だけを足し_ほかのパックと世界のデータは残す_やり直しても重複しない() throws IOException, Exception {
        String id = BdsInstaller.apply(bds, packs, null, true);
        assertTrue(Files.isRegularFile(bds.resolve("behavior_packs/modbridge-bp/manifest.json")));
        assertTrue(Files.isRegularFile(bds.resolve("resource_packs/modbridge-rp/manifest.json")));
        assertTrue(Files.isRegularFile(bds.resolve("behavior_packs/modbridge-bp/items/b/test_ingot.json")));
        String bpJson = Files.readString(world.resolve("world_behavior_packs.json"));
        assertTrue(bpJson.contains("11111111-2222-3333-4444-555555555555"), "ほかのパックを残す");
        BdsInstaller.Plan plan = BdsInstaller.plan(bds, packs, null);
        assertEquals(1, count(bpJson, plan.bpUuid()));
        assertTrue(Files.readString(world.resolve("world_resource_packs.json")).contains(plan.rpUuid()));
        assertEquals("MANIFEST-000001\n", Files.readString(world.resolve("db/CURRENT")), "ワールドの DB には触らない");
        assertTrue(Files.isRegularFile(bds.resolve("modbridge-backups").resolve(id).resolve("backup.json")));
        assertFalse(Files.exists(bds.resolve("modbridge-backups").resolve(id).resolve("worlds/Bedrock level/db/LOCK")),
                "ロックファイルはバックアップしない");

        // 内容を変えてやり直す
        compilePacks(packs, "other_ingot");
        BdsInstaller.apply(bds, packs, null, true);
        String again = Files.readString(world.resolve("world_behavior_packs.json"));
        assertEquals(1, count(again, plan.bpUuid()), "同じパックの項目は 1 つだけ");
        assertFalse(Files.exists(bds.resolve("behavior_packs/modbridge-bp/items/b/test_ingot.json")), "古い中身は残さない");
        assertTrue(Files.exists(bds.resolve("behavior_packs/modbridge-bp/items/b/other_ingot.json")));
    }

    @Test
    void rollbackで配置の前に戻る() throws Exception {
        Map<String, String> before = snapshot();
        String id = BdsInstaller.apply(bds, packs, null, true);
        BdsInstaller.rollback(bds, id, true);
        Map<String, String> after = snapshot();
        after.keySet().removeIf(k -> k.startsWith("modbridge-backups/"));
        assertEquals(before, after);
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, "../../etc", true));
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, false));
    }

    @Test
    void 配置の途中で失敗したら元に戻す() throws IOException {
        Files.writeString(world.resolve("world_resource_packs.json"), "{\"not\":\"an array\"}");
        Map<String, String> before = snapshot();
        var e = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
        assertTrue(e.getMessage().contains("元に戻した"), e.getMessage());
        Map<String, String> after = snapshot();
        after.keySet().removeIf(k -> k.startsWith("modbridge-backups/"));
        assertEquals(before, after);
    }

    @Test
    void ワールドやパックが無ければ計画の問題として返す() throws IOException {
        BdsInstaller.Plan noWorld = BdsInstaller.plan(bds, packs, "missing");
        assertFalse(noWorld.ok());
        assertTrue(noWorld.problems().get(0).contains("worlds/missing"));
        BdsInstaller.Plan noPacks = BdsInstaller.plan(bds, dir.resolve("nothing"), null);
        assertFalse(noPacks.ok());
        assertFalse(BdsInstaller.plan(dir.resolve("not-bds"), packs, null).ok());
        assertFalse(BdsInstaller.plan(bds, packs, "../escape").ok(), "ワールド名に区切り文字は使えない");
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
