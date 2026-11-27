package dev.puicore.modbridge.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.puicore.modbridge.core.compile.PackCompiler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void 引数なしは使い方の誤り() {
        assertEquals(Main.USAGE, run());
    }

    @Test
    void 不明なコマンドは使い方の誤り() {
        assertEquals(Main.USAGE, run("explode"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("不明なコマンド"));
    }

    @Test
    void versionは対象バージョンを表示する() {
        assertEquals(Main.OK, run("version"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("minecraft = 26.3"));
    }

    @Test
    void doctorはテスト環境のJavaで成功する() {
        assertEquals(Main.OK, run("doctor"));
    }

    @Test
    void 素材変換はキャッシュを使っても同じ診断と成果物を返す(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("demo.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("""
                    {"schemaVersion":1,"id":"demo","version":"1","entrypoints":{"main":["demo.Example"]}}
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path output = dir.resolve("out");
        Path cache = dir.resolve("cache");
        String[] args = {"compile", jar.toString(), "--out", output.toString(), "--cache", cache.toString()};

        assertEquals(Main.OK, run(args));
        String first = out.toString(StandardCharsets.UTF_8);
        assertFalse(first.contains("キャッシュを使う"));
        assertTrue(first.contains("JAVA_LOGIC_NOT_CONVERTED"), first);
        assertTrue(first.contains("変換結果（リソースパック）"), first);
        assertTrue(first.contains("変換結果（ビヘイビアパック）"), first);
        var firstFiles = new java.util.HashMap<String, byte[]>();
        for (String file : PackCompiler.OUTPUT_FILES) {
            firstFiles.put(file, Files.readAllBytes(output.resolve(file)));
        }

        out.reset();
        assertEquals(Main.OK, run(args));
        String second = out.toString(StandardCharsets.UTF_8);
        assertTrue(second.startsWith("キャッシュを使う: "), second);
        assertEquals(first, second.substring(second.indexOf('\n') + 1));
        for (String file : PackCompiler.OUTPUT_FILES) {
            assertArrayEquals(firstFiles.get(file), Files.readAllBytes(output.resolve(file)), file);
        }
    }
}
