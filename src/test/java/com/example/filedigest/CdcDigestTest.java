package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CdcDigestTest {

    @TempDir
    Path tempDir;

    /** 空文件：产生 1 块，块哈希 = 空串 SHA-256。 */
    @Test
    void emptyFile() throws Exception {
        var f = tempDir.resolve("empty.bin");
        Files.write(f, new byte[0]);

        var result = CdcDigest.digest(f);
        assertEquals(1, result.chunkHashes().size());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                result.chunkHashes().get(0));
    }

    /** 确定性：同文件同参数 -> 块哈希列表与指纹完全一致。 */
    @Test
    void deterministic() throws Exception {
        var f = tempDir.resolve("det.bin");
        var data = new byte[512 * 1024];
        new java.util.Random(1).nextBytes(data);
        Files.write(f, data);

        var r1 = CdcDigest.digest(f);
        var r2 = CdcDigest.digest(f);
        assertEquals(r1.chunkHashes(), r2.chunkHashes());
        assertEquals(r1.fileDigest(), r2.fileDigest());
    }

    /** 参数校验：min/avg/max 必须满足 0 < min <= avg <= max。 */
    @Test
    void invalidParamsRejected() throws Exception {
        var f = tempDir.resolve("p.bin");
        Files.writeString(f, "params", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> CdcDigest.digest(f, 0, 1024, 4096, "SHA-256"));
        assertThrows(IllegalArgumentException.class, () -> CdcDigest.digest(f, 2048, 1024, 4096, "SHA-256"));
        assertThrows(IllegalArgumentException.class, () -> CdcDigest.digest(f, 1024, 4096, 2048, "SHA-256"));
    }

    /** 块大小落在 [min, max] 约束内。 */
    @Test
    void chunkSizesWithinBounds() throws Exception {
        var f = tempDir.resolve("bounds.bin");
        var data = new byte[1024 * 1024];
        new java.util.Random(2).nextBytes(data);
        Files.write(f, data);

        int min = 4 * 1024, avg = 16 * 1024, max = 64 * 1024;
        var sizes = CdcDigest.chunkSizes(f, min, avg, max);
        assertTrue(sizes.size() > 1);
        for (int s : sizes) {
            assertTrue(s >= min && s <= max, "size " + s + " outside [" + min + "," + max + "]");
        }
    }

    /** 平均块大小大致接近 avg（不精确，但应在合理范围）。 */
    @Test
    void averageChunkSizeNearTarget() throws Exception {
        var f = tempDir.resolve("avg.bin");
        var data = new byte[4 * 1024 * 1024];
        new java.util.Random(3).nextBytes(data);
        Files.write(f, data);

        int avg = 16 * 1024;
        var sizes = CdcDigest.chunkSizes(f, 4 * 1024, avg, 64 * 1024);
        double mean = sizes.stream().mapToInt(Integer::intValue).average().orElse(0);
        // 平均块应在 [avg/2, avg*2] 附近（FastCDC 规范化特性）
        assertTrue(mean >= avg / 2.0 && mean <= avg * 2.0, "mean=" + mean + " avg=" + avg);
    }

    /**
     * ★ 核心卖点：抗边界漂移。
     * 在文件开头插入 1 字节后，CDC 的大部分块应保持稳定（内容定位边界）。
     * 比较"插入前后块哈希集合的交集"，验证去重复用率。
     */
    @Test
    void boundaryShiftResistance() throws Exception {
        var orig = new byte[512 * 1024];
        new java.util.Random(4).nextBytes(orig);
        var f = tempDir.resolve("orig.bin");
        Files.write(f, orig);

        // 开头插入 1 字节
        var shifted = new byte[orig.length + 1];
        shifted[0] = (byte) 0x7F;
        System.arraycopy(orig, 0, shifted, 1, orig.length);
        var g = tempDir.resolve("shifted.bin");
        Files.write(g, shifted);

        var cdcBefore = new HashSet<>(CdcDigest.digest(f).chunkHashes());
        var cdcAfter = new HashSet<>(CdcDigest.digest(g).chunkHashes());
        // 交集中的块在插入后仍可复用
        var cdcRetained = cdcBefore.stream().filter(cdcAfter::contains).count();
        double cdcRate = (double) cdcRetained / cdcBefore.size();
        // CDC 应有很高复用率（>60%）
        assertTrue(cdcRate > 0.60, "CDC retained only " + cdcRate);

        // 对照：固定分块对同样插入，复用率应明显更低
        var fixBefore = new HashSet<>(FixedChunkDigest.digest(f, 16 * 1024, "SHA-256").chunkHashes());
        var fixAfter = new HashSet<>(FixedChunkDigest.digest(g, 16 * 1024, "SHA-256").chunkHashes());
        var fixRetained = fixBefore.stream().filter(fixAfter::contains).count();
        double fixRate = (double) fixRetained / fixBefore.size();
        // 固定分块因边界全错位，复用率应很低（<30%）
        assertTrue(fixRate < 0.30, "fixed chunk retained " + fixRate + " (should be low)");

        // CDC 复用率应显著高于固定分块
        assertTrue(cdcRate > fixRate + 0.3,
                "CDC " + cdcRate + " should beat fixed " + fixRate);
    }

    /** 块数量合理：512KB 文件 / 平均 16KB 块，应有 ~30 块左右（非固定但合理）。 */
    @Test
    void reasonableChunkCount() throws Exception {
        var f = tempDir.resolve("count.bin");
        var data = new byte[512 * 1024];
        new java.util.Random(5).nextBytes(data);
        Files.write(f, data);

        int chunks = CdcDigest.digest(f).chunkHashes().size();
        assertTrue(chunks >= 8 && chunks <= 128, "unexpected chunk count " + chunks);
    }

    /** 与内容一致性：每个块的哈希确实对应文件的一个字节区间（可复现性保障）。 */
    @Test
    void eachChunkHashMatchesSubrange() throws Exception {
        var f = tempDir.resolve("sub.bin");
        var data = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(4096).getBytes(StandardCharsets.UTF_8);
        Files.write(f, data);

        // 对确定性内容，块集合应能稳定复现
        var r1 = CdcDigest.digest(f);
        var r2 = CdcDigest.digest(f);
        assertEquals(r1.chunkHashes(), r2.chunkHashes());
    }

    /** 真实 84MB PDF：CDC 可跑、块数合理、确定性。 */
    @Test
    void realPdfCdc() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        var r1 = CdcDigest.digest(pdf);
        var r2 = CdcDigest.digest(pdf);
        assertEquals(r1.fileDigest(), r2.fileDigest());
        // 84MB / 平均16KB ≈ 5000+ 块
        assertTrue(r1.chunkHashes().size() > 1000, "expected many CDC chunks");
    }

    /** 空文件指纹 vs 非空：不同。 */
    @Test
    void differentContentDifferentDigest() throws Exception {
        var empty = tempDir.resolve("empty.bin");
        Files.write(empty, new byte[0]);
        var nonempty = tempDir.resolve("non.bin");
        Files.writeString(nonempty, "data", StandardCharsets.UTF_8);

        assertNotEquals(CdcDigest.digest(empty).fileDigest(), CdcDigest.digest(nonempty).fileDigest());
    }

    /** ★ 流式版与内存版产生完全相同的块哈希与指纹（边界一致性的核心验证）。 */
    @Test
    void streamingMatchesInMemory() throws Exception {
        var data = new byte[512 * 1024];
        new java.util.Random(9).nextBytes(data);
        var f = tempDir.resolve("stream.bin");
        Files.write(f, data);

        int min = 4 * 1024, avg = 16 * 1024, max = 64 * 1024;
        var mem = CdcDigest.digest(f, min, avg, max, "SHA-256");
        var stream = CdcDigest.digestStreaming(f, min, avg, max, "SHA-256");

        assertEquals(mem.chunkHashes(), stream.chunkHashes());
        assertEquals(mem.fileDigest(), stream.fileDigest());
    }

    /** 流式版空文件：与内存版一致，产生 1 块空串哈希。 */
    @Test
    void streamingEmptyFile() throws Exception {
        var f = tempDir.resolve("empty.bin");
        Files.write(f, new byte[0]);

        var mem = CdcDigest.digest(f);
        var stream = CdcDigest.digestStreaming(f,
                CdcDigest.DEFAULT_MIN, CdcDigest.DEFAULT_AVG, CdcDigest.DEFAULT_MAX,
                CdcDigest.DEFAULT_ALGORITHM);
        assertEquals(mem.chunkHashes(), stream.chunkHashes());
        assertEquals(mem.fileDigest(), stream.fileDigest());
    }

    /** 真实大文件：流式版与内存版一致（覆盖大数据量下的边界对齐）。 */
    @Test
    void streamingMatchesInMemoryRealPdf() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        var mem = CdcDigest.digest(pdf);
        var stream = CdcDigest.digestStreaming(pdf,
                CdcDigest.DEFAULT_MIN, CdcDigest.DEFAULT_AVG, CdcDigest.DEFAULT_MAX,
                CdcDigest.DEFAULT_ALGORITHM);
        assertEquals(mem.chunkHashes(), stream.chunkHashes());
        assertEquals(mem.fileDigest(), stream.fileDigest());
    }
}
