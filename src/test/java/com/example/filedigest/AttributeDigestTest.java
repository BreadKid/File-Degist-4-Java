package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttributeDigestTest {

    @TempDir
    Path tempDir;

    /** 同名同大小同 mtime -> 相同指纹（确定性）。 */
    @Test
    void deterministic() throws Exception {
        // 两个不同目录下的同名文件（文件名参与指纹，需同名才能一致）
        var dir1 = Files.createDirectory(tempDir.resolve("d1"));
        var dir2 = Files.createDirectory(tempDir.resolve("d2"));
        var a = dir1.resolve("data.txt");
        var b = dir2.resolve("data.txt");
        Files.writeString(a, "x", StandardCharsets.UTF_8);
        Files.writeString(b, "x", StandardCharsets.UTF_8);

        // 强制相同 mtime
        long t = 1_000_000_000L;
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(t));
        Files.setLastModifiedTime(b, java.nio.file.attribute.FileTime.fromMillis(t));

        assertEquals(AttributeDigest.digest(a, "SHA-256"),
                AttributeDigest.digest(b, "SHA-256"));
    }

    /** 文件名不同 -> 指纹不同（文件名是指纹的一部分）。 */
    @Test
    void fileNameChangeChangesDigest() throws Exception {
        var dir = tempDir.resolve("fn");
        Files.createDirectories(dir);
        var a = dir.resolve("nameA.txt");
        var b = dir.resolve("nameB.txt");
        Files.writeString(a, "same", StandardCharsets.UTF_8);
        Files.writeString(b, "same", StandardCharsets.UTF_8);
        long t = 9_000_000_000L;
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.fromMillis(t));
        Files.setLastModifiedTime(b, java.nio.file.attribute.FileTime.fromMillis(t));

        assertNotEquals(AttributeDigest.digest(a, "SHA-256"),
                AttributeDigest.digest(b, "SHA-256"));
    }

    /** 内容变了但大小、mtime、文件名都没变 -> 指纹不变（这正是属性指纹的局限）。 */
    @Test
    void sameSizeSameMtimeIgnoredContentChange() throws Exception {
        var f = tempDir.resolve("f.txt");
        Files.writeString(f, "hello", StandardCharsets.UTF_8);
        long t = 2_000_000_000L;
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(t));
        var before = AttributeDigest.digest(f, "SHA-256");

        // 同长度不同内容，且强制还原 mtime
        Files.writeString(f, "HELLO", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(t));

        assertEquals(before, AttributeDigest.digest(f, "SHA-256"));
    }

    /** 大小变化 -> 指纹变化。 */
    @Test
    void sizeChangeChangesDigest() throws Exception {
        var f = tempDir.resolve("s.txt");
        Files.writeString(f, "small", StandardCharsets.UTF_8);
        long t = 3_000_000_000L;
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(t));
        var before = AttributeDigest.digest(f, "SHA-256");

        Files.writeString(f, "a much longer content", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(t));
        assertNotEquals(before, AttributeDigest.digest(f, "SHA-256"));
    }

    /** mtime 变化 -> 指纹变化。 */
    @Test
    void mtimeChangeChangesDigest() throws Exception {
        var f = tempDir.resolve("m.txt");
        Files.writeString(f, "same content", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(1_000L));
        var before = AttributeDigest.digest(f, "SHA-256");

        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(2_000L));
        assertNotEquals(before, AttributeDigest.digest(f, "SHA-256"));
    }

    /** 与"手工读属性再哈希"交叉验证：确认指纹确实来自这些元信息字段。 */
    @Test
    void crossCheckManualAttrs() throws Exception {
        var f = tempDir.resolve("c.txt");
        Files.writeString(f, "cross check", StandardCharsets.UTF_8);

        var attrs = AttributeDigest.attrs(f);
        var md = java.security.MessageDigest.getInstance("SHA-256");
        var canonical = attrs.fileName() + ":" + attrs.size() + ":" + attrs.lastModifiedMillis();
        md.update(canonical.getBytes(StandardCharsets.UTF_8));
        var expected = java.util.HexFormat.of().formatHex(md.digest());

        assertEquals(expected, AttributeDigest.digest(f, "SHA-256"));
    }

    /** 不存在的文件抛 IOException。 */
    @Test
    void nonexistentThrows() {
        assertThrows(IOException.class,
                () -> AttributeDigest.digest(tempDir.resolve("nope.txt"), "SHA-256"));
    }

    /** 真实 84MB PDF：属性指纹可复现，且与内容 SHA-256 不同（体现"不读内容"）。 */
    @Test
    void realPdfAttributeVsContent() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return;
        }
        var attrDigest = AttributeDigest.digest(pdf, "SHA-256");
        var contentDigest = StreamingDigest.digest(pdf, "SHA-256");
        assertEquals(attrDigest, AttributeDigest.digest(pdf, "SHA-256"));
        assertNotEquals(attrDigest, contentDigest);
    }
}
