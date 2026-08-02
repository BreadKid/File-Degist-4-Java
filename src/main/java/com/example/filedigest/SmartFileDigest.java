/*
 * 智能分派：SmartFileDigest —— 内存阈值自动降级
 *
 * 背景：方案 1(内存) 简单快但整文件进堆，大文件/高并发易 OOM；
 *       方案 2(流式) 内存恒定但需逐块 update。二者结果完全相同。
 *
 * 思路：按文件大小自动选择实现——
 *   - size <= 阈值    -> 方案 1 整文件内存（快）
 *   - size >  阈值    -> 方案 2 流式（防 OOM）
 *
 * 调用方无需关心文件多大，SmartFileDigest 保证内存可控且结果与单一实现一致。
 * 这是企业处理"文件大小未知"场景的标准兜底策略。
 */
package com.example.filedigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;

public final class SmartFileDigest {

    /** 默认内存阈值：64MB。超过则走流式防 OOM。 */
    public static final long DEFAULT_THRESHOLD_BYTES = 64L * 1024 * 1024;

    private SmartFileDigest() {
    }

    /**
     * 按文件大小自动选内存版或流式版，返回相同的哈希。
     *
     * @param path      文件路径
     * @param algorithm 哈希算法
     * @return 与方案 1/2 一致的哈希
     */
    public static String digest(Path path, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        return digest(path, algorithm, DEFAULT_THRESHOLD_BYTES);
    }

    /**
     * 按文件大小自动选实现，可指定阈值。
     *
     * @param path      文件路径
     * @param algorithm 哈希算法
     * @param threshold 超过该字节数(含)走流式；&lt;= 0 表示总是走内存版
     * @return 与方案 1/2 一致的哈希
     */
    public static String digest(Path path, String algorithm, long threshold)
            throws IOException, NoSuchAlgorithmException {
        long size = Files.size(path);

        if (threshold > 0 && size > threshold) {
            // 大文件：流式，内存恒定
            return StreamingDigest.digest(path, algorithm);
        }
        // 小文件：内存版
        return SmallFileDigest.digest(path, algorithm);
    }

    /**
     * 便捷：默认 SHA-256。
     */
    public static String digest(Path path) throws IOException, NoSuchAlgorithmException {
        return digest(path, "SHA-256");
    }
}
