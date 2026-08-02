package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨方案"参数契约"测试：4 个方案都接受 (path, algorithm)，对非法输入
 * 应抛出一致的异常。这是工业级对外的统一契约，避免各自为政。
 */
class FileDigestContractTest {

    @TempDir
    Path tempDir;

    /**
     * 不存在的文件：方案 1/2 抛 NoSuchFileException(IOException 子类)。
     * 方案 3 的 FileChannel.open() 在主线程执行，同样直接抛 NoSuchFileException。
     */
    @Test
    void nonexistentFileThrowsForAllSchemes() {
        var missing = tempDir.resolve("does-not-exist.bin");

        assertThrows(NoSuchFileException.class,
                () -> SmallFileDigest.digest(missing, "SHA-256"));
        assertThrows(NoSuchFileException.class,
                () -> StreamingDigest.digest(missing, "SHA-256"));
        assertThrows(NoSuchFileException.class,
                () -> FixedChunkDigest.digest(missing, 4096, "SHA-256"));
    }

    /**
     * 非法算法名：方案 1/2 直接抛 NoSuchAlgorithmException。
     * 方案 3 的 MessageDigest.getInstance 在虚拟线程内，包装为 ExecutionException(根因仍是 NoSuchAlgorithmException)。
     * 方案 4 build 有前置校验，直接抛 NoSuchAlgorithmException。
     */
    @Test
    void invalidAlgorithmThrowsForAllSchemes() throws Exception {
        var f = tempDir.resolve("ok.txt");
        Files.writeString(f, "hi", StandardCharsets.UTF_8);

        assertThrows(NoSuchAlgorithmException.class,
                () -> SmallFileDigest.digest(f, "SHA-999"));
        assertThrows(NoSuchAlgorithmException.class,
                () -> StreamingDigest.digest(f, "SHA-999"));
        assertThrows(NoSuchAlgorithmException.class,
                () -> MerkleTreeDigest.build(java.util.List.of("aa"), "SHA-999"));

        var ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> FixedChunkDigest.digest(f, 4096, "SHA-999"));
        assertTrue(ex.getCause() instanceof NoSuchAlgorithmException);
    }

    /**
     * 目录当文件传：方案 1/2 直接抛 IOException。
     * 方案 3 能打开目录 channel，但虚拟线程内 read 目录抛 IOException，
     * 被包装为 ExecutionException(根因仍是 IOException)。
     */
    @Test
    void directoryAsFileThrows() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("a-dir"));

        assertThrows(IOException.class, () -> SmallFileDigest.digest(dir, "SHA-256"));
        assertThrows(IOException.class, () -> StreamingDigest.digest(dir, "SHA-256"));
        var ex = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> FixedChunkDigest.digest(dir, 4096, "SHA-256"));
        assertTrue(ex.getCause() instanceof IOException);
    }

    /** 方案 3 chunkSize 必须 > 0：0 和负数都应明确拒绝，避免除零/无限循环。 */
    @Test
    void nonPositiveChunkSizeRejected() throws Exception {
        var f = tempDir.resolve("data.txt");
        Files.writeString(f, "chunk bounds", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> FixedChunkDigest.digest(f, 0, "SHA-256"));
        assertThrows(IllegalArgumentException.class,
                () -> FixedChunkDigest.digest(f, -1024, "SHA-256"));
    }
}
