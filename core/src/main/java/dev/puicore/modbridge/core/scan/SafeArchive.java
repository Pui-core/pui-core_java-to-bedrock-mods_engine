package dev.puicore.modbridge.core.scan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * JAR（ZIP）を展開せずに読む。クラスの読込もコード実行もしない。
 *
 * <ul>
 *   <li>危険なエントリー名（{@code ..}、絶対パス、ドライブ指定、バックスラッシュ、NUL）を拒否する（ZIP Slip 対策）</li>
 *   <li>エントリー数・個別サイズ・合計読込量・圧縮率に上限を設ける（ZIP 爆弾対策）。
 *       上限は宣言サイズではなく、実際に読んだバイト数で判定する</li>
 *   <li>内容を読むのはテキスト系（JSON・lang・toml・mcmeta）だけ。画像などは存在だけを記録する</li>
 *   <li>同梱 JAR は、外側の {@code fabric.mod.json} の {@code jars} に書かれたものだけを、中の {@code fabric.mod.json}
 *       に限って読む（依存の判定用）。個数と展開量の合計にも上限を設ける。同梱 JAR の中にさらに同梱された JAR は読まない</li>
 *   <li>エラーメッセージには絶対パスを入れない（IR の再現性のため）</li>
 * </ul>
 */
public final class SafeArchive {

    /** 上限値。既定値は一般的な MOD の数十倍の余裕を持たせている。 */
    public record Limits(int maxEntries, long maxJarBytes, long maxEntryBytes, long maxTotalReadBytes, int maxRatio) {
        public static final Limits DEFAULT = new Limits(65_536, 512L << 20, 8L << 20, 128L << 20, 200);
    }

    /** 読込を打ち切った理由。 */
    public static final class ArchiveException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        ArchiveException(String code, String message) {
            super(message);
            this.code = code;
        }

        ArchiveException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");
    /** 同梱 JAR の読込結果。{@code fabricModJson} は無ければ null、{@code error} は読めなかった理由（読めたら null）。 */
    public record NestedJar(byte[] fabricModJson, String error) {}

    /** 1 JAR あたりで読む同梱 JAR の上限個数。 */
    static final int MAX_NESTED_JARS = 1024;

    private static final Set<String> TEXT_SUFFIXES = Set.of(".json", ".lang", ".toml", ".mcmeta", ".properties");

    private final String fileName;
    private final String sha256;
    private final long size;
    private final Set<String> names;
    private final Map<String, byte[]> texts;
    private final Map<String, NestedJar> nested;

    private SafeArchive(String fileName, String sha256, long size, Set<String> names, Map<String, byte[]> texts,
            Map<String, NestedJar> nested) {
        this.fileName = fileName;
        this.sha256 = sha256;
        this.size = size;
        this.names = names;
        this.texts = texts;
        this.nested = nested;
    }

    public String fileName() {
        return fileName;
    }

    public String sha256() {
        return sha256;
    }

    public long size() {
        return size;
    }

    /** 全エントリー名（ディレクトリを除く、ソート済み）。 */
    public Set<String> names() {
        return names;
    }

    public boolean has(String name) {
        return names.contains(name);
    }

    /** テキスト系（と、開くときに指定したもの）のエントリーの内容。対象外・不在なら null。 */
    public byte[] text(String name) {
        return texts.get(name);
    }

    /** 同梱 JAR の読込結果。エントリーが無ければ null。 */
    public NestedJar nested(String name) {
        return nested.get(name);
    }

    public static SafeArchive open(Path path) throws ArchiveException {
        return open(path, Limits.DEFAULT);
    }

    public static SafeArchive open(Path path, Limits limits) throws ArchiveException {
        return open(path, limits, name -> false);
    }

    /**
     * テキスト系に加えて、{@code alsoRead} が真を返すエントリー（テクスチャの PNG など）の内容も読む。
     * 読んだ量は同じ上限（個別・合計・圧縮率）で数える。内容は {@link #text(String)} で取り出す。
     */
    public static SafeArchive open(Path path, Limits limits, java.util.function.Predicate<String> alsoRead) throws ArchiveException {
        long size;
        String sha;
        try {
            size = Files.size(path);
            if (size > limits.maxJarBytes()) {
                throw new ArchiveException("JAR_LIMIT_EXCEEDED", "JAR が大きすぎる（" + size + " バイト、上限 " + limits.maxJarBytes() + "）");
            }
            sha = sha256(path);
        } catch (IOException e) {
            throw new ArchiveException("IO_ERROR", "読めない（" + e.getClass().getSimpleName() + "）", e);
        }

        Set<String> names = new TreeSet<>();
        Map<String, byte[]> texts = new TreeMap<>();
        Map<String, NestedJar> nested = new TreeMap<>();
        long totalRead = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.size() > limits.maxEntries()) {
                throw new ArchiveException("JAR_LIMIT_EXCEEDED", "エントリー数が多すぎる（" + zip.size() + "、上限 " + limits.maxEntries() + "）");
            }
            Set<String> declared = declaredNestedJars(zip, limits);
            long[] nestedBudget = {limits.maxTotalReadBytes()}; // 同梱 JAR の展開量の合計
            var it = zip.entries();
            int count = 0;
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (++count > limits.maxEntries()) {
                    throw new ArchiveException("JAR_LIMIT_EXCEEDED", "エントリー数が上限を超えた");
                }
                String name = e.getName();
                checkName(name);
                if (e.isDirectory()) {
                    continue;
                }
                if (!names.add(name)) {
                    throw new ArchiveException("JAR_CORRUPT", "同名のエントリーが重複している: " + name);
                }
                if (declared.contains(name)) {
                    NestedJar n = nested.size() >= MAX_NESTED_JARS
                            ? new NestedJar(null, "同梱 JAR の個数が上限（" + MAX_NESTED_JARS + "）を超えた")
                            : readNested(zip, e, limits, nestedBudget);
                    if (n.fabricModJson() != null) {
                        totalRead += n.fabricModJson().length;
                    }
                    nested.put(name, n);
                    continue;
                }
                if (!isText(name) && !alsoRead.test(name)) {
                    continue;
                }
                byte[] data;
                try (InputStream in = zip.getInputStream(e)) {
                    data = readBounded(in, name, Math.max(e.getCompressedSize(), 1), limits);
                }
                totalRead += data.length;
                if (totalRead > limits.maxTotalReadBytes()) {
                    throw new ArchiveException("JAR_LIMIT_EXCEEDED", "読込量の合計が上限を超えた（" + limits.maxTotalReadBytes() + " バイト）");
                }
                texts.put(name, data);
            }
        } catch (ArchiveException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            // ZipException（壊れた ZIP）や不正なエントリー名の文字コード
            throw new ArchiveException("JAR_CORRUPT", "JAR として読めない: " + scrub(e.getMessage(), path), e);
        }
        return new SafeArchive(path.getFileName().toString(), sha, size,
                Collections.unmodifiableSet(names), Collections.unmodifiableMap(texts), Collections.unmodifiableMap(nested));
    }

    /**
     * 同梱 JAR をストリームとして読み、{@code fabric.mod.json} だけを取り出す。
     * 読めなくても JAR 全体は拒否しない（依存の判定ができないだけなので、理由を記録する）。
     * 展開量は「圧縮サイズ × 圧縮率の上限 + 1 MiB」と JAR サイズの上限の小さいほうで打ち切る。
     */
    private static NestedJar readNested(ZipFile zip, ZipEntry e, Limits limits, long[] budget) {
        long perJar = Math.min(limits.maxJarBytes(), Math.max(e.getCompressedSize(), 1) * limits.maxRatio() + (1L << 20));
        if (budget[0] <= 0) {
            return new NestedJar(null, "同梱 JAR の展開量の合計が上限（" + limits.maxTotalReadBytes() + " バイト）に達した");
        }
        CountingZipInputStream in = null;
        try (InputStream raw = zip.getInputStream(e)) {
            // 同梱 JAR 自体の大きさと、その中を展開した量の両方を数える
            in = new CountingZipInputStream(new CappedInputStream(raw, perJar), Math.min(limits.maxJarBytes(), budget[0]));
            ZipEntry inner;
            while ((inner = in.getNextEntry()) != null) {
                if (inner.getName().equals("fabric.mod.json")) {
                    return new NestedJar(readBounded(in, e.getName() + "!fabric.mod.json", 0, limits), null);
                }
            }
            return new NestedJar(null, null);
        } catch (ArchiveException | LimitException ex) {
            return new NestedJar(null, ex.getMessage());
        } catch (IOException | IllegalArgumentException ex) {
            return new NestedJar(null, "同梱 JAR として読めない（" + ex.getClass().getSimpleName() + "）");
        } finally {
            if (in != null) {
                budget[0] -= in.used();
            }
        }
    }

    /** 外側の fabric.mod.json の jars[].file。読めなければ空（メタデータの不正は JarScanner が報告する）。 */
    private static Set<String> declaredNestedJars(ZipFile zip, Limits limits) throws IOException, ArchiveException {
        ZipEntry meta = zip.getEntry("fabric.mod.json");
        if (meta == null || meta.isDirectory()) {
            return Set.of();
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(meta)) {
            bytes = readBounded(in, "fabric.mod.json", Math.max(meta.getCompressedSize(), 1), limits);
        }
        Set<String> out = new TreeSet<>();
        try {
            if (JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)) instanceof JsonObject o
                    && o.get("jars") instanceof JsonArray arr) {
                for (JsonElement v : arr) {
                    if (v instanceof JsonObject vo && vo.get("file") instanceof JsonPrimitive f && f.isString()) {
                        out.add(f.getAsString());
                    }
                }
            }
        } catch (JsonParseException ex) {
            return Set.of();
        }
        return out;
    }

    /** 同梱 JAR の上限による打切り。メッセージは固定の文言。 */
    private static final class LimitException extends IOException {
        private static final long serialVersionUID = 1L;

        LimitException(String message) {
            super(message);
        }
    }

    /** 展開後のバイト数（エントリーを読み飛ばす分も含む）が上限を超えたら打ち切る ZIP 入力。 */
    private static final class CountingZipInputStream extends ZipInputStream {
        private final long cap;
        private long used;

        CountingZipInputStream(InputStream in, long cap) {
            super(in);
            this.cap = cap;
        }

        long used() {
            return Math.min(used, cap);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                used += n;
                if (used > cap) {
                    throw new LimitException("同梱 JAR の展開量が上限を超えた（ZIP 爆弾の疑い）");
                }
            }
            return n;
        }
    }

    /** 読んだバイト数が上限を超えたら打ち切る入力。 */
    private static final class CappedInputStream extends FilterInputStream {
        private long remaining;

        CappedInputStream(InputStream in, long cap) {
            super(in);
            this.remaining = cap;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                remaining -= n;
                if (remaining < 0) {
                    throw new LimitException("同梱 JAR が大きすぎる（ZIP 爆弾の疑い）");
                }
            }
            return n;
        }
    }

    /** 例外メッセージに含まれるパスをファイル名に置き換える。 */
    private static String scrub(String message, Path path) {
        if (message == null) {
            return "";
        }
        String name = String.valueOf(path.getFileName());
        return message.replace(path.toAbsolutePath().toString(), name).replace(path.toString(), name);
    }

    public static void checkName(String name) throws ArchiveException {
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || name.startsWith("/")
                || DRIVE.matcher(name).matches()) {
            throw new ArchiveException("UNSAFE_PATH", "危険なエントリー名: " + printable(name));
        }
        for (String seg : name.split("/", -1)) {
            if (seg.equals("..") || seg.equals(".")) {
                throw new ArchiveException("UNSAFE_PATH", "危険なエントリー名: " + printable(name));
            }
        }
    }

    private static boolean isText(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_SUFFIXES.contains(name.substring(dot));
    }

    /**
     * 上限つきで読む。{@code compressed} が 0 以下なら圧縮率は判定しない（同梱 JAR の中など、
     * 外側の展開量の上限で守られている場合）。{@code in} は閉じない。
     */
    private static byte[] readBounded(InputStream in, String name, long compressed, Limits limits) throws IOException, ArchiveException {
        List<byte[]> chunks = new ArrayList<>();
        long read = 0;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            read += n;
            if (read > limits.maxEntryBytes()) {
                throw new ArchiveException("JAR_LIMIT_EXCEEDED", "エントリーが大きすぎる: " + name);
            }
            if (compressed > 0 && read > (1L << 20) && read / compressed > limits.maxRatio()) {
                throw new ArchiveException("JAR_LIMIT_EXCEEDED", "圧縮率が異常に高い（ZIP 爆弾の疑い）: " + name);
            }
            byte[] c = new byte[n];
            System.arraycopy(buf, 0, c, 0, n);
            chunks.add(c);
        }
        byte[] out = new byte[(int) read];
        int pos = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length;
        }
        return out;
    }

    private static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            b.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        return b.toString();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
