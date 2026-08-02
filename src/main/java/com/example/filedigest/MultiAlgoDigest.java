/*
 * 方案 6：多算法组合（单次 I/O 同时计算多种哈希）
 *
 * 原理：一次读取文件流，把每段缓冲区同时 update 给多个 MessageDigest 实例。
 *       相比"分别算 SHA-256 再分别算 MD5"要读两遍流，本方案只读一遍磁盘，
 *       避免成倍 I/O 损耗，CPU 只是多份哈希并行计算。
 *
 * 适用：既要 SHA-256(新安全标准) 又要 MD5(向后兼容历史系统) 的混合场景。
 *
 * 线程安全说明：MessageDigest 实例非线程安全，此处每个实例独占一个
 *       主线程内顺序使用，不跨线程共享，安全。
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
import java.util.LinkedHashMap;
import java.util.Map;

public final class MultiAlgoDigest {

    private MultiAlgoDigest() {
    }

    /**
     * 单次读取流，同时计算多个算法的哈希。
     *
     * @param path       文件路径
     * @param algorithms 算法列表（如 {"SHA-256", "MD5"}），返回的 Map 按此顺序
     * @return 算法名 -> 小写十六进制哈希
     */
    public static Map<String, String> digest(Path path, String... algorithms)
            throws IOException, NoSuchAlgorithmException {
        if (algorithms == null || algorithms.length == 0) {
            throw new IllegalArgumentException("at least one algorithm required");
        }

        var digests = new LinkedHashMap<String, MessageDigest>();
        for (var algo : algorithms) {
            digests.put(algo, MessageDigest.getInstance(algo));
        }

        var buf = new byte[8192];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int len;
            while ((len = in.read(buf)) != -1) {
                for (var md : digests.values()) {
                    md.update(buf, 0, len);   // 同一段数据喂给每个算法
                }
            }
        }

        var result = new LinkedHashMap<String, String>();
        for (var e : digests.entrySet()) {
            result.put(e.getKey(), HexFormat.of().formatHex(e.getValue().digest()));
        }
        return result;
    }

    /** 便捷：同时算 SHA-256 和 MD5。 */
    public static Map<String, String> digestSha256AndMd5(Path path) throws IOException, NoSuchAlgorithmException {
        return digest(path, "SHA-256", "MD5");
    }
}
