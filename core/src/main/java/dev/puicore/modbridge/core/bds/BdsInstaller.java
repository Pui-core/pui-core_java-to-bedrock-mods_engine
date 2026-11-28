package dev.puicore.modbridge.core.bds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.puicore.modbridge.core.scan.SafeArchive;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>変更操作は BDS フォルダー内の空の {@code .modbridge-bds.lock} で排他する</li>
 *   <li>変更の前に、ワールド・パックのフォルダー・パック一覧の JSON をバックアップする。rollback でそこから戻せる</li>
 *   <li>触るのは、自分のパックのフォルダー（{@code behavior_packs/modbridge-bp}・{@code resource_packs/modbridge-rp}）と、
 *       ワールドの {@code world_behavior_packs.json}・{@code world_resource_packs.json} の自分の項目だけ</li>
 * </ul>
 */
public final class BdsInstaller {
    public static final String BP_DIR = "modbridge-bp";
    public static final String RP_DIR = "modbridge-rp";
    public static final String BACKUP_DIR = "modbridge-backups";
    static final String OPERATION_LOCK_FILE = ".modbridge-bds.lock";
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

    private record Pack(String uuid, List<Integer> version, JsonObject manifest, Map<String, byte[]> files) {}

    private record Bundle(Pack behavior, Pack resource) {}

    private record PreparedPlan(Plan plan, Bundle bundle) {}

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
        return prepare(bds, packDir, world).plan();
    }

    private static PreparedPlan prepare(Path bds, Path packDir, String world) {
        List<String> problems = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        Path root = bds.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return new PreparedPlan(new Plan(world, actions, List.of("BDS のフォルダーが無い（シンボリックリンクは使えない）"),
                    null, null, null, null), null);
        }
        if (!Files.isRegularFile(root.resolve("bedrock_server")) && !Files.isRegularFile(root.resolve("bedrock_server.exe"))) {
            problems.add("bedrock_server（.exe）が無い。BDS のフォルダーを指定する");
        }
        String w = world;
        if (w == null) {
            w = levelName(root, problems);
        }
        if (w != null && !validWorld(root, w)) {
            problems.add("ワールド名に使えない文字か形がある（区切り文字・制御文字、「.」「..」、先頭・末尾の「.」や空白）");
            w = null;
        }
        if (Files.isSymbolicLink(root.resolve("worlds"))) {
            problems.add("worlds がシンボリックリンクになっている");
        }
        Pack bp = null;
        Pack rp = null;
        Bundle bundle = null;
        try {
            bundle = readBundle(packDir);
            bp = bundle.behavior();
            rp = bundle.resource();
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
        return new PreparedPlan(new Plan(w, List.copyOf(actions), List.copyOf(problems),
                bp == null ? null : bp.uuid(), bp == null ? null : bp.version(),
                rp == null ? null : rp.uuid(), rp == null ? null : rp.version()), bundle);
    }

    /** ワールド名として使えるか。解決したパスが worlds/ の直下でなければならない。 */
    static boolean validWorld(Path root, String w) {
        if (w == null || !WORLD_NAME.matcher(w).matches() || w.equals(".") || w.equals("..") || !w.strip().equals(w)
                || w.startsWith(".") || w.endsWith(".")) {
            return false;
        }
        Path worlds = root.resolve("worlds");
        return worlds.equals(worlds.resolve(w).normalize().getParent());
    }

    // ---- 適用 ----

    /**
     * 計画どおりに配置する。BDS を止めたことの明示（{@code confirmedStopped}）と、ワールドの DB のロックが要る。
     *
     * @return 作ったバックアップの ID（rollback に使う）
     */
    public static String apply(Path bds, Path packDir, String world, boolean confirmedStopped) throws InstallException {
        PreparedPlan prepared = prepare(bds, packDir, world);
        Plan plan = prepared.plan();
        if (!plan.ok()) {
            throw new InstallException("配置できない: " + String.join(" / ", plan.problems()));
        }
        if (!confirmedStopped) {
            throw new InstallException("BDS を止めてから、--confirm-stopped を付けて実行する");
        }
        Path root = bds.toAbsolutePath().normalize();
        Path worldDir = root.resolve("worlds").resolve(plan.world());
        Pack bp = prepared.bundle().behavior();
        Pack rp = prepared.bundle().resource();
        try (MutationLock mutation = MutationLock.acquire(root);
             WorldLock otherWorlds = WorldLock.acquireOthers(worldDir);
             WorldLock lock = WorldLock.acquire(worldDir)) {
            otherWorlds.ensureHeld();
            lock.ensureHeld();
            mutation.ensureHeld();
            String id = backup(root, plan.world());
            try {
                // 前回と違うパック ID で作り直した場合に備えて、いま入っているパックの UUID の項目も置き換える
                String oldBp = installedUuid(root.resolve("behavior_packs").resolve(BP_DIR));
                String oldRp = installedUuid(root.resolve("resource_packs").resolve(RP_DIR));
                writePackDir(root.resolve("behavior_packs"), BP_DIR, bp);
                writePackDir(root.resolve("resource_packs"), RP_DIR, rp);
                updateWorldJson(worldDir.resolve("world_behavior_packs.json"), bp, oldBp);
                updateWorldJson(worldDir.resolve("world_resource_packs.json"), rp, oldRp);
            } catch (IOException | RuntimeException e) {
                try {
                    restore(root, readBackup(root, id), false); // 配置で変えたもの（パックのフォルダーと 2 つの JSON）だけを戻す
                } catch (IOException | RuntimeException r) {
                    e.addSuppressed(r);
                    throw new InstallException("配置に失敗し、自動の復旧にも失敗した。バックアップ " + id + " から rollback する", e);
                }
                throw new InstallException("配置に失敗したので、バックアップ " + id + " から元に戻した（" + e.getClass().getSimpleName() + "）", e);
            }
            return id;
        } catch (IOException e) {
            throw new InstallException("配置できない（" + message(e) + "）", e);
        }
    }

    /** 利用者向けの理由。自前の例外は文言を、それ以外は種類名だけを出す（絶対パスを含めない）。 */
    private static String message(Exception e) {
        return e instanceof LockedException || (e instanceof IOException && e.getClass() == IOException.class) ? e.getMessage()
                : e.getClass().getSimpleName();
    }

    /** 入っているパックの UUID（manifest が無い・読めないなら null）。 */
    private static String installedUuid(Path packDir) {
        JsonObject m = readJson(packDir.resolve("manifest.json"));
        try {
            return m == null ? null : m.getAsJsonObject("header").get("uuid").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * バックアップから戻す。apply と同じく、止めたことの明示とロックが要る。
     *
     * <p>対象ワールドのフォルダーを置き換える間は、そのワールドのロックだけを放す。Windows では、開いているファイルを
     * 含むフォルダーを動かせないため。置き換え後に対象のロックを確かめ、BDS が起動していたら知らせる。
     */
    public static void rollback(Path bds, String backupId, boolean confirmedStopped) throws InstallException {
        if (backupId == null || !BACKUP_ID.matcher(backupId).matches()) {
            throw new InstallException("バックアップ ID の形式が違う");
        }
        if (!confirmedStopped) {
            throw new InstallException("BDS を止めてから、--confirm-stopped を付けて実行する");
        }
        Path root = bds.toAbsolutePath().normalize();
        Backup b;
        try {
            b = readBackup(root, backupId);
        } catch (IOException | RuntimeException e) {
            throw new InstallException("バックアップ " + backupId + " を使えない（" + message(e) + "）", e);
        }
        Path worldDir = root.resolve("worlds").resolve(b.world());
        try (MutationLock mutation = MutationLock.acquire(root);
             WorldLock otherWorlds = WorldLock.acquireOthers(worldDir)) {
            otherWorlds.ensureHeld();
            WorldLock.acquire(worldDir).close();
            mutation.ensureHeld();
            restore(root, b, true);
            try {
                WorldLock.acquire(worldDir).close();
            } catch (IOException e) {
                throw new InstallException("元に戻したが、その間に BDS が起動した可能性がある。BDS を止めてから、もう一度 rollback する", e);
            }
        } catch (ReplacementCleanupException e) {
            throw new InstallException(e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new InstallException("元に戻せない（" + message(e) + "）。バックアップ " + backupId + " は残っている", e);
        }
    }

    // ---- ロック ----

    /** 同じ BDS フォルダーを更新する ModBridge の処理を、プロセス間と JVM 内で排他する。 */
    static final class MutationLock implements AutoCloseable {
        private static final String BUSY = "この BDS フォルダーで ModBridge の配置または復旧を実行中";
        private static final Set<Path> ACTIVE_ROOTS = ConcurrentHashMap.newKeySet();
        private final Path root;
        private FileChannel channel;
        private FileLock lock;

        private MutationLock(Path root) {
            this.root = root;
        }

        static MutationLock acquire(Path root) throws IOException {
            Path canonical = root.toRealPath();
            // 同一 JVM の別チャネルを閉じると、既存のファイルロックまで外れる OS がある。
            if (!ACTIVE_ROOTS.add(canonical)) {
                throw new IOException(BUSY);
            }
            var mutation = new MutationLock(canonical);
            try {
                mutation.channel = FileChannel.open(canonical.resolve(OPERATION_LOCK_FILE), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                mutation.lock = mutation.channel.tryLock(0, 1, false);
                if (mutation.lock == null) {
                    throw new IOException(BUSY);
                }
                return mutation;
            } catch (IOException | RuntimeException failure) {
                try {
                    mutation.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                if (failure instanceof OverlappingFileLockException) {
                    throw new IOException(BUSY, failure);
                }
                if (failure instanceof UnsupportedOperationException) {
                    throw new IOException("このファイルシステムでは ModBridge の配置・復旧ロックを使えない", failure);
                }
                throw failure;
            }
        }

        void ensureHeld() throws IOException {
            if (!lock.isValid()) {
                throw new IOException("ModBridge の配置・復旧ロックが外れた");
            }
        }

        @Override
        public void close() throws IOException {
            try {
                if (channel != null) {
                    channel.close();
                }
            } finally {
                ACTIVE_ROOTS.remove(root);
            }
        }
    }

    /** BDS が動いていてロックを取れない。メッセージは固定の文言（パスを含めない）。 */
    static final class LockedException extends IOException {
        private static final long serialVersionUID = 1L;

        LockedException() {
            super("ワールドの DB がロックされている。BDS が動いているので止める");
        }
    }

    /**
     * LevelDB のロック、または Windows の DB ファイルの書き込み共有拒否を保持する。
     * DB がまだ無いワールドでは、呼出し側の停止確認だけを使う。
     */
    private static final class WorldLock implements AutoCloseable {
        private static final Pattern MUTABLE_LEVELDB_FILE = Pattern.compile("MANIFEST-[0-9]+|[0-9]+\\.log");
        private final List<FileChannel> channels = new ArrayList<>();
        private final List<FileLock> locks = new ArrayList<>();

        static WorldLock acquire(Path worldDir) throws IOException {
            return acquire(List.of(worldDir));
        }

        static WorldLock acquireOthers(Path target) throws IOException {
            Path worldsDir = target.getParent();
            if (Files.notExists(worldsDir, LinkOption.NOFOLLOW_LINKS)) {
                return new WorldLock();
            }
            if (!Files.isDirectory(worldsDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new LockedException();
            }
            List<Path> others;
            try (Stream<Path> paths = Files.list(worldsDir)) {
                // RP/BP は BDS 全体で共有される。起動後に level-name を変えた場合にも、元の DB を検査する。
                others = paths.filter(path -> !path.equals(target) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList();
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            return acquire(others);
        }

        private static WorldLock acquire(List<Path> worlds) throws IOException {
            WorldLock acquired = new WorldLock();
            try {
                for (Path world : worlds) {
                    acquired.openDatabase(world.resolve("db"));
                }
                return acquired;
            } catch (IOException | UncheckedIOException | ReflectiveOperationException | UnsupportedOperationException
                    | OverlappingFileLockException failure) {
                var locked = failure instanceof LockedException e ? e : new LockedException();
                if (locked != failure) {
                    locked.addSuppressed(failure);
                }
                try {
                    acquired.close();
                } catch (IOException cleanup) {
                    locked.addSuppressed(cleanup);
                }
                throw locked;
            }
        }

        private void openDatabase(Path db) throws IOException, ReflectiveOperationException {
            if (Files.notExists(db, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!Files.isDirectory(db, LinkOption.NOFOLLOW_LINKS)) {
                throw new LockedException();
            }
            Path lockFile = db.resolve("LOCK");
            if (!Files.notExists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
                FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                channels.add(channel);
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new LockedException();
                }
                locks.add(lock);
            }
            if (System.getProperty("os.name").startsWith("Windows")) {
                acquireWindowsFiles(db, channels);
            }
        }

        private static void acquireWindowsFiles(Path db, List<FileChannel> opened) throws IOException, ReflectiveOperationException {
            List<Path> journals;
            try (Stream<Path> files = Files.list(db)) {
                journals = files.filter(p -> MUTABLE_LEVELDB_FILE.matcher(p.getFileName().toString()).matches()).toList();
            }
            if (journals.isEmpty()) {
                return;
            }
            // Windows の OpenJDK が提供する拡張。利用できなければ停止を確認できない扱いにする。
            if (!(Class.forName("com.sun.nio.file.ExtendedOpenOption").getField("NOSHARE_WRITE").get(null)
                    instanceof OpenOption denyWrites)) {
                throw new LockedException();
            }
            for (Path file : journals) {
                // 読み込みは共有するので、このチャネルを保ったままバックアップできる。
                opened.add(FileChannel.open(file, StandardOpenOption.READ, denyWrites, LinkOption.NOFOLLOW_LINKS));
            }
        }

        /** ロックを持っている間に呼ぶ（DB の無いワールドでは何もしない）。 */
        void ensureHeld() throws IOException {
            if (locks.stream().anyMatch(lock -> !lock.isValid()) || channels.stream().anyMatch(ch -> !ch.isOpen())) {
                throw new IOException("ワールドの DB のロックが外れた");
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (FileChannel channel : channels) {
                try {
                    channel.close(); // このチャネルの FileLock も解放される
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    // ---- バックアップと復元 ----

    /** バックアップの中身。{@code existed} は対象ごとに、バックアップの時点で存在したか。 */
    private record Backup(String id, Path dir, String world, Map<String, Boolean> existed) {}

    /** バックアップの対象（ワールド名から決まる 3 つ）。 */
    private static List<String> targets(String world) {
        return List.of("worlds/" + world, "behavior_packs/" + BP_DIR, "resource_packs/" + RP_DIR);
    }

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
            for (String rel : targets(world)) {
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
     * バックアップを読み、すべて検査する。対象は、ワールド名から決まる 3 つと完全に一致しなければならず、
     * 値は真偽値でなければならない（書き換えられた backup.json でほかのフォルダーを消さないため）。
     */
    private static Backup readBackup(Path root, String id) throws IOException {
        Path dir = root.resolve(BACKUP_DIR).resolve(id);
        JsonObject meta = readJson(dir.resolve("backup.json"));
        if (meta == null || !(meta.get("world") instanceof JsonPrimitive wp) || !wp.isString()) {
            throw new IOException("backup.json が無いか壊れている");
        }
        String world = wp.getAsString();
        if (!validWorld(root, world)) {
            throw new IOException("backup.json のワールド名が不正");
        }
        if (!(meta.get("existed") instanceof JsonObject existed) || !new TreeSet<>(existed.keySet()).equals(new TreeSet<>(targets(world)))) {
            throw new IOException("backup.json の対象が、このワールドのバックアップの対象と一致しない");
        }
        Map<String, Boolean> out = new TreeMap<>();
        for (var e : existed.entrySet()) {
            if (!(e.getValue() instanceof JsonPrimitive p) || !p.isBoolean()) {
                throw new IOException("backup.json の値が真偽値ではない: " + e.getKey());
            }
            if (p.getAsBoolean() && !Files.isDirectory(dir.resolve(e.getKey()), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("バックアップに " + e.getKey() + " が無い");
            }
            out.put(e.getKey(), p.getAsBoolean());
        }
        // 配置はワールドがあるときにしか行わないので、正しいバックアップではワールドは必ず「あった」
        if (!out.get("worlds/" + world)) {
            throw new IOException("backup.json でワールドが「無かった」ことになっている（正しいバックアップではありえない）");
        }
        return new Backup(id, dir, world, out);
    }

    /**
     * バックアップから戻す。配置失敗時は、ワールドのうち配置で触るパック一覧だけを復旧する。
     */
    private static void restore(Path root, Backup backup, boolean fullWorld) throws IOException {
        List<Replacement> replacements = new ArrayList<>();
        for (var entry : backup.existed().entrySet()) {
            String relative = entry.getKey();
            Path target = root.resolve(relative);
            Path saved = entry.getValue() ? backup.dir().resolve(relative) : null;
            boolean isWorld = relative.startsWith("worlds/");
            if (isWorld && !fullWorld) {
                for (String name : List.of("world_behavior_packs.json", "world_resource_packs.json")) {
                    Path savedList = saved.resolve(name); // バックアップにはワールドが必ずある
                    replacements.add(new Replacement(target.resolve(name),
                            Files.isRegularFile(savedList) ? savedList : null, false, false));
                }
            } else {
                boolean recreateDbLock = isWorld && Files.isRegularFile(target.resolve("db/LOCK"));
                replacements.add(new Replacement(target, saved, true, recreateDbLock));
            }
        }
        replaceAll(replacements);
    }

    /** 元が無かった対象は source が null。 */
    private record Replacement(Path target, Path source, boolean directory, boolean recreateDbLock) {}

    private static void replaceAll(List<Replacement> replacements) throws IOException {
        List<ReplacementSwap> swaps = new ArrayList<>();
        try {
            for (Replacement replacement : replacements) {
                var swap = new ReplacementSwap(replacement.target());
                swaps.add(swap);
                swap.prepare(replacement);
            }
            for (ReplacementSwap swap : swaps) {
                swap.install();
            }
        } catch (IOException | RuntimeException failure) {
            List<String> notRestored = new ArrayList<>();
            for (ReplacementSwap swap : swaps.reversed()) {
                try {
                    swap.undo();
                } catch (IOException | RuntimeException undo) {
                    failure.addSuppressed(undo);
                    notRestored.add(swap.original == null ? swap.target.getFileName().toString()
                            : swap.original.getParent().getFileName() + "/" + swap.original.getFileName());
                }
                try {
                    swap.discardStaged();
                } catch (IOException | RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (!notRestored.isEmpty()) {
                throw new IOException("操作前の状態に戻せなかった。対象または退避先: " + String.join("、", notRestored), failure);
            }
            throw failure;
        }

        IOException cleanupFailure = null;
        for (ReplacementSwap swap : swaps) {
            if (swap.original == null) {
                continue;
            }
            try {
                deleteTree(swap.original);
            } catch (IOException | RuntimeException e) {
                var failure = new IOException("退避先 " + swap.original.getFileName() + " を削除できない", e);
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
        }
        if (cleanupFailure != null) {
            throw new ReplacementCleanupException(cleanupFailure);
        }
    }

    private static final class ReplacementCleanupException extends IOException {
        private static final long serialVersionUID = 1L;

        ReplacementCleanupException(IOException cause) {
            super("入れ替えは完了したが、" + cause.getMessage(), cause);
        }
    }

    private static final class ReplacementSwap {
        private final Path target;
        private Path staged;
        private Path original;
        private boolean installed;

        ReplacementSwap(Path target) {
            this.target = target;
        }

        void prepare(Replacement replacement) throws IOException {
            Path parent = target.getParent();
            String prefix = "." + target.getFileName() + ".restore";
            Files.createDirectories(parent);
            if (replacement.source() == null) {
                return;
            }
            if (replacement.directory()) {
                staged = Files.createTempDirectory(parent, prefix);
                copyTree(replacement.source(), staged, false);
                Path dbLock = staged.resolve("db/LOCK");
                if (replacement.recreateDbLock() && Files.isDirectory(dbLock.getParent()) && !Files.exists(dbLock)) {
                    Files.createFile(dbLock);
                }
            } else {
                staged = Files.createTempFile(parent, prefix, ".tmp");
                Files.copy(replacement.source(), staged, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        void install() throws IOException {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Path aside = target.resolveSibling("." + target.getFileName() + ".old-" + Long.toHexString(System.nanoTime()));
                move(target, aside);
                original = aside;
            }
            if (staged != null) {
                move(staged, target);
                installed = true;
            }
        }

        void undo() throws IOException {
            if (installed) {
                move(target, staged);
                installed = false;
            }
            if (original != null) {
                move(original, target);
                original = null;
            }
        }

        void discardStaged() throws IOException {
            if (staged != null && !installed) {
                deleteTree(staged);
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
            // 書き終えたものと入れ替える（途中で失敗しても、元のフォルダーは残る）
            replaceAll(List.of(new Replacement(parent.resolve(name), tmp, true, false)));
        } finally {
            deleteTree(tmp);
        }
    }

    /**
     * {@code [{"pack_id": ..., "version": [...]}]} の自分の項目を置き換える。ほかの項目と順序は残す。
     * 自分の項目（今回の UUID か、{@code previousUuid}）が既にあれば、最初のものの位置で置き換え、残りは消す。無ければ末尾に足す。
     */
    static void updateWorldJson(Path file, Pack pack, String previousUuid) throws IOException {
        JsonObject mine = new JsonObject();
        mine.addProperty("pack_id", pack.uuid());
        mine.add("version", GSON.toJsonTree(pack.version()));
        JsonArray arr = new JsonArray();
        boolean placed = false;
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
                String id = x instanceof JsonObject o && o.get("pack_id") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
                boolean ours = id != null && (id.equals(pack.uuid()) || id.equals(previousUuid));
                if (!ours) {
                    arr.add(x);
                } else if (!placed) {
                    arr.add(mine);
                    placed = true;
                }
            }
        }
        if (!placed) {
            arr.add(mine);
        }
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

    /** 一緒に生成したパックの組であることを、BP が要求する RP の UUID と版で確認する。 */
    private static Bundle readBundle(Path packDir) throws InstallException {
        Pack bp = readPack(packDir.resolve("modbridge-bp.mcpack"), "data");
        Pack rp = readPack(packDir.resolve("modbridge-rp.mcpack"), "resources");
        if (bp.uuid().equalsIgnoreCase(rp.uuid())) {
            throw new InstallException("ビヘイビアパックとリソースパックの UUID が同じ");
        }
        if (!(bp.manifest().get("dependencies") instanceof JsonArray dependencies)) {
            throw new InstallException("ビヘイビアパックにリソースパックへの依存が無い");
        }
        var references = dependencies.asList().stream().filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(entry -> entry.get("uuid") instanceof JsonPrimitive uuid && uuid.isString()
                        && !entry.has("module_name") && rp.uuid().equalsIgnoreCase(uuid.getAsString()))
                .toList();
        if (references.size() != 1 || !matchesVersion(references.getFirst().get("version"), rp.version())) {
            throw new InstallException("ビヘイビアパックの依存先とリソースパックの UUID または版が一致しない。同じ compile の出力を使う");
        }
        return new Bundle(bp, rp);
    }

    private static boolean matchesVersion(JsonElement declared, List<Integer> version) {
        String dotted = version.get(0) + "." + version.get(1) + "." + version.get(2);
        return GSON.toJsonTree(version).equals(declared)
                || (declared instanceof JsonPrimitive value && value.isString() && dotted.equals(value.getAsString()));
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
            return new Pack(uuid, List.copyOf(version), manifest, files);
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
