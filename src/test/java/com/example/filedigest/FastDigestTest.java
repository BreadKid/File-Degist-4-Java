package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastDigestTest {

    @TempDir
    Path tempDir;

    /** 已知向量：空串 CRC32 = 0。 */
    @Test
    void crc32EmptyKnownVector() throws Exception {
        var f = tempDir.resolve("empty.txt");
        Files.write(f, new byte[0]);
        assertEquals(0L, FastDigest.crc32(f));
        assertEquals("00000000", FastDigest.crc32Hex(f));
    }

    /** 已知向量："123456789" 的 CRC32 = 0xCBF43926（IEEE 标准校验向量）。 */
    @Test
    void crc32KnownVector() throws Exception {
        var f = tempDir.resolve("check.txt");
        Files.writeString(f, "123456789", StandardCharsets.UTF_8);
        assertEquals(0xCBF43926L, FastDigest.crc32(f));
        assertEquals("cbf43926", FastDigest.crc32Hex(f));
    }

    /** 确定性：相同内容 -> 相同 CRC；不同内容 -> 不同 CRC。 */
    @Test
    void deterministicAndDistinct() throws Exception {
        var a = tempDir.resolve("a.txt");
        var b = tempDir.resolve("b.txt");
        var c = tempDir.resolve("c.txt");
        Files.writeString(a, "hello", StandardCharsets.UTF_8);
        Files.writeString(b, "hello", StandardCharsets.UTF_8);
        Files.writeString(c, "hello!", StandardCharsets.UTF_8);

        assertEquals(FastDigest.crc32(a), FastDigest.crc32(b));
        assertNotEquals(FastDigest.crc32(a), FastDigest.crc32(c));
    }

    /** CRC32C 也有确定性，且不同内容不同值。 */
    @Test
    void crc32cDeterministicAndDistinct() throws Exception {
        var a = tempDir.resolve("a.txt");
        var b = tempDir.resolve("b.txt");
        Files.writeString(a, "crc32c check", StandardCharsets.UTF_8);
        Files.writeString(b, "crc32c check!", StandardCharsets.UTF_8);

        assertEquals(FastDigest.crc32c(a), FastDigest.crc32c(a));
        assertNotEquals(FastDigest.crc32c(a), FastDigest.crc32c(b));
    }

    /** 与 java.util.zip.CRC32 独立手工计算交叉验证。 */
    @Test
    void crossCheckWithRawCRC32() throws Exception {
        var data = "cross-validation of crc32".getBytes(StandardCharsets.UTF_8);
        var f = tempDir.resolve("cross.txt");
        Files.write(f, data);

        var raw = new CRC32();
        raw.update(data);
        assertEquals(raw.getValue(), FastDigest.crc32(f));
    }

    /** 与 Java 原生流式 CRC32 交叉验证（覆盖分块读取正确性）。 */
    @Test
    void crossCheckStreamingRaw() throws Exception {
        var data = new byte[64 * 1024 + 17]; // 非整块大小
        new java.util.Random(5).nextBytes(data);
        var f = tempDir.resolve("cross.bin");
        Files.write(f, data);

        var raw = new CRC32();
        raw.update(data);
        assertEquals(raw.getValue(), FastDigest.crc32(f));
    }

    /** hex 输出固定 8 字符（CRC32 是 32 位）。 */
    @Test
    void crc32HexLength() throws Exception {
        var f = tempDir.resolve("h.txt");
        Files.writeString(f, "hex length", StandardCharsets.UTF_8);
        assertEquals(8, FastDigest.crc32Hex(f).length());
        assertEquals(8, FastDigest.crc32cHex(f).length());
    }

    /** 真实 84MB PDF：CRC32C 结果确定可复现。 */
    @Test
    void realPdfDeterministic() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        assertEquals(FastDigest.crc32c(pdf), FastDigest.crc32c(pdf));
        assertTrue(FastDigest.crc32cHex(pdf).matches("[0-9a-f]{8}"));
    }
}
