package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedChunkDigestTest {

    @TempDir
    Path tempDir;

    /**
     * 核心正确性 #1：单块文件(< 分块大小)时，该块的哈希必须等于整文件 SHA-256
     * （因为这一块就是整个文件），从而与方案 1/2 交叉验证一致。
     */
    @Test
    void singleChunkMatchesWholeFile() throws Exception {
        var f = tempDir.resolve("small.txt");
        var content = "hello fixed chunk digest".getBytes(StandardCharsets.UTF_8);
        Files.write(f, content);

        var result = FixedChunkDigest.digest(f, 4 * 1024 * 1024, "SHA-256");
        assertEquals(1, result.chunkHashes().size());
        assertEquals(SmallFileDigest.digest(f, "SHA-256"), result.chunkHashes().get(0));
    }

    /**
     * 核心正确性 #2：多块文件，逐块独立校验。
     * 9MB 文件按 4MB 分块 -> 3 块(4MB+4MB+1MB)，每块哈希应等于对应字节区间的 SHA-256。
     */
    @Test
    void eachChunkHashCorrect() throws Exception {
        var data = new byte[9 * 1024 * 1024];
        new java.util.Random(7).nextBytes(data);
        var f = tempDir.resolve("nineMB.bin");
        Files.write(f, data);

        int chunkSize = 4 * 1024 * 1024;
        var result = FixedChunkDigest.digest(f, chunkSize, "SHA-256");
        assertEquals(3, result.chunkHashes().size());

        // 逐块用独立流式哈希验证
        assertEquals(hashOf(data, 0, chunkSize), result.chunkHashes().get(0));
        assertEquals(hashOf(data, chunkSize, chunkSize), result.chunkHashes().get(1));
        assertEquals(hashOf(data, 2 * chunkSize, data.length - 2 * chunkSize), result.chunkHashes().get(2));
    }

    /** 确定性：同文件同分块大小 -> 块哈希列表与最终指纹都一致。 */
    @Test
    void deterministic() throws Exception {
        var f = tempDir.resolve("det.txt");
        Files.write(f, "deterministic check".getBytes(StandardCharsets.UTF_8));

        var r1 = FixedChunkDigest.digest(f, 1024, "SHA-256");
        var r2 = FixedChunkDigest.digest(f, 1024, "SHA-256");
        assertEquals(r1.chunkHashes(), r2.chunkHashes());
        assertEquals(r1.fileDigest(), r2.fileDigest());
    }

    /** 分块大小影响边界 -> 最终指纹通常不同（体现分块粒度差异）。 */
    @Test
    void chunkSizeChangesResult() throws Exception {
        var f = tempDir.resolve("size.txt");
        Files.write(f, "chunk size matters for a multi-block file".getBytes(StandardCharsets.UTF_8));

        // 文件较小，用不同分块大小(1B vs 7B)制造不同块边界
        var r1 = FixedChunkDigest.digest(f, 1, "SHA-256");
        var r2 = FixedChunkDigest.digest(f, 7, "SHA-256");
        assertTrue(r1.chunkHashes().size() > 1);
        assertNotEquals(r1.fileDigest(), r2.fileDigest());
    }

    /** 空文件 -> 仍产生 1 块，块哈希为空串 SHA-256，最终指纹可复现。 */
    @Test
    void emptyFile() throws Exception {
        var f = tempDir.resolve("empty.txt");
        Files.write(f, new byte[0]);

        var result = FixedChunkDigest.digest(f, 4096, "SHA-256");
        assertEquals(1, result.chunkHashes().size());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                result.chunkHashes().get(0));
    }

    /**
     * 真实大文件冒烟：对 84MB PDF 跑默认 4MB 分块，应产生 ~21 块，
     * 且块哈希总和字节长度 = 块数*32 字节，确定性复现。
     */
    @Test
    void realLargePdfSmoke() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return; // 大文件未放时跳过
        }

        var r1 = FixedChunkDigest.digest(pdf);
        var r2 = FixedChunkDigest.digest(pdf);
        int expectedChunks = (int) ((Files.size(pdf) + FixedChunkDigest.DEFAULT_CHUNK_SIZE - 1)
                / FixedChunkDigest.DEFAULT_CHUNK_SIZE);
        assertEquals(expectedChunks, r1.chunkHashes().size());
        assertEquals(r1.fileDigest(), r2.fileDigest());
        assertNotEquals(r1.fileDigest(), StreamingDigest.digest(pdf, "SHA-256")); // 分块指纹≠整文件哈希
    }

    /** 便捷入口默认值可用。 */
    @Test
    void defaultEntryPoint() throws Exception {
        var f = tempDir.resolve("default.txt");
        Files.writeString(f, "default chunk + sha256", StandardCharsets.UTF_8);
        var result = FixedChunkDigest.digest(f);
        assertEquals(1, result.chunkHashes().size());
        assertTrue(result.fileDigest().length() == 64);
    }

    /**
     * 整除边界：文件大小恰好是 chunkSize 的整数倍时，应分成 N 个满块，
     * 不能多出 0 字节的空块。8MB 文件 / 4MB 块 = 正好 2 块。
     */
    @Test
    void exactMultipleChunkCount() throws Exception {
        int chunkSize = 4 * 1024 * 1024;
        var data = new byte[2 * chunkSize]; // 8MB，正好 2 块
        new java.util.Random(3).nextBytes(data);
        var f = tempDir.resolve("exact.bin");
        Files.write(f, data);

        var result = FixedChunkDigest.digest(f, chunkSize, "SHA-256");
        assertEquals(2, result.chunkHashes().size());
        assertEquals(2, data.length / chunkSize); // 断言本身自洽
    }

    /**
     * 大规模一致性：100 个随机分块的块哈希，应等于逐块独立计算的 SHA-256。
     * 覆盖固定分块在多块场景下位置偏移的正确性。
     */
    @Test
    void manyChunksEachCorrect() throws Exception {
        int chunkSize = 1024;
        int chunks = 100;
        var data = new byte[chunks * chunkSize];
        new java.util.Random(11).nextBytes(data);
        var f = tempDir.resolve("many.bin");
        Files.write(f, data);

        var result = FixedChunkDigest.digest(f, chunkSize, "SHA-256");
        assertEquals(chunks, result.chunkHashes().size());
        for (int i = 0; i < chunks; i++) {
            assertEquals(hashOf(data, i * chunkSize, chunkSize), result.chunkHashes().get(i));
        }
    }

    /** 1 字节文件：单块，块哈希 = 该字节的 SHA-256。 */
    @Test
    void singleByteFile() throws Exception {
        var f = tempDir.resolve("one.bin");
        Files.write(f, new byte[]{(byte) 0xAB});

        var result = FixedChunkDigest.digest(f, 4096, "SHA-256");
        assertEquals(1, result.chunkHashes().size());
        assertEquals(hashOf(new byte[]{(byte) 0xAB}, 0, 1), result.chunkHashes().get(0));
    }

    // ---- helpers ----

    private static String hashOf(byte[] data, int off, int len)
            throws NoSuchAlgorithmException {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(data, off, len);
        return HexFormat.of().formatHex(md.digest());
    }
}
