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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SmallFileDigestTest {

    @TempDir
    Path tempDir;

    /** 标准已知向量：空字符串的 SHA-256。 */
    @Test
    void emptyFileSha256() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("empty.txt");
        Files.write(f, new byte[0]);

        var expected = "e3b0c44298fc1c149afbf4c8996fb924"
                + "27ae41e4649b934ca495991b7852b855";
        assertEquals(expected, SmallFileDigest.digest(f, "SHA-256"));
    }

    /** 已知向量：空字符串的 MD5 应为 d41d8cd98f00b204e9800998ecf8427e。 */
    @Test
    void emptyFileMd5() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("empty.txt");
        Files.write(f, new byte[0]);

        assertEquals("d41d8cd98f00b204e9800998ecf8427e",
                SmallFileDigest.digest(f, "MD5"));
    }

    /** 相同内容 -> 相同哈希；不同内容 -> 不同哈希。 */
    @Test
    void deterministicAndDistinct() throws IOException, NoSuchAlgorithmException {
        var a = tempDir.resolve("a.txt");
        var b = tempDir.resolve("b.txt");
        var c = tempDir.resolve("c.txt");
        Files.writeString(a, "hello world", StandardCharsets.UTF_8);
        Files.writeString(b, "hello world", StandardCharsets.UTF_8);
        Files.writeString(c, "hello world!", StandardCharsets.UTF_8);

        var ha = SmallFileDigest.digest(a, "SHA-256");
        assertEquals(ha, SmallFileDigest.digest(b, "SHA-256"));
        assertNotEquals(ha, SmallFileDigest.digest(c, "SHA-256"));
    }

    /** 输出必须是指定算法长度的十六进制串：SHA-256 为 64 字符。 */
    @Test
    void hexLengthMatchesAlgorithm() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("data.bin");
        Files.write(f, new byte[]{1, 2, 3, 4, 5});

        assertEquals(64, SmallFileDigest.digest(f, "SHA-256").length());
        assertEquals(40, SmallFileDigest.digest(f, "SHA-1").length());
        assertEquals(32, SmallFileDigest.digest(f, "MD5").length());
    }

    /** 与 Java 原生 MessageDigest 手动实现交叉验证，确保无低级实现错误。 */
    @Test
    void crossCheckWithRawMessageDigest() throws IOException, NoSuchAlgorithmException {
        var data = "the quick brown fox jumps over the lazy dog"
                .getBytes(StandardCharsets.UTF_8);
        var f = tempDir.resolve("fox.txt");
        Files.write(f, data);

        var md = java.security.MessageDigest.getInstance("SHA-256");
        var expected = HexFormat.of().formatHex(md.digest(data));

        assertEquals(expected, SmallFileDigest.digest(f, "SHA-256"));
    }

    /** 验证便捷入口 digest(String, String) 与 Path 版等价。 */
    @Test
    void stringOverloadMatchesPathOverload() throws IOException, NoSuchAlgorithmException {
        var f = tempDir.resolve("overload.txt");
        Files.writeString(f, "jdk 25 syntax check", StandardCharsets.UTF_8);

        assertEquals(SmallFileDigest.digest(f, "SHA-256"),
                SmallFileDigest.digest(f.toString(), "SHA-256"));
    }
}
