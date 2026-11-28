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
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

    private Map<String, String> restoredContents() throws IOException {
        Map<String, String> out = contentsWithoutLockFile();
        out.keySet().removeIf(path -> path.startsWith("modbridge-backups/"));
        return out;
    }

    private Map<String, String> contentsWithoutLockFile() throws IOException {
        Map<String, String> out = snapshot();
        assertEquals("", out.remove(BdsInstaller.OPERATION_LOCK_FILE), "空のファイルを後続の操作でも同じロック先に使う");
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
    void 同じUUIDで版が違うパックを組み合わせると何も変更せず拒否する() throws Exception {
        BdsInstaller.Plan initial = BdsInstaller.plan(bds, packs, null);
        BdsInstaller.apply(bds, packs, null, true);
        Path newer = dir.resolve("new-output");
        compilePacks(newer, "other_ingot");
        BdsInstaller.Plan updated = BdsInstaller.plan(bds, newer, null);
        assertTrue(updated.ok(), updated.problems().toString());
        assertEquals(initial.rpUuid(), updated.rpUuid());
        assertFalse(initial.rpVersion().equals(updated.rpVersion()));
        Files.copy(newer.resolve("modbridge-rp.mcpack"), packs.resolve("modbridge-rp.mcpack"),
                StandardCopyOption.REPLACE_EXISTING);
        Map<String, String> before = snapshot();
        BdsInstaller.Plan mixed = BdsInstaller.plan(bds, packs, null);
        assertFalse(mixed.ok());
        assertTrue(mixed.actions().isEmpty());
        assertTrue(mixed.problems().stream().anyMatch(reason -> reason.contains("UUID または版")));
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
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
        assertEquals(before, contentsWithoutLockFile());
    }

    @Test
    void 設定を別ワールドに切り替えても元のDBが使用中なら配置しない() throws Exception {
        Path alternate = Files.createDirectory(bds.resolve("worlds/Archive"));
        Files.writeString(alternate.resolve("level.dat"), "archived world");
        Files.writeString(bds.resolve("server.properties"), "level-name=Archive\n");
        Map<String, String> before = snapshot();
        try (FileChannel ch = FileChannel.open(world.resolve("db/LOCK"), StandardOpenOption.WRITE);
             FileLock lock = ch.lock()) {
            assertTrue(lock.isValid());
            var error = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, "Archive", true));
            assertTrue(error.getMessage().contains("ロック"), error.getMessage());
        }
        assertEquals(before, contentsWithoutLockFile());
        BdsInstaller.apply(bds, packs, "Archive", true);
        assertTrue(Files.isRegularFile(alternate.resolve("world_resource_packs.json")));
        assertEquals(OTHER, Files.readString(world.resolve("world_behavior_packs.json")));
    }

    @Test
    void 別ワールドの復旧でも起動対象のDBが使用中なら変更しない() throws Exception {
        Path alternate = Files.createDirectory(bds.resolve("worlds/Archive"));
        Files.writeString(alternate.resolve("level.dat"), "archived world");
        Map<String, String> original = snapshot();
        String id = BdsInstaller.apply(bds, packs, "Archive", true);
        Map<String, String> installed = snapshot();
        try (FileChannel ch = FileChannel.open(world.resolve("db/LOCK"), StandardOpenOption.WRITE);
             FileLock lock = ch.lock()) {
            assertTrue(lock.isValid());
            var error = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true));
            assertTrue(error.getMessage().contains("ロック"), error.getMessage());
        }
        assertEquals(installed, snapshot());
        BdsInstaller.rollback(bds, id, true);
        assertEquals(original, restoredContents());
    }

    @Test
    void DBロックの無いワールドでも別の配置と復旧は書き込めない() throws Exception {
        Files.delete(world.resolve("db/LOCK"));
        Map<String, String> original = snapshot();
        String id = BdsInstaller.apply(bds, packs, null, true);
        Map<String, String> installed = snapshot();
        try (var mutation = BdsInstaller.MutationLock.acquire(bds)) {
            mutation.ensureHeld();
            assertTrue(BdsInstaller.plan(bds, packs, null).ok(), "実行中でも dry-run は可能");
            var apply = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
            var rollback = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true));
            assertTrue(apply.getMessage().contains("ModBridge の配置または復旧を実行中"), apply.getMessage());
            assertTrue(rollback.getMessage().contains("ModBridge の配置または復旧を実行中"), rollback.getMessage());
        }
        assertEquals(installed, snapshot());
        BdsInstaller.rollback(bds, id, true);
        assertEquals(original, restoredContents());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void LOCKがなくても書き込み中のDBでは配置も復旧もしない() throws Exception {
        Files.delete(world.resolve("db/LOCK"));
        Path journal = world.resolve("db/000001.log");
        Files.writeString(world.resolve("db/MANIFEST-000001"), "manifest");
        Files.writeString(journal, "world state");
        Map<String, String> beforeApply = snapshot();
        try (var writer = FileChannel.open(journal, StandardOpenOption.WRITE)) {
            assertTrue(writer.isOpen());
            var error = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
            assertTrue(error.getMessage().contains("ロック"), error.getMessage());
        }
        assertEquals(beforeApply, contentsWithoutLockFile());
        String id = BdsInstaller.apply(bds, packs, null, true);
        Files.createFile(world.resolve("db/LOCK")); // 以前の実行で残ったが、使われていない LOCK
        Map<String, String> beforeRollback = snapshot();
        try (var writer = FileChannel.open(journal, StandardOpenOption.WRITE)) {
            assertTrue(writer.isOpen());
            var error = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true));
            assertTrue(error.getMessage().contains("ロック"), error.getMessage());
            assertEquals(beforeRollback, snapshot());
        }
        Files.delete(world.resolve("db/LOCK"));
        BdsInstaller.rollback(bds, id, true);
        assertEquals(beforeApply, restoredContents());
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
        assertEquals(before, restoredContents());
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, "../../etc", true));
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, false));
    }

    @Test
    void リンク経由で別フォルダーに共有パックを配置できない() throws Exception {
        Path external = Files.createDirectories(dir.resolve("other-profile"));
        Path marker = Files.writeString(external.resolve("keep.txt"), "keep");
        linkDirectory(bds.resolve("behavior_packs"), external);
        Map<String, String> before = snapshot();

        var plan = BdsInstaller.plan(bds, packs, null);
        assertFalse(plan.ok());
        assertTrue(plan.problems().getFirst().contains("behavior_packs"));
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
        assertEquals(before, snapshot());
        assertFalse(Files.exists(external.resolve(BdsInstaller.BP_DIR)));
        assertEquals("keep", Files.readString(marker));
    }

    @Test
    void リンク経由で別フォルダーにバックアップを作らない() throws Exception {
        Path external = Files.createDirectories(dir.resolve("other-saves"));
        linkDirectory(bds.resolve(BdsInstaller.BACKUP_DIR), external);
        assertFalse(BdsInstaller.plan(bds, packs, null).ok());
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
        try (Stream<Path> files = Files.list(external)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void リンク経由で別フォルダーの共有パックをrollbackできない() throws Exception {
        String id = BdsInstaller.apply(bds, packs, null, true);
        Path behavior = bds.resolve("behavior_packs");
        Path external = Files.move(behavior, dir.resolve("another-profile-packs"));
        linkDirectory(behavior, external);
        Path manifest = external.resolve(BdsInstaller.BP_DIR).resolve("manifest.json");
        String before = Files.readString(manifest);

        var error = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true));
        assertTrue(error.getMessage().contains("behavior_packs"));
        assertEquals(before, Files.readString(manifest));
    }

    @Test
    void リンク経由のバックアップを復旧に使わない() throws Exception {
        String id = BdsInstaller.apply(bds, packs, null, true);
        Path backup = bds.resolve(BdsInstaller.BACKUP_DIR).resolve(id);
        Path external = Files.move(backup, dir.resolve("exported-backup"));
        linkDirectory(backup, external);
        Map<String, String> before = snapshot();

        var error = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true));
        assertTrue(error.getMessage().contains(BdsInstaller.BACKUP_DIR));
        assertEquals(before, snapshot());
        assertTrue(Files.isRegularFile(external.resolve("backup.json")));
    }

    @Test
    void 配置前のバックアップでワールド内のリンクを拒否する() throws Exception {
        Path external = Files.createDirectories(dir.resolve("other-save-data"));
        Files.writeString(external.resolve("keep.txt"), "keep");
        linkDirectory(world.resolve("external-data"), external);

        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
        assertFalse(Files.exists(bds.resolve("behavior_packs").resolve(BdsInstaller.BP_DIR)));
        assertEquals("keep", Files.readString(external.resolve("keep.txt")));
    }

    @Test
    void rollback後に古いワールドを片付けるときリンク先を消さない() throws Exception {
        String id = BdsInstaller.apply(bds, packs, null, true);
        Path external = Files.createDirectories(dir.resolve("other-save-data"));
        Path marker = Files.writeString(external.resolve("keep.txt"), "keep");
        Path linked = world.resolve("external-data");
        linkDirectory(linked, external);

        BdsInstaller.rollback(bds, id, true);
        assertFalse(Files.exists(linked));
        assertEquals("keep", Files.readString(marker));
    }

    private static void linkDirectory(Path link, Path target) throws Exception {
        if (OS.WINDOWS.isCurrentOs()) {
            Process junction = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            assertTrue(junction.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, junction.exitValue(), new String(junction.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    @Test
    void 別のワールドが使う共有パックの版を更新できない() throws Exception {
        Path archive = Files.createDirectories(bds.resolve("worlds/Archive"));
        BdsInstaller.apply(bds, packs, null, true);
        BdsInstaller.apply(bds, packs, "Archive", true);
        var installed = BdsInstaller.plan(bds, packs, null);
        compilePacks(packs, "next_ingot");
        Map<String, String> before = snapshot();
        var update = BdsInstaller.plan(bds, packs, null);
        assertEquals(installed.rpUuid(), update.rpUuid());
        assertFalse(installed.rpVersion().equals(update.rpVersion()));
        assertFalse(update.ok());
        assertTrue(update.problems().getFirst().contains("worlds/Archive"));
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
        assertEquals(before, snapshot());

        Files.delete(archive.resolve("world_behavior_packs.json"));
        var resourceOnly = BdsInstaller.plan(bds, packs, null);
        assertFalse(resourceOnly.ok());
        assertTrue(resourceOnly.problems().getFirst().contains("resource_packs/modbridge-rp"));

        // 未使用なら、別のワールドがあっても更新できる。
        Files.delete(archive.resolve("world_resource_packs.json"));
        BdsInstaller.apply(bds, packs, null, true);
    }

    @Test
    void 別のワールドが使う共有パックをrollbackで消せない() throws Exception {
        Path archive = Files.createDirectories(bds.resolve("worlds/Archive"));
        String first = BdsInstaller.apply(bds, packs, null, true);
        BdsInstaller.apply(bds, packs, "Archive", true);
        Map<String, String> before = snapshot();

        var error = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, first, true));
        assertTrue(error.getMessage().contains("worlds/Archive"));
        assertEquals(before, snapshot());

        Files.delete(archive.resolve("world_behavior_packs.json"));
        Files.delete(archive.resolve("world_resource_packs.json"));
        BdsInstaller.rollback(bds, first, true);
        assertFalse(Files.exists(bds.resolve("behavior_packs/modbridge-bp")));
        assertFalse(Files.exists(bds.resolve("resource_packs/modbridge-rp")));
    }

    @Test
    void 別のワールドが使える完全なコピーが残る場合だけ共有パックを更新できる() throws Exception {
        Path archive = Files.createDirectories(bds.resolve("worlds/Archive"));
        BdsInstaller.apply(bds, packs, null, true);
        BdsInstaller.apply(bds, packs, "Archive", true);
        Path localBehavior = Files.createDirectories(archive.resolve("behavior_packs/local-copy"));
        Path sharedResource = Files.createDirectories(bds.resolve("resource_packs/other-copy"));
        Path behaviorPack = bds.resolve("behavior_packs/modbridge-bp");
        Path resourcePack = bds.resolve("resource_packs/modbridge-rp");
        Files.copy(behaviorPack.resolve("manifest.json"), localBehavior.resolve("manifest.json"));
        Files.copy(resourcePack.resolve("manifest.json"), sharedResource.resolve("manifest.json"));
        String behavior = Files.readString(archive.resolve("world_behavior_packs.json"));
        String resource = Files.readString(archive.resolve("world_resource_packs.json"));

        compilePacks(packs, "next_ingot");
        Map<String, String> before = snapshot();
        assertFalse(BdsInstaller.plan(bds, packs, null).ok());
        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
        assertEquals(before, snapshot());

        copyPack(behaviorPack, localBehavior);
        assertFalse(BdsInstaller.plan(bds, packs, null).ok());
        copyPack(resourcePack, sharedResource);
        Path contents = localBehavior.resolve("items/b/test_ingot.json");
        String original = Files.readString(contents);
        Files.writeString(contents, "{}");
        assertFalse(BdsInstaller.plan(bds, packs, null).ok());
        Files.writeString(contents, original);
        assertTrue(BdsInstaller.plan(bds, packs, null).ok());
        BdsInstaller.apply(bds, packs, null, true);
        assertEquals(behavior, Files.readString(archive.resolve("world_behavior_packs.json")));
        assertEquals(resource, Files.readString(archive.resolve("world_resource_packs.json")));
    }

    private static void copyPack(Path source, Path destination) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Test
    void 復旧用のコピーをすべて用意できなければ現在のワールドとパックを変えない() throws Exception {
        BdsInstaller.apply(bds, packs, null, true);
        Map<String, String> initial = restoredContents();
        compilePacks(packs, "next_ingot");
        String id = BdsInstaller.apply(bds, packs, null, true);
        Files.writeString(world.resolve("db/state"), "after update");
        Path blockedParent = bds.resolve("resource_packs");
        Path movedAside = dir.resolve("resource_packs_moved_aside");
        Files.move(blockedParent, movedAside);
        Files.writeString(blockedParent, "file blocks restore staging");
        Map<String, String> before = snapshot();

        assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true));
        assertEquals(before, snapshot());
        Files.delete(blockedParent);
        Files.move(movedAside, blockedParent);
        BdsInstaller.rollback(bds, id, true);
        assertEquals(initial, restoredContents());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void 復旧の最後にワールドの移動に失敗したら交換済みのパックも戻す() throws Exception {
        Path worldFile = world.resolve("level.dat");
        Files.writeString(worldFile, "initial world");
        BdsInstaller.apply(bds, packs, null, true);
        Map<String, String> initial = restoredContents();
        compilePacks(packs, "next_ingot");
        String id = BdsInstaller.apply(bds, packs, null, true);
        Files.writeString(worldFile, "world after update");
        Map<String, String> before = snapshot();
        var denyDelete = (OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption").getField("NOSHARE_DELETE").get(null);
        try (var reader = FileChannel.open(worldFile, StandardOpenOption.READ, denyDelete)) {
            assertTrue(reader.isOpen());
            assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true));
        }
        assertEquals(before, snapshot());
        BdsInstaller.rollback(bds, id, true);
        assertEquals(initial, restoredContents());
    }

    @Test
    void 配置の途中で失敗したら元に戻す() throws IOException {
        Files.writeString(world.resolve("world_resource_packs.json"), "{\"not\":\"an array\"}");
        Map<String, String> before = snapshot();
        var e = assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.apply(bds, packs, null, true));
        assertTrue(e.getMessage().contains("元に戻した"), e.getMessage());
        assertEquals(before, restoredContents());
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

    @Test
    void 書き換えられたbackup_jsonではほかのワールドを消さない() throws Exception {
        Path other = Files.createDirectories(bds.resolve("worlds/other/db"));
        Files.writeString(other.resolve("CURRENT"), "keep");
        String id = BdsInstaller.apply(bds, packs, null, true);
        Path meta = bds.resolve("modbridge-backups").resolve(id).resolve("backup.json");
        String original = Files.readString(meta);
        for (String tampered : List.of(
                original.replace("\"existed\": {", "\"existed\": {\n    \"worlds/other\": false,"),
                original.replace("\"existed\": {", "\"existed\": {\n    \"worlds/\": false,"),
                original.replace(": true", ": \"yes\""),
                original.replace("\"worlds/Bedrock level\": true", "\"worlds/Bedrock level\": false"),
                original.replace("\"world\": \"Bedrock level\"", "\"world\": \"..\""),
                original.replace("\"world\": \"Bedrock level\"", "\"world\": 3"))) {
            Files.writeString(meta, tampered);
            assertThrows(BdsInstaller.InstallException.class, () -> BdsInstaller.rollback(bds, id, true), tampered);
            assertEquals("keep", Files.readString(other.resolve("CURRENT")), "ほかのワールドは残る");
            assertTrue(Files.isDirectory(world), "対象のワールドも、検査に通るまで触らない");
        }
        Files.writeString(meta, original);
        BdsInstaller.rollback(bds, id, true);
    }

    @Test
    void ワールド名のドットや空白は使えない() {
        for (String bad : List.of(".", "..", " Bedrock level", "Bedrock level ", ".hidden", "a.", "a/b", "a\\b")) {
            assertFalse(BdsInstaller.plan(bds, packs, bad).ok(), bad);
        }
        assertTrue(BdsInstaller.plan(bds, packs, "Bedrock level").ok());
    }

    @Test
    void パックIDを変えて作り直しても古い項目を残さず_元の位置で置き換える() throws Exception {
        BdsInstaller.apply(bds, packs, null, true);
        String first = BdsInstaller.plan(bds, packs, null).bpUuid();
        // 自分の項目の後ろに、ほかのパックを足す
        String json = Files.readString(world.resolve("world_behavior_packs.json"));
        Files.writeString(world.resolve("world_behavior_packs.json"),
                json.replaceFirst("\\]\\s*$", ",\n  {\"pack_id\": \"99999999-8888-7777-6666-555555555555\", \"version\": [2, 0, 0]}\n]\n"));
        // パック ID を変えて作り直す
        BridgeIr ir = Analyzer.analyzePaths(List.of(dir.resolve("test_ingot.jar")));
        PackCompiler.Result r = PackCompiler.compile(ir, Analyzer.jarFiles(List.of(dir.resolve("test_ingot.jar"))),
                new PackCompiler.Settings("Renamed", "renamed"));
        PackPublisher.publish(packs, r.outputs());
        String second = BdsInstaller.plan(bds, packs, null).bpUuid();
        assertFalse(first.equals(second));
        BdsInstaller.apply(bds, packs, null, true);
        String after = Files.readString(world.resolve("world_behavior_packs.json"));
        assertEquals(0, count(after, first), "古い UUID の項目は残さない");
        assertEquals(1, count(after, second));
        int otherAt = after.indexOf("11111111-2222");
        int mineAt = after.indexOf(second);
        int laterAt = after.indexOf("99999999-8888");
        assertTrue(otherAt < mineAt && mineAt < laterAt, "自分の項目は元の位置に置く: " + after);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
