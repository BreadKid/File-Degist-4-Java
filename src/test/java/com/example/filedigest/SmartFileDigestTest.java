package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartFileDigestTest {

    @TempDir
    Path tempDir;

    /** 小文件：结果必须与内存版(方案1)一致。 */
    @Test
    void smallFileMatchesInMemory() throws Exception {
        var f = tempDir.resolve("small.txt");
        Files.writeString(f, "small file under threshold", StandardCharsets.UTF_8);

        assertEquals(SmallFileDigest.digest(f, "SHA-256"),
                SmartFileDigest.digest(f, "SHA-256"));
    }

    /** 结果与流式版(方案2)也一致（三个实现殊途同归）。 */
    @Test
    void matchesStreamingToo() throws Exception {
        var f = tempDir.resolve("cross.txt");
        Files.writeString(f, "cross-check all three", StandardCharsets.UTF_8);

        assertEquals(StreamingDigest.digest(f, "SHA-256"),
                SmartFileDigest.digest(f, "SHA-256"));
    }

    /** 阈值边界：等于阈值走内存(阈值>0且size>threshold才流式)。 */
    @Test
    void thresholdBoundary() throws Exception {
        var data = new byte[1000];
        new java.util.Random(1).nextBytes(data);
        var f = tempDir.resolve("bound.bin");
        Files.write(f, data);

        // size=1000，threshold=1000 -> 1000>1000 为 false，走内存
        var smart = SmartFileDigest.digest(f, "SHA-256", 1000);
        assertEquals(SmallFileDigest.digest(f, "SHA-256"), smart);

        // threshold=999 -> 1000>999 为 true，走流式
        var smart2 = SmartFileDigest.digest(f, "SHA-256", 999);
        assertEquals(StreamingDigest.digest(f, "SHA-256"), smart2);
        assertEquals(smart, smart2); // 两者结果一致
    }

    /** 阈值 <= 0：总是走内存版。 */
    @Test
    void zeroOrNegativeThresholdAlwaysMemory() throws Exception {
        var data = new byte[1024 * 1024];
        new java.util.Random(2).nextBytes(data);
        var f = tempDir.resolve("forced.bin");
        Files.write(f, data);

        assertEquals(SmallFileDigest.digest(f, "SHA-256"),
                SmartFileDigest.digest(f, "SHA-256", 0));
        assertEquals(SmallFileDigest.digest(f, "SHA-256"),
                SmartFileDigest.digest(f, "SHA-256", -5));
    }

    /** 真实 84MB PDF（> 默认 64MB 阈值）：自动走流式，结果与独立实现一致。 */
    @Test
    void realPdfOverThresholdUsesStreaming() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        // 84MB > 64MB 默认阈值 -> 走流式
        var smart = SmartFileDigest.digest(pdf, "SHA-256");
        assertEquals(StreamingDigest.digest(pdf, "SHA-256"), smart);

        // 与整文件内存版一致
        assertEquals(SmallFileDigest.digest(pdf, "SHA-256"), smart);
        assertTrue(Files.size(pdf) > SmartFileDigest.DEFAULT_THRESHOLD_BYTES);
    }

    /** 默认入口用 SHA-256。 */
    @Test
    void defaultEntryPoint() throws Exception {
        var f = tempDir.resolve("d.txt");
        Files.writeString(f, "default", StandardCharsets.UTF_8);
        assertEquals(SmallFileDigest.digest(f, "SHA-256"), SmartFileDigest.digest(f));
    }
}
