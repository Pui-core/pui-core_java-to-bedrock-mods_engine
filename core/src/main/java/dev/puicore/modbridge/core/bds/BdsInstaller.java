package dev.puicore.modbridge.core.bds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.puicore.modbridge.core.scan.SafeArchive;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 生成したアドオンを BDS（Bedrock Dedicated Server）のワールドに配置する。
 *
 * <ul>
 *   <li>既定は dry-run（計画を返すだけで、何も変更しない）</li>
 *   <li>apply は、呼出し側が「BDS を止めた」と明示し、さらにワールドの DB のロックを取れるとき（BDS が動いていないとき）だけ行う</li>
 *   <li>変更の前に、ワールド・パックのフォルダー・パック一覧の JSON をバックアップする。rollback でそこから戻せる</li>
 *   <li>触るのは、自分のパックのフォルダー（{@code behavior_packs/modbridge-bp}・{@code resource_packs/modbridge-rp}）と、
 *       ワールドの {@code world_behavior_packs.json}・{@code world_resource_packs.json} の自分の項目だけ</li>
 * </ul>
 */
public final class BdsInstaller {
    public static final String BP_DIR = "modbridge-bp";
    public static final String RP_DIR = "modbridge-rp";
    public static final String BACKUP_DIR = "modbridge-backups";
    static final long MAX_PACK_BYTES = 256L << 20;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern WORLD_NAME = Pattern.compile("[^/\\\\:*?\"<>|\\x00-\\x1f]{1,128}");
    private static final Pattern BACKUP_ID = Pattern.compile("[0-9]{8}T[0-9]{6}Z(-[0-9]+)?");

    /** 計画の 1 手順。 */
    public record Action(String type, String target, String detail) {}

    /** 計画。{@code problems} が空でないと apply できない。 */
    public record Plan(String world, List<Action> actions, List<String> problems, String bpUuid, List<Integer> bpVersion,
            String rpUuid, List<Integer> rpVersion) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /** 失敗。メッセージは利用者向け。 */
    public static final class InstallException extends Exception {
        private static final long serialVersionUID = 1L;

        InstallException(String message) {
            super(message);
        }

        InstallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Pack(String uuid, List<Integer> version, Map<String, byte[]> files) {}

    private BdsInstaller() {}

    // ---- 計画（dry-run） ----

    /**
     * 配置の計画を作る。何も変更しない。
     *
     * @param bds     BDS のフォルダー（bedrock_server と server.properties があるところ）
     * @param packDir {@code modbridge compile} の出力フォルダー
     * @param world   ワールド名。null なら server.properties の level-name
     */
    public static Plan plan(Path bds, Path packDir, String world) {
        List<String> problems = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        Path root = bds.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return new Plan(world, actions, List.of("BDS のフォルダーが無い（シンボリックリンクは使えない）"), null, null, null, null);
        }
        if (!Files.isRegularFile(root.resolve("bedrock_server")) && !Files.isRegularFile(root.resolve("bedrock_server.exe"))) {
            problems.add("bedrock_server（.exe）が無い。BDS のフォルダーを指定する");
        }
        String w = world;
        if (w == null) {
            w = levelName(root, problems);
        }
        if (w != null && !WORLD_NAME.matcher(w).matches()) {
            problems.add("ワールド名に使えない文字がある");
            w = null;
        }
        Pack bp = null;
        Pack rp = null;
        try {
            bp = readPack(packDir.resolve("modbridge-bp.mcpack"), "data");
            rp = readPack(packDir.resolve("modbridge-rp.mcpack"), "resources");
        } catch (InstallException e) {
            problems.add(e.getMessage());
        }
        if (w != null) {
            Path worldDir = root.resolve("worlds").resolve(w);
            if (!Files.isDirectory(worldDir, LinkOption.NOFOLLOW_LINKS)) {
                problems.add("ワールド worlds/" + w + " が無い。BDS を一度起動してワールドを作ってから配置する");
            }
            for (String dir : List.of("behavior_packs/" + BP_DIR, "resource_packs/" + RP_DIR)) {
                Path p = root.resolve(dir);
                if (Files.isSymbolicLink(p)) {
                    problems.add(dir + " がシンボリックリンクになっている");
                }
            }
            if (bp != null && rp != null) {
                actions.add(new Action("BACKUP", BACKUP_DIR + "/<時刻>/",
                        "worlds/" + w + "、behavior_packs/" + BP_DIR + "、resource_packs/" + RP_DIR + " を保存"));
                actions.add(new Action(Files.exists(root.resolve("behavior_packs/" + BP_DIR)) ? "REPLACE_DIR" : "CREATE_DIR",
                        "behavior_packs/" + BP_DIR, "ビヘイビアパック " + bp.uuid() + " " + bp.version() + "（" + bp.files().size() + " ファイル）"));
                actions.add(new Action(Files.exists(root.resolve("resource_packs/" + RP_DIR)) ? "REPLACE_DIR" : "CREATE_DIR",
                        "resource_packs/" + RP_DIR, "リソースパック " + rp.uuid() + " " + rp.version() + "（" + rp.files().size() + " ファイル）"));
                actions.add(new Action("UPDATE_JSON", "worlds/" + w + "/world_behavior_packs.json", "pack_id " + bp.uuid() + " を " + bp.version() + " にする"));
                actions.add(new Action("UPDATE_JSON", "worlds/" + w + "/world_resource_packs.json", "pack_id " + rp.uuid() + " を " + rp.version() + " にする"));
            }
        }
        return new Plan(w, List.copyOf(actions), List.copyOf(problems), bp == null ? null : bp.uuid(), bp == null ? null : bp.version(),
                rp == null ? null : rp.uuid(), rp == null ? null : rp.version());
    }

    // ---- 適用 ----

    /**
     * 計画どおりに配置する。BDS を止めたことの明示（{@code confirmedStopped}）と、ワールドの DB のロックが要る。
     *
     * @return 作ったバックアップの ID（rollback に使う）
     */
    public static String apply(Path bds, Path packDir, String world, boolean confirmedStopped) throws InstallException {
        Plan plan = plan(bds, packDir, world);
        if (!plan.ok()) {
            throw new InstallException("配置できない: " + String.join(" / ", plan.problems()));
        }
        if (!confirmedStopped) {
            throw new InstallException("BDS を止めてから、--confirm-stopped を付けて実行する");
        }
        Path root = bds.toAbsolutePath().normalize();
        Path worldDir = root.resolve("worlds").resolve(plan.world());
        Pack bp;
        Pack rp;
        try {
            bp = readPack(packDir.resolve("modbridge-bp.mcpack"), "data");
            rp = readPack(packDir.resolve("modbridge-rp.mcpack"), "resources");
        } catch (InstallException e) {
            throw e;
        }
        try (WorldLock lock = WorldLock.acquire(worldDir)) {
            lock.ensureHeld();
            String id = backup(root, plan.world());
            try {
                writePackDir(root.resolve("behavior_packs"), BP_DIR, bp);
                writePackDir(root.resolve("resource_packs"), RP_DIR, rp);
                updateWorldJson(worldDir.resolve("world_behavior_packs.json"), bp);
                updateWorldJson(worldDir.resolve("world_resource_packs.json"), rp);
            } catch (IOException | RuntimeException e) {
                try {
                    restore(root, id, false); // 配置で変えたもの（パックのフォルダーと 2 つの JSON）だけを戻す
                } catch (IOException | RuntimeException r) {
                    e.addSuppressed(r);
                    throw new InstallException("配置に失敗し、自動の復旧にも失敗した。バックアップ " + id + " から rollback する", e);
                }
                throw new InstallException("配置に失敗したので、バックアップ " + id + " から元に戻した（" + e.getClass().getSimpleName() + "）", e);
            }
            return id;
        } catch (IOException e) {
            throw new InstallException("配置できない（" + e.getMessage() + "）", e);
        }
    }

    /** バックアップから戻す。apply と同じく、止めたことの明示とロックが要る。 */
    public static void rollback(Path bds, String backupId, boolean confirmedStopped) throws InstallException {
        if (backupId == null || !BACKUP_ID.matcher(backupId).matches()) {
            throw new InstallException("バックアップ ID の形式が違う");
        }
        if (!confirmedStopped) {
            throw new InstallException("BDS を止めてから、--confirm-stopped を付けて実行する");
        }
        Path root = bds.toAbsolutePath().normalize();
        Path dir = root.resolve(BACKUP_DIR).resolve(backupId);
        JsonObject meta = readJson(dir.resolve("backup.json"));
        if (meta == null || !meta.has("world")) {
            throw new InstallException("バックアップ " + backupId + " が無いか壊れている");
        }
        String world = meta.get("world").getAsString();
        if (!WORLD_NAME.matcher(world).matches()) {
            throw new InstallException("バックアップのワールド名が不正");
        }
        // 動いていないことをロックで確かめてから、ロックを放して戻す（ワールドのフォルダーごと置き換えるため）
        try {
            WorldLock.acquire(root.resolve("worlds").resolve(world)).close();
        } catch (IOException e) {
            throw new InstallException("元に戻せない（" + e.getMessage() + "）", e);
        }
        try {
            restore(root, backupId, true);
        } catch (IOException e) {
            throw new InstallException("元に戻せない（" + e.getMessage() + "）", e);
        }
    }

    // ---- ロック ----

    /**
     * ワールドの DB（LevelDB）のロック。BDS が動いていれば、ロックを取れない。
     * DB がまだ無いワールドでは、ロックの検査を省く（呼出し側の明示だけに頼る）。
     */
    private static final class WorldLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private WorldLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static WorldLock acquire(Path worldDir) throws IOException {
            Path lockFile = worldDir.resolve("db").resolve("LOCK");
            if (!Files.isRegularFile(lockFile)) {
                return new WorldLock(null, null);
            }
            FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException | IOException e) {
                l = null;
            }
            if (l == null) {
                ch.close();
                throw new IOException("ワールドの DB がロックされている。BDS が動いているので止める");
            }
            return new WorldLock(ch, l);
        }

        /** ロックを持っている間に呼ぶ（DB の無いワールドでは何もしない）。 */
        void ensureHeld() throws IOException {
            if (lock != null && !lock.isValid()) {
                throw new IOException("ワールドの DB のロックが外れた");
            }
        }

        @Override
        public void close() throws IOException {
            if (lock != null) {
                lock.release();
            }
            if (channel != null) {
                channel.close();
            }
        }
    }

    // ---- バックアップと復元 ----

    private static String backup(Path root, String world) throws IOException {
        String base = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(ZonedDateTime.now(ZoneOffset.UTC));
        Path backups = root.resolve(BACKUP_DIR);
        Files.createDirectories(backups);
        String id = base;
        for (int i = 2; Files.exists(backups.resolve(id)); i++) {
            id = base + "-" + i;
        }
        Path dir = backups.resolve(id);
        Path tmp = Files.createTempDirectory(backups, "." + id + ".tmp");
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("world", world);
            Map<String, Boolean> existed = new TreeMap<>();
            for (String rel : List.of("worlds/" + world, "behavior_packs/" + BP_DIR, "resource_packs/" + RP_DIR)) {
                Path src = root.resolve(rel);
                boolean exists = Files.exists(src, LinkOption.NOFOLLOW_LINKS);
                existed.put(rel, exists);
                if (exists) {
                    copyTree(src, tmp.resolve(rel), true);
                }
            }
            meta.put("existed", existed);
            Files.writeString(tmp.resolve("backup.json"), GSON.toJson(meta) + "\n", StandardCharsets.UTF_8);
            move(tmp, dir);
        } finally {
            deleteTree(tmp);
        }
        return id;
    }

    /**
     * バックアップから戻す。
     *
     * @param fullWorld true ならワールドのフォルダーごと戻す（rollback）。false なら、ワールドのうち配置で変える
     *                  2 つの JSON だけを戻す（配置の失敗時。ワールドの DB はロック中で、配置では触らないため）
     */
    private static void restore(Path root, String id, boolean fullWorld) throws IOException {
        Path dir = root.resolve(BACKUP_DIR).resolve(id);
        JsonObject meta = readJson(dir.resolve("backup.json"));
        if (meta == null || !(meta.get("existed") instanceof JsonObject existed)) {
            throw new IOException("バックアップ " + id + " が壊れている");
        }
        for (var e : existed.entrySet()) {
            String rel = e.getKey();
            if (!(rel.startsWith("worlds/") || rel.equals("behavior_packs/" + BP_DIR) || rel.equals("resource_packs/" + RP_DIR))
                    || rel.contains("..")) {
                throw new IOException("バックアップの対象が不正: " + rel);
            }
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("バックアップの対象が BDS の外を指している");
            }
            if (rel.startsWith("worlds/") && !fullWorld) {
                for (String json : List.of("world_behavior_packs.json", "world_resource_packs.json")) {
                    Path saved = dir.resolve(rel).resolve(json);
                    Files.deleteIfExists(target.resolve(json));
                    if (Files.isRegularFile(saved)) {
                        Files.copy(saved, target.resolve(json), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                continue;
            }
            // ロックファイルはバックアップしないので、戻す前にあったら作り直す（LevelDB も無ければ作るが、元の形に揃える）
            boolean hadLock = Files.isRegularFile(target.resolve("db").resolve("LOCK"));
            deleteTree(target);
            if (e.getValue().getAsBoolean()) {
                copyTree(dir.resolve(rel), target, false);
                if (hadLock && Files.isDirectory(target.resolve("db")) && !Files.exists(target.resolve("db").resolve("LOCK"))) {
                    Files.createFile(target.resolve("db").resolve("LOCK"));
                }
            }
        }
    }

    // ---- 書込み ----

    private static void writePackDir(Path parent, String name, Pack pack) throws IOException {
        Files.createDirectories(parent);
        Path tmp = Files.createTempDirectory(parent, "." + name + ".tmp");
        try {
            for (var f : pack.files().entrySet()) {
                Path p = tmp.resolve(f.getKey()).normalize();
                if (!p.startsWith(tmp)) {
                    throw new IOException("パックのファイル名が不正");
                }
                Files.createDirectories(p.getParent());
                Files.write(p, f.getValue());
            }
            Path target = parent.resolve(name);
            deleteTree(target);
            move(tmp, target);
        } finally {
            deleteTree(tmp);
        }
    }

    /** {@code [{"pack_id": ..., "version": [...]}]} の自分の項目を置き換える。ほかの項目と順序は残す。 */
    static void updateWorldJson(Path file, Pack pack) throws IOException {
        JsonArray arr = new JsonArray();
        if (Files.exists(file)) {
            JsonElement e;
            try {
                e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (JsonParseException ex) {
                throw new IOException(file.getFileName() + " が JSON として読めない");
            }
            if (!(e instanceof JsonArray a)) {
                throw new IOException(file.getFileName() + " が配列ではない");
            }
            for (JsonElement x : a) {
                boolean mine = x instanceof JsonObject o && o.has("pack_id") && o.get("pack_id").isJsonPrimitive()
                        && pack.uuid().equals(o.get("pack_id").getAsString());
                if (!mine) {
                    arr.add(x);
                }
            }
        }
        JsonObject mine = new JsonObject();
        mine.addProperty("pack_id", pack.uuid());
        mine.add("version", GSON.toJsonTree(pack.version()));
        arr.add(mine);
        Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            Files.writeString(tmp, GSON.toJson(arr) + "\n", StandardCharsets.UTF_8);
            move(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- 読込 ----

    private static String levelName(Path root, List<String> problems) {
        Path props = root.resolve("server.properties");
        if (!Files.isRegularFile(props)) {
            problems.add("server.properties が無い");
            return null;
        }
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            problems.add("server.properties を読めない");
            return null;
        }
        String name = p.getProperty("level-name");
        if (name == null || name.isBlank()) {
            problems.add("server.properties に level-name が無い。--world で指定する");
            return null;
        }
        return name.trim();
    }

    /** .mcpack を上限付きで展開し、manifest の UUID・版・モジュールの種類を確かめる。 */
    private static Pack readPack(Path mcpack, String moduleType) throws InstallException {
        if (!Files.isRegularFile(mcpack)) {
            throw new InstallException(mcpack.getFileName() + " が無い。先に modbridge compile を実行する");
        }
        Map<String, byte[]> files = new TreeMap<>();
        try {
            if (Files.size(mcpack) > MAX_PACK_BYTES) {
                throw new InstallException(mcpack.getFileName() + " が大きすぎる");
            }
            long total = 0;
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(mcpack)))) {
                ZipEntry e;
                while ((e = in.getNextEntry()) != null) {
                    SafeArchive.checkName(e.getName());
                    if (e.isDirectory()) {
                        continue;
                    }
                    byte[] b = in.readNBytes((int) Math.min(MAX_PACK_BYTES - total + 1, Integer.MAX_VALUE - 8));
                    total += b.length;
                    if (total > MAX_PACK_BYTES || files.put(e.getName(), b) != null) {
                        throw new InstallException(mcpack.getFileName() + " が大きすぎるか、名前が重複している");
                    }
                }
            }
        } catch (IOException | SafeArchive.ArchiveException | IllegalArgumentException e) {
            throw new InstallException(mcpack.getFileName() + " を読めない（" + e.getMessage() + "）", e);
        }
        JsonObject manifest = null;
        byte[] m = files.get("manifest.json");
        if (m != null) {
            try {
                manifest = JsonParser.parseString(new String(m, StandardCharsets.UTF_8)) instanceof JsonObject o ? o : null;
            } catch (JsonParseException e) {
                manifest = null;
            }
        }
        try {
            JsonObject header = manifest.getAsJsonObject("header");
            String uuid = header.get("uuid").getAsString();
            List<Integer> version = new ArrayList<>();
            header.getAsJsonArray("version").forEach(v -> version.add(v.getAsInt()));
            String type = manifest.getAsJsonArray("modules").get(0).getAsJsonObject().get("type").getAsString();
            if (!type.equals(moduleType) || version.size() != 3) {
                throw new InstallException(mcpack.getFileName() + " のモジュールの種類か版が違う");
            }
            java.util.UUID.fromString(uuid);
            return new Pack(uuid, List.copyOf(version), files);
        } catch (RuntimeException e) {
            throw new InstallException(mcpack.getFileName() + " の manifest.json が無いか不正", e);
        }
    }

    private static JsonObject readJson(Path p) {
        try {
            return Files.isRegularFile(p) && JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)) instanceof JsonObject o ? o : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- ファイル操作 ----

    /** @param skipDbLock ワールドの DB のロックファイル（db/LOCK）を飛ばす。ロック中で読めない場合があり、LevelDB が作り直すため */
    private static void copyTree(Path src, Path dst, boolean skipDbLock) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : s.toList()) {
                if (Files.isSymbolicLink(p)) {
                    throw new IOException("シンボリックリンクはコピーしない: " + src.relativize(p));
                }
                if (skipDbLock && p.getFileName().toString().equals("LOCK") && p.getParent() != null
                        && p.getParent().getFileName() != null && p.getParent().getFileName().toString().equals("db")) {
                    continue;
                }
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(p)) {
            Files.delete(p); // リンク先は消さない
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        }
    }
}
