/*
 * 方案 2：大文件 / 流式
 *
 * 原理：开启文件输入流，用固定大小的字节缓冲区循环读取，
 *       每读一段就调用 MessageDigest.update(buf, 0, len) 累加，
 *       读完再 digest() 终结计算。全程只占一个固定大小缓冲区。
 *
 * 适用：GB 级大文件。内存消耗恒定，不会 OOM。
 * 对比：方案 1 整文件进内存，方案 2 是它的流式版本。
 */
package com.example.filedigest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class StreamingDigest {

    /** 默认读缓冲大小（字节）。 */
    public static final int DEFAULT_BUFFER_SIZE = 8 * 1024;

    private StreamingDigest() {
    }

    /**
     * 流式计算指定算法的哈希。
     *
     * @param path      文件路径
     * @param algorithm 摘要算法，如 "SHA-256"、"MD5"、"SHA-1"
     * @return 小写十六进制哈希字符串
     */
    public static String digest(Path path, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        return digest(path, algorithm, DEFAULT_BUFFER_SIZE);
    }

    /**
     * 流式计算，可指定读缓冲大小（体现缓冲区大小不影响结果、只影响性能）。
     *
     * @param path       文件路径
     * @param algorithm  摘要算法
     * @param bufferSize 读缓冲区字节数，须 &gt; 0
     * @return 小写十六进制哈希字符串
     */
    public static String digest(Path path, String algorithm, int bufferSize)
            throws IOException, NoSuchAlgorithmException {
        var md = MessageDigest.getInstance(algorithm);
        var buf = new byte[bufferSize];

        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int len;
            while ((len = in.read(buf)) != -1) {
                md.update(buf, 0, len);   // 只更新已读到的长度，避免读到脏字节
            }
        }

        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * 便捷入口：路径写字符串。
     */
    public static String digest(String path, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        return digest(Path.of(path), algorithm);
    }
}
