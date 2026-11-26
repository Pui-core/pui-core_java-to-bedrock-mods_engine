package dev.puicore.modbridge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DoctorTest {

    @Test
    void 対象バージョンはカタログから生成される() {
        assertEquals("26.3", TargetVersions.get("minecraft"));
        assertEquals(25, TargetVersions.requiredJavaFeature());
    }

    @Test
    void 必要版未満のJavaは失敗にする() {
        Doctor.Result r = Doctor.run(21, "UTF-8");
        assertTrue(r.failed());
        assertEquals(Doctor.Level.FAIL, r.checks().get(0).level());
    }

    @Test
    void 必要版以上なら失敗しない() {
        assertFalse(Doctor.run(25, "UTF-8").failed());
        assertFalse(Doctor.run(26, "windows-31j").failed());
    }
}
