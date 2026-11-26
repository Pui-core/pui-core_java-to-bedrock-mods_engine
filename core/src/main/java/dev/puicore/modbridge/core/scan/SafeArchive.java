package dev.puicore.modbridge.core.scan;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * JAR（ZIP）を展開せずに読む。クラスの読込もコード実行もしない。
 *
 * <ul>
 *   <li>危険なエントリー名（{@code ..}、絶対パス、ドライブ指定、バックスラッシュ、NUL）を拒否する（ZIP Slip 対策）</li>
 *   <li>エントリー数・個別サイズ・合計読込量・圧縮率に上限を設ける（ZIP 爆弾対策）。
 *       上限は宣言サイズではなく、実際に読んだバイト数で判定する</li>
 *   <li>内容を読むのはテキスト系（JSON・lang・toml・mcmeta）だけ。画像などは存在だけを記録する</li>
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
    private static final Set<String> TEXT_SUFFIXES = Set.of(".json", ".lang", ".toml", ".mcmeta", ".properties");

    private final String fileName;
    private final String sha256;
    private final long size;
    private final Set<String> names;
    private final Map<String, byte[]> texts;

    private SafeArchive(String fileName, String sha256, long size, Set<String> names, Map<String, byte[]> texts) {
        this.fileName = fileName;
        this.sha256 = sha256;
        this.size = size;
        this.names = names;
        this.texts = texts;
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

    /** テキスト系エントリーの内容。対象外・不在なら null。 */
    public byte[] text(String name) {
        return texts.get(name);
    }

    public static SafeArchive open(Path path) throws ArchiveException {
        return open(path, Limits.DEFAULT);
    }

    public static SafeArchive open(Path path, Limits limits) throws ArchiveException {
        long size;
        String sha;
        try {
            size = Files.size(path);
            if (size > limits.maxJarBytes()) {
                throw new ArchiveException("JAR_LIMIT_EXCEEDED", "JAR が大きすぎる（" + size + " バイト、上限 " + limits.maxJarBytes() + "）");
            }
            sha = sha256(path);
        } catch (IOException e) {
            throw new ArchiveException("IO_ERROR", "読めない: " + e.getMessage(), e);
        }

        Set<String> names = new TreeSet<>();
        Map<String, byte[]> texts = new TreeMap<>();
        long totalRead = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.size() > limits.maxEntries()) {
                throw new ArchiveException("JAR_LIMIT_EXCEEDED", "エントリー数が多すぎる（" + zip.size() + "、上限 " + limits.maxEntries() + "）");
            }
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
                if (!isText(name)) {
                    continue;
                }
                byte[] data = readBounded(zip, e, limits);
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
            throw new ArchiveException("JAR_CORRUPT", "JAR として読めない: " + e.getMessage(), e);
        }
        return new SafeArchive(path.getFileName().toString(), sha, size,
                Collections.unmodifiableSet(names), Collections.unmodifiableMap(texts));
    }

    static void checkName(String name) throws ArchiveException {
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

    private static byte[] readBounded(ZipFile zip, ZipEntry e, Limits limits) throws IOException, ArchiveException {
        long compressed = Math.max(e.getCompressedSize(), 1);
        List<byte[]> chunks = new ArrayList<>();
        long read = 0;
        try (InputStream in = zip.getInputStream(e)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                read += n;
                if (read > limits.maxEntryBytes()) {
                    throw new ArchiveException("JAR_LIMIT_EXCEEDED", "エントリーが大きすぎる: " + e.getName());
                }
                if (read > (1L << 20) && read / compressed > limits.maxRatio()) {
                    throw new ArchiveException("JAR_LIMIT_EXCEEDED", "圧縮率が異常に高い（ZIP 爆弾の疑い）: " + e.getName());
                }
                byte[] c = new byte[n];
                System.arraycopy(buf, 0, c, 0, n);
                chunks.add(c);
            }
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
