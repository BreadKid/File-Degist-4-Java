package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoLevelDigestTest {

    @TempDir
    Path tempDir;

    /** 首次解析：L1 未命中 -> 必须升级 L2 读盘一次，返回正确内容哈希。 */
    @Test
    void firstResolveReadsContent() throws Exception {
        var f = tempDir.resolve("first.txt");
        Files.writeString(f, "hello two-level", StandardCharsets.UTF_8);
        var digest = new TwoLevelDigest();

        var r = digest.resolve(f, "SHA-256");
        assertFalse(r.fromCache());
        assertEquals(StreamingDigest.digest(f, "SHA-256"), r.contentHash());
        assertEquals(1, digest.contentReads());
    }

    /** 核心：文件未变，再次解析 L1 命中 -> 不读盘，哈希仍正确。 */
    @Test
    void unchangedFileHitsCacheNoRead() throws Exception {
        var f = tempDir.resolve("cache.txt");
        Files.writeString(f, "stable content", StandardCharsets.UTF_8);
        var digest = new TwoLevelDigest();

        digest.resolve(f, "SHA-256"); // 首次，1 次读盘
        var r = digest.resolve(f, "SHA-256"); // 未变 -> 命中缓存

        assertTrue(r.fromCache());
        assertEquals(StreamingDigest.digest(f, "SHA-256"), r.contentHash());
        assertEquals(1, digest.contentReads()); // 仍只有 1 次读盘
    }

    /** 内容变化 -> L1 哨兵检测到 mtime 变化 -> 重新读盘，哈希更新。 */
    @Test
    void contentChangeTriggersReload() throws Exception {
        var f = tempDir.resolve("change.txt");
        Files.writeString(f, "version 1", StandardCharsets.UTF_8);
        var digest = new TwoLevelDigest();

        var before = digest.resolve(f, "SHA-256").contentHash();

        // 改写内容，并强制改 mtime（同一毫秒内写入 mtime 可能不变）
        Files.writeString(f, "version 2", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 1000));
        var after = digest.resolve(f, "SHA-256");

        assertFalse(after.fromCache());
        assertFalse(before.equals(after.contentHash()));
        assertEquals(2, digest.contentReads());
    }

    /** 属性未变但内容被篡改（同大小+还原mtime）：L1 误判为未变（局限演示）。 */
    @Test
    void tamperWithSameAttrsIsNotDetected() throws Exception {
        var f = tempDir.resolve("tamper.txt");
        Files.writeString(f, "original", StandardCharsets.UTF_8);
        long t = 4_000_000_000L;
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(t));
        var digest = new TwoLevelDigest();

        var first = digest.resolve(f, "SHA-256");

        // 同长度不同内容 + 还原 mtime：绕过 L1 哨兵
        Files.writeString(f, "TAMPERE!", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(t));

        var second = digest.resolve(f, "SHA-256");
        assertTrue(second.fromCache());                       // 被误判为未变
        assertEquals(first.contentHash(), second.contentHash()); // 返回了旧哈希
        assertEquals(1, digest.contentReads());               // 没读盘 -> 篡改未被发现
    }

    /** 不同文件互不影响：缓存按路径隔离。 */
    @Test
    void cacheIsolatedPerPath() throws Exception {
        var a = tempDir.resolve("a.txt");
        var b = tempDir.resolve("b.txt");
        Files.writeString(a, "aaa", StandardCharsets.UTF_8);
        Files.writeString(b, "bbb", StandardCharsets.UTF_8);
        var digest = new TwoLevelDigest();

        digest.resolve(a, "SHA-256");
        digest.resolve(b, "SHA-256");
        digest.resolve(a, "SHA-256"); // a 命中
        digest.resolve(b, "SHA-256"); // b 命中

        assertEquals(2, digest.contentReads()); // 每个文件只读一次
    }

    /** 真实 84MB PDF：多次解析只读一次盘。 */
    @Test
    void realPdfResolvesOnce() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        var digest = new TwoLevelDigest();

        var r1 = digest.resolve(pdf, "SHA-256");
        assertFalse(r1.fromCache());
        assertEquals(StreamingDigest.digest(pdf, "SHA-256"), r1.contentHash());

        var r2 = digest.resolve(pdf, "SHA-256");
        assertTrue(r2.fromCache());
        assertEquals(1, digest.contentReads()); // 84MB 只读了一次
    }
}
