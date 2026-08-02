package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAlgoDigestTest {

    @TempDir
    Path tempDir;

    /** 核心：组合的 SHA-256 == 方案 2 流式 SHA-256，MD5 == 独立 MD5。 */
    @Test
    void combinedMatchesIndependentAlgorithms() throws Exception {
        var f = tempDir.resolve("combined.txt");
        var content = "multi-algorithm single-pass".repeat(500).getBytes(StandardCharsets.UTF_8);
        Files.write(f, content);

        var result = MultiAlgoDigest.digest(f, "SHA-256", "MD5");
        assertEquals(StreamingDigest.digest(f, "SHA-256"), result.get("SHA-256"));
        assertEquals(StreamingDigest.digest(f, "MD5"), result.get("MD5"));
    }

    /** 单次 I/O 组合三个算法，每个都与独立计算一致。 */
    @Test
    void threeAlgorithmsMatch() throws Exception {
        var f = tempDir.resolve("three.txt");
        Files.writeString(f, "three algorithms in one pass", StandardCharsets.UTF_8);

        var result = MultiAlgoDigest.digest(f, "SHA-256", "SHA-1", "MD5");
        assertEquals(3, result.size());
        assertEquals(StreamingDigest.digest(f, "SHA-256"), result.get("SHA-256"));
        assertEquals(StreamingDigest.digest(f, "SHA-1"), result.get("SHA-1"));
        assertEquals(StreamingDigest.digest(f, "MD5"), result.get("MD5"));
    }

    /** 返回值顺序与传入算法顺序一致（LinkedHashMap 保序）。 */
    @Test
    void resultOrderPreserved() throws Exception {
        var f = tempDir.resolve("order.txt");
        Files.writeString(f, "order matters", StandardCharsets.UTF_8);

        var result = MultiAlgoDigest.digest(f, "MD5", "SHA-256");
        assertEquals("MD5", result.keySet().iterator().next());
    }

    /** 空算法列表应被拒绝。 */
    @Test
    void emptyAlgorithmsRejected() throws Exception {
        var f = tempDir.resolve("e.txt");
        Files.writeString(f, "hi", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> MultiAlgoDigest.digest(f));
    }

    /** 组合结果与单算法哈希不同（SHA-256 和 MD5 长度不同）。 */
    @Test
    void differentAlgorithmsDifferentLength() throws Exception {
        var f = tempDir.resolve("len.txt");
        Files.writeString(f, "length difference", StandardCharsets.UTF_8);

        var result = MultiAlgoDigest.digestSha256AndMd5(f);
        assertEquals(64, result.get("SHA-256").length());
        assertEquals(32, result.get("MD5").length());
        assertNotEquals(result.get("SHA-256"), result.get("MD5"));
    }

    /** 非法算法名抛 NoSuchAlgorithmException。 */
    @Test
    void invalidAlgorithmRejected() throws Exception {
        var f = tempDir.resolve("bad.txt");
        Files.writeString(f, "bad algo", StandardCharsets.UTF_8);
        assertThrows(NoSuchAlgorithmException.class,
                () -> MultiAlgoDigest.digest(f, "SHA-256", "NOT_AN_ALGO"));
    }

    /** 真实 84MB PDF：组合的 SHA-256 与独立流式一致。 */
    @Test
    void realPdfCombinedMatches() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        var result = MultiAlgoDigest.digestSha256AndMd5(pdf);
        assertEquals(StreamingDigest.digest(pdf, "SHA-256"), result.get("SHA-256"));
        assertEquals(StreamingDigest.digest(pdf, "MD5"), result.get("MD5"));
        assertTrue(result.get("SHA-256").length() == 64);
    }
}
