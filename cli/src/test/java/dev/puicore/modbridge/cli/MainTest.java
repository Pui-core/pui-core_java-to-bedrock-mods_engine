package dev.puicore.modbridge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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
}
