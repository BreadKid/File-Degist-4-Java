/*
 * 方案 1：小文件 / 内存可放
 *
 * 原理：将文件内容一次性读入字节数组，交给 MessageDigest.digest(byte[])
 *       一次性计算哈希。
 *
 * 适用：≤ 几十 MB 的小文件、配置文件、小型文本。
 * 局限：整文件进内存，大文件 / 高并发下易 OOM —— 大文件请用流式(方案 2)。
 */
package com.example.filedigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SmallFileDigest {

    private SmallFileDigest() {
    }

    /**
     * 一次性读入内存并计算指定算法的哈希。
     *
     * @param path      文件路径
     * @param algorithm 摘要算法，如 "SHA-256"、"MD5"、"SHA-1"
     * @return 小写十六进制哈希字符串
     */
    public static String digest(Path path, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        var bytes = Files.readAllBytes(path);          // var：JDK 10+
        var md = MessageDigest.getInstance(algorithm); // 类型安全推断
        var hash = md.digest(bytes);
        return HexFormat.of().formatHex(hash);         // JDK 17+
    }

    /**
     * 便捷入口：路径写字符串，内部转 Path.of()。
     */
    public static String digest(String path, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        return digest(Path.of(path), algorithm);       // Path.of：JDK 11+
    }
}
