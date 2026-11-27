package dev.puicore.modbridge.core.compile;

import dev.puicore.modbridge.core.TargetVersions;
import dev.puicore.modbridge.core.ir.BridgeIr;
import dev.puicore.modbridge.core.ir.IrJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 生成物のキャッシュと公開。
 *
 * <ul>
 *   <li>キャッシュの鍵: 生成器の版・対象バージョン・設定・アダプター・IR（入力 JAR の SHA-256 と依存を含む）のハッシュ。
 *       どれかが変われば鍵が変わり、作り直す</li>
 *   <li>公開: 一時フォルダーにすべて書いてから、出力フォルダーと入れ替える。途中で失敗したら旧成果物を残す</li>
 *   <li>出力フォルダーは、空か、このクラスが作った印（{@code .modbridge-pack}）のあるフォルダーに限る（他のフォルダーを消さないため）</li>
 * </ul>
 */
public final class PackPublisher {
    static final String MARKER = ".modbridge-pack";
    static final String CHECKSUMS = "files.sha256";

    private PackPublisher() {}

    /** アダプターはまだ無い（#7）。将来はアダプターの ID と版をここに入れる。 */
    static final List<String> ADAPTERS = List.of();

    public static String cacheKey(BridgeIr ir, PackCompiler.Settings settings) {
        StringBuilder b = new StringBuilder();
        b.append(PackCompiler.COMPILER_VERSION).append('\n');
        TargetVersions.all().forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
        b.append("packName=").append(settings.packName()).append('\n');
        b.append("packId=").append(settings.packId()).append('\n');
        b.append("adapters=").append(ADAPTERS).append('\n');
        b.append(IrJson.write(ir));
        return HexFormat.of().formatHex(PackCompiler.sha256().digest(b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** キャッシュにあれば、その内容を返す。壊れていれば（チェックサム不一致）null。 */
    public static Map<String, byte[]> readCache(Path cacheDir, String key) throws IOException {
        Path dir = cacheDir.resolve(key);
        Path sums = dir.resolve(CHECKSUMS);
        if (!Files.isRegularFile(sums)) {
            return null;
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
            String[] p = line.split("  ", 2);
            if (p.length != 2 || !(p[1].equals(PackCompiler.PACK_FILE) || p[1].equals(PackCompiler.MAPPING_FILE)) || files.containsKey(p[1])) {
                return null;
            }
            Path f = dir.resolve(p[1]);
            if (!Files.isRegularFile(f)) {
                return null;
            }
            byte[] b = Files.readAllBytes(f);
            if (!HexFormat.of().formatHex(PackCompiler.sha256().digest(b)).equals(p[0])) {
                return null;
            }
            files.put(p[1], b);
        }
        // 2 つとも揃っていて、対応表に記録した内容のハッシュがパックと一致するときだけ使う
        if (files.size() != 2 || !files.containsKey(PackCompiler.PACK_FILE) || !files.containsKey(PackCompiler.MAPPING_FILE)) {
            return null;
        }
        return PackCompiler.consistent(files.get(PackCompiler.PACK_FILE), files.get(PackCompiler.MAPPING_FILE)) ? files : null;
    }

    /** キャッシュに保存する。同じ鍵のものが既にあれば置き換える。 */
    public static void writeCache(Path cacheDir, String key, Map<String, byte[]> files) throws IOException {
        Files.createDirectories(cacheDir);
        Path tmp = Files.createTempDirectory(cacheDir, "." + key + ".tmp");
        try {
            StringBuilder sums = new StringBuilder();
            for (var f : new TreeMap<>(files).entrySet()) {
                Files.write(tmp.resolve(f.getKey()), f.getValue());
                sums.append(HexFormat.of().formatHex(PackCompiler.sha256().digest(f.getValue()))).append("  ").append(f.getKey()).append('\n');
            }
            Files.writeString(tmp.resolve(CHECKSUMS), sums.toString(), StandardCharsets.UTF_8);
            Path dir = cacheDir.resolve(key);
            deleteTree(dir);
            move(tmp, dir);
        } finally {
            deleteTree(tmp);
        }
    }

    /**
     * 出力フォルダーを、{@code files} だけを持つフォルダーに置き換える。失敗したら旧成果物を残す。
     *
     * @throws IOException 書けない、または出力先が別の用途のフォルダー（印が無く、空でない）
     */
    public static void publish(Path outDir, Map<String, byte[]> files) throws IOException {
        Path out = outDir.toAbsolutePath().normalize();
        if (out.getFileName() == null || out.getParent() == null) {
            throw new IOException("出力先にはフォルダー名を指定する（ルートは使えない）");
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (cwd.startsWith(out)) {
            throw new IOException("出力先が現在のフォルダーか、その親になっている");
        }
        if (Files.isSymbolicLink(out)) {
            throw new IOException("出力先がシンボリックリンクになっている");
        }
        if (Files.exists(out)) {
            if (!Files.isDirectory(out)) {
                throw new IOException("出力先がフォルダーではない: " + out.getFileName());
            }
            boolean empty;
            try (Stream<Path> s = Files.list(out)) {
                empty = s.findAny().isEmpty();
            }
            if (!empty && !Files.exists(out.resolve(MARKER))) {
                throw new IOException("出力先 " + out.getFileName() + " は空でなく、modbridge の出力の印（" + MARKER + "）も無い。別のフォルダーを指定する");
            }
        }
        Path parent = out.getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempDirectory(parent, "." + out.getFileName() + ".tmp");
        Path old = null;
        try {
            for (var f : files.entrySet()) {
                Files.write(tmp.resolve(f.getKey()), f.getValue());
            }
            Files.writeString(tmp.resolve(MARKER), "modbridge が生成したフォルダー。公開のたびに中身を置き換える\n", StandardCharsets.UTF_8);
            if (Files.exists(out)) {
                old = parent.resolve("." + out.getFileName() + ".old-" + Long.toHexString(System.nanoTime()));
                move(out, old);
            }
            try {
                move(tmp, out);
            } catch (IOException e) {
                if (old != null) {
                    Path keep = old;
                    old = null; // 元に戻せなくても、旧成果物は消さない
                    try {
                        move(keep, out);
                    } catch (IOException restore) {
                        e.addSuppressed(restore);
                        throw new IOException("公開に失敗し、旧成果物を元の場所に戻せなかった。旧成果物は " + keep.getFileName() + " に残している", e);
                    }
                }
                throw e;
            }
        } finally {
            deleteTree(tmp);
            if (old != null) {
                deleteTree(old);
            }
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(x);
                } catch (DirectoryNotEmptyException e) {
                    // 並行して作られたものは残す
                }
            }
        }
    }
}
