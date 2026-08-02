package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TwoLevelDigest 并发压力测试。
 * 验证：多线程同时 resolve，缓存/计数线程安全，同一文件只读盘一次。
 */
class TwoLevelDigestConcurrencyTest {

    @TempDir
    Path tempDir;

    /** 核心：多线程首次并发访问同一文件 -> 恰好读盘一次，所有结果正确。 */
    @Test
    void concurrentFirstAccessReadsOnce() throws Exception {
        var f = tempDir.resolve("hot.bin");
        var data = new byte[512 * 1024];
        new java.util.Random(1).nextBytes(data);
        Files.write(f, data);
        var expected = StreamingDigest.digest(f, "SHA-256");

        var digest = new TwoLevelDigest();
        int threads = 32;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> digest.resolve(f, "SHA-256").contentHash());
            }
            var results = pool.invokeAll(tasks);
            for (var r : results) {
                assertEquals(expected, r.get());   // 每个线程都返回正确哈希
            }
        }

        assertEquals(1, digest.contentReads());    // 并发下只读盘一次
    }

    /** 并发首次 + 随后命中：读盘数 == 文件数（每个文件各一次），命中返回缓存。 */
    @Test
    void concurrentDistinctFilesEachReadOnce() throws Exception {
        var digest = new TwoLevelDigest();
        int fileCount = 8;
        var files = new ArrayList<Path>();
        for (int i = 0; i < fileCount; i++) {
            var f = tempDir.resolve("f" + i + ".txt");
            Files.writeString(f, "content " + i, StandardCharsets.UTF_8);
            files.add(f);
        }

        // 第一轮并发：每个文件首次访问
        try (var pool = Executors.newFixedThreadPool(fileCount)) {
            var tasks = new ArrayList<Callable<String>>();
            for (var f : files) {
                tasks.add(() -> digest.resolve(f, "SHA-256").contentHash());
            }
            pool.invokeAll(tasks).forEach(r -> {
                try { r.get(); } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        assertEquals(fileCount, digest.contentReads()); // 8 文件各读一次

        // 第二轮并发：都应命中缓存，不新增读盘
        long readsBefore = digest.contentReads();
        try (var pool = Executors.newFixedThreadPool(fileCount)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (var f : files) {
                tasks.add(() -> digest.resolve(f, "SHA-256").fromCache());
            }
            var results = pool.invokeAll(tasks);
            for (var r : results) {
                assertTrue(r.get());   // 都命中缓存
            }
        }
        assertEquals(readsBefore, digest.contentReads()); // 读盘数不变
    }

    /** 多线程并发命中缓存：零新增读盘，返回正确哈希。 */
    @Test
    void concurrentCacheHitsNoExtraReads() throws Exception {
        var f = tempDir.resolve("cached.bin");
        Files.writeString(f, "concurrent cache hits", StandardCharsets.UTF_8);
        var digest = new TwoLevelDigest();
        digest.resolve(f, "SHA-256"); // 预热

        int threads = 64;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> digest.resolve(f, "SHA-256").fromCache());
            }
            var results = pool.invokeAll(tasks);
            for (var r : results) {
                assertTrue(r.get());   // 全部命中
            }
        }
        assertEquals(1, digest.contentReads());   // 预热那一次，无新增
    }

    /**
     * 串行多次修改内容：每次属性变化都触发恰一次读盘，结果始终对应最新内容。
     * （并发写同一文件属于文件系统层一致性问题，非 TwoLevelDigest 职责，此处用串行验证计数正确性。）
     */
    @Test
    void sequentialMutationsEachResolvedOnce() throws Exception {
        var f = tempDir.resolve("mut.bin");
        Files.writeString(f, "v0", StandardCharsets.UTF_8);
        var digest = new TwoLevelDigest();

        int rounds = 10;
        for (int i = 1; i <= rounds; i++) {
            Files.writeString(f, "v" + i, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(f,
                    java.nio.file.attribute.FileTime.fromMillis(
                            System.currentTimeMillis() + 10_000 + i));
            var r = digest.resolve(f, "SHA-256");
            // 结果必须对应最新内容
            assertEquals(StreamingDigest.digest(f, "SHA-256"), r.contentHash());
            assertTrue(!r.fromCache());          // 内容变 -> 不命中
            assertEquals(i, digest.contentReads()); // 首次为1，每次变更+1
        }
    }

    /** 大并发 + 真实大文件：只读一次，吞吐不因并发而重复读盘。 */
    @Test
    void concurrentRealPdfReadsOnce() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        var digest = new TwoLevelDigest();
        int threads = 16;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> digest.resolve(pdf, "SHA-256").contentHash());
            }
            var results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
            var first = results.get(0).get();
            for (var r : results) {
                assertEquals(first, r.get());   // 所有线程哈希一致
            }
        }
        assertEquals(1, digest.contentReads()); // 84MB 并发下只读一次
    }
}
