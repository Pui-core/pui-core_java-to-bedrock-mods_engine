package dev.puicore.modbridge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanCommandTest {
    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        out.reset();
        err.reset();
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path jar(String name, String modJson) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(p); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(modJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/demo/blockstates/rock.json"));
            zip.write("{\"variants\":{}}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return p;
    }

    @Test
    void 問題のないJARは終了コード0でIRを出力する() throws IOException {
        Path j = jar("demo.jar", "{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"1\",\"depends\":{\"minecraft\":\"*\"}}");
        assertEquals(Main.OK, run("scan", j.toString()));
        assertTrue(stdout().contains("\"schema\": \"modbridge.bridge-ir\""));
        assertTrue(stdout().contains("\"id\": \"demo:rock\""));
    }

    @Test
    void ERROR診断があれば終了コード1() throws IOException {
        Path j = jar("demo.jar", "{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"1\",\"depends\":{\"absent\":\"*\"}}");
        assertEquals(Main.FAILED, run("validate", j.toString()));
        assertTrue(stdout().contains("DEPENDENCY_MISSING"));
    }

    @Test
    void outで書き出しreportで読める_同じ入力なら同じ内容() throws IOException {
        Path j = jar("demo.jar", "{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"1\"}");
        Path ir = dir.resolve("out/ir.json");
        assertEquals(Main.OK, run("scan", j.toString(), "--out", ir.toString()));
        String first = Files.readString(ir);
        assertEquals(Main.OK, run("scan", j.toString(), "--out", ir.toString()));
        assertEquals(first, Files.readString(ir));
        try (var s = Files.list(ir.getParent())) {
            assertEquals(1, s.count(), "一時ファイルを残さない");
        }
        assertEquals(Main.OK, run("report", ir.toString()));
        assertTrue(stdout().contains("block:demo:rock"));
    }

    @Test
    void 使い方の誤りは終了コード2_読めないIRは3() {
        assertEquals(Main.USAGE, run("scan"));
        assertEquals(Main.USAGE, run("scan", "--bogus"));
        assertEquals(Main.USAGE, run("scan", "x.jar", "--out"));
        assertEquals(Main.IO, run("report", dir.resolve("none.json").toString()));
    }

    @Test
    void outの重複指定と不正なIRは拒否する_スタックトレースを出さない() throws IOException {
        assertEquals(Main.USAGE, run("scan", "x.jar", "--out", "a.json", "--out", "b.json"));
        assertEquals(Main.USAGE, run("validate", "x.jar", "--runtime"));
        assertEquals(Main.USAGE, run("validate", "x.jar", "--runtime", "a.json", "--runtime", "b.json"));
        Path bad = dir.resolve("bad.json");
        for (String json : new String[] {"[]", "{\"schema\":[1]}", "{\"schema\":\"modbridge.bridge-ir\"}",
                "{\"schema\":\"modbridge.bridge-ir\",\"schemaVersion\":1,\"inputs\":\"x\"}"}) {
            Files.writeString(bad, json);
            assertEquals(Main.FAILED, run("report", bad.toString()), json);
        }
    }

    @Test
    void 書き出しに失敗しても既存のファイルを壊さず一時ファイルも残さない() throws IOException {
        // 置き換え先が中身のあるフォルダーだと、移動が失敗する
        Path target = dir.resolve("out/ir.json");
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.txt"), "keep");
        assertThrows(IOException.class, () -> Main.writeAtomically(target, "{}"));
        assertEquals("keep", Files.readString(target.resolve("keep.txt")));
        try (var s = Files.list(target.getParent())) {
            assertEquals(1, s.count(), "一時ファイルを残さない");
        }
    }

    @Test
    void 存在しない入力はERROR診断になる() {
        assertEquals(Main.FAILED, run("validate", dir.resolve("missing.jar").toString()));
        assertTrue(stdout().contains("IO_ERROR"));
    }
}
