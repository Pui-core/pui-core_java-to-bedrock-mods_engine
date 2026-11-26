package dev.puicore.modbridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** ビルド時にバージョンカタログから生成した対象バージョン一覧。 */
public final class TargetVersions {
    private static final String RESOURCE = "target-versions.properties";
    private static final Map<String, String> VALUES = load();

    private TargetVersions() {}

    public static Map<String, String> all() {
        return VALUES;
    }

    public static String get(String key) {
        String v = VALUES.get(key);
        if (v == null) {
            throw new IllegalArgumentException("未定義の対象バージョン: " + key);
        }
        return v;
    }

    /** 実行に必要な最小の Java フィーチャーリリース。 */
    public static int requiredJavaFeature() {
        return Integer.parseInt(get("java"));
    }

    private static Map<String, String> load() {
        try (InputStream in = TargetVersions.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " が見つからない（ビルド生成物の欠落）");
            }
            Properties p = new Properties();
            p.load(in);
            Map<String, String> m = new TreeMap<>();
            p.stringPropertyNames().forEach(k -> m.put(k, p.getProperty(k)));
            return Collections.unmodifiableMap(m);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
