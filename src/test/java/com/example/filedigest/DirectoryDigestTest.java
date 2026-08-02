package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectoryDigestTest {

    @TempDir
    Path tempDir;

    /** 空目录：指纹确定且不崩溃。 */
    @Test
    void emptyDirectory() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("empty"));

        var r1 = DirectoryDigest.digest(dir);
        var r2 = DirectoryDigest.digest(dir);
        assertEquals(r1.directoryDigest(), r2.directoryDigest());
        assertEquals(0, r1.entries().size());
    }

    /** 目录顺序无关性：同样的文件集，扫描顺序不同 -> 指纹相同。 */
    @Test
    void orderIndependence() throws Exception {
        var a = Files.createDirectory(tempDir.resolve("a"));
        var b = Files.createDirectory(tempDir.resolve("b"));
        Files.writeString(a.resolve("f1.txt"), "one", StandardCharsets.UTF_8);
        Files.writeString(a.resolve("f2.txt"), "two", StandardCharsets.UTF_8);
        Files.writeString(b.resolve("f2.txt"), "two", StandardCharsets.UTF_8);
        Files.writeString(b.resolve("f1.txt"), "one", StandardCharsets.UTF_8);

        assertEquals(DirectoryDigest.digest(a).directoryDigest(),
                DirectoryDigest.digest(b).directoryDigest());
    }

    /** 内容变化 -> 指纹变化。 */
    @Test
    void contentChangeChangesDigest() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("d"));
        var f = dir.resolve("f.txt");
        Files.writeString(f, "v1", StandardCharsets.UTF_8);
        var before = DirectoryDigest.digest(dir).directoryDigest();

        Files.writeString(f, "v2", StandardCharsets.UTF_8);
        var after = DirectoryDigest.digest(dir).directoryDigest();

        assertNotEquals(before, after);
    }

    /** 新增/删除文件 -> 指纹变化。 */
    @Test
    void fileAddRemoveChangesDigest() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("e"));
        Files.writeString(dir.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        var base = DirectoryDigest.digest(dir).directoryDigest();

        Files.writeString(dir.resolve("b.txt"), "b", StandardCharsets.UTF_8);
        assertNotEquals(base, DirectoryDigest.digest(dir).directoryDigest());

        Files.delete(dir.resolve("b.txt"));
        assertEquals(base, DirectoryDigest.digest(dir).directoryDigest());
    }

    /** 递归：子目录中的文件参与指纹，且相对路径含子目录名。 */
    @Test
    void recursesIntoSubdirectories() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("r"));
        Files.createDirectories(dir.resolve("sub/deep"));
        Files.writeString(dir.resolve("top.txt"), "top", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("sub/deep/leaf.bin"), "leaf", StandardCharsets.UTF_8);

        var result = DirectoryDigest.digest(dir);
        var paths = result.entries().stream().map(e -> e.relativePath()).toList();
        assertEquals(2, paths.size());
        assertEquals("sub/deep/leaf.bin", paths.get(0)); // 字典序 's' < 't'
        assertEquals("top.txt", paths.get(1));
    }

    /** 每个条目的 hash 必须是该文件独立算的 SHA-256。 */
    @Test
    void entryHashesCorrect() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("h"));
        var f = dir.resolve("data.txt");
        Files.writeString(f, "entry hash check", StandardCharsets.UTF_8);

        var result = DirectoryDigest.digest(dir);
        assertEquals(1, result.entries().size());
        assertEquals(StreamingDigest.digest(f, "SHA-256"), result.entries().get(0).hash());
    }

    /** 相对路径使用 POSIX 分隔符归一化（跨平台一致性，即使在本机也验证逻辑）。 */
    @Test
    void pathsUseForwardSlash() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("s"));
        Files.writeString(dir.resolve("file.txt"), "x", StandardCharsets.UTF_8);

        var entry = DirectoryDigest.digest(dir).entries().get(0);
        // 不依赖 OS，直接断言不含反斜杠、含相对名
        assertEquals("file.txt", entry.relativePath());
        assertEquals(false, entry.relativePath().contains("\\"));
    }

    /** 非目录输入应被拒绝。 */
    @Test
    void nonDirectoryRejected() throws Exception {
        var f = tempDir.resolve("file.txt");
        Files.writeString(f, "not a dir", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> DirectoryDigest.digest(f));
    }

    /** 目录为空时，二次哈希输入为空 -> 指纹是空串的 SHA-256（可复现）。 */
    @Test
    void emptyDirFinalDigestIsKnown() throws Exception {
        var dir = Files.createDirectory(tempDir.resolve("k"));
        // 空条目列表 -> MessageDigest.digest() 对空输入
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                DirectoryDigest.digest(dir).directoryDigest());
    }
}
