package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingDigestTest {

    @TempDir
    Path tempDir;

    /** 空文件：流式与已知向量一致。 */
    @Test
    void emptyFile() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("empty.txt");
        Files.write(f, new byte[0]);

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                StreamingDigest.digest(f, "SHA-256"));
    }

    /**
     * 核心正确性：方案 2(流式) 必须与方案 1(整文件) 对同一文件给出完全相同的哈希。
     * 这验证了流式 update 累加逻辑没有丢字节、没有重复字节。
     */
    @Test
    void matchesSmallFileDigest() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("cross.txt");
        var content = "cross-validation between streaming and in-memory"
                .repeat(1000).getBytes(StandardCharsets.UTF_8);
        Files.write(f, content);

        var viaStreaming = StreamingDigest.digest(f, "SHA-256");
        var viaMemory = SmallFileDigest.digest(f, "SHA-256");
        assertEquals(viaMemory, viaStreaming);
    }

    /**
     * 缓冲区大小不同，结果必须一致（缓冲区只影响性能，不影响正确性）。
     * 用很小的缓冲区(1,2,3 字节)强迫大量循环读，暴露"多读脏字节"类 bug。
     */
    @Test
    void resultIndependentOfBufferSize() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("buf.txt");
        var data = "buffer size must not change the hash".getBytes(StandardCharsets.UTF_8);
        Files.write(f, data);

        var baseline = StreamingDigest.digest(f, "SHA-256");
        assertEquals(baseline, StreamingDigest.digest(f, "SHA-256", 1));
        assertEquals(baseline, StreamingDigest.digest(f, "SHA-256", 3));
        assertEquals(baseline, StreamingDigest.digest(f, "SHA-256", 4096));
    }

    /**
     * 大文件冒烟测试：生成 5MB 随机内容，验证流式与整文件内存版一致，
     * 且不抛 OOM（流式内存恒定）。
     */
    @Test
    void largeFileMatchesMemoryVersion() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("large.bin");
        var data = new byte[5 * 1024 * 1024];
        new java.util.Random(42).nextBytes(data);
        Files.write(f, data);

        assertEquals(SmallFileDigest.digest(f, "SHA-256"),
                StreamingDigest.digest(f, "SHA-256"));
    }

    /** 流式也能算 MD5，结果与已知向量一致。 */
    @Test
    void md5KnownVector() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("md5.txt");
        Files.writeString(f, "abc", StandardCharsets.UTF_8);

        // "abc" 的 MD5 已知向量
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                StreamingDigest.digest(f, "MD5"));
    }

    /** String 重载与 Path 版等价。 */
    @Test
    void stringOverloadMatchesPath() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("overload.txt");
        Files.writeString(f, "streaming string overload", StandardCharsets.UTF_8);

        assertEquals(StreamingDigest.digest(f, "SHA-256"),
                StreamingDigest.digest(f.toString(), "SHA-256"));
    }
}
