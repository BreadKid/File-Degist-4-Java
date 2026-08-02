/*
 * 方案 9：CDC 内容定义分块 (Content-Defined Chunking) —— FastCDC 风格
 *
 * 原理：与固定分块(方案3)不同，CDC 用"滚动哈希"(Gear Hash)扫描字节流，
 *       由内容特征决定块边界，切出"变长块"。因此文件任意位置插入/删除字节
 *       只影响附近的少数块，其余块边界自动重新对齐、哈希可复用——
 *       去重率稳定，不受"边界漂移(boundary shift)"影响。
 *
 * FastCDC 要点：
 *   - Gear 滚动哈希：fp = (fp << 1) + gear_table[b]，每字节 O(1) 更新
 *   - 最小/最大块约束：min 之前不切、max 强制切，保证块长可控
 *   - 规范化(normalization)：位置越靠前掩码越宽(易切、块短)，越靠后掩码收窄
 *     (难切、块长)，使块大小分布集中在平均块附近
 *
 * 对比：
 *   - 方案3 固定分块：边界固定、算得快，但插入1字节后全文件块边界错位、
 *     去重率崩塌
 *   - 本方案 CDC：抗边界漂移、去重率稳定，代价是滚动哈希额外开销
 *
 * 注：为演示清晰，此处将文件读入内存扫描；企业级可改流式扫描以支持超大文件。
 */
package com.example.filedigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

public final class CdcDigest {

    /** 默认最小块大小。 */
    public static final int DEFAULT_MIN = 4 * 1024;
    /** 默认平均块大小（2 的幂，规范化掩码基准）。 */
    public static final int DEFAULT_AVG = 16 * 1024;
    /** 默认最大块大小（兜底强制切块）。 */
    public static final int DEFAULT_MAX = 64 * 1024;
    /** 默认算法。 */
    public static final String DEFAULT_ALGORITHM = "SHA-256";

    /**
     * 流式 CDC 分块：边读边扫描，内存只占"最大块大小"的缓冲，
     * 可处理超出内存的超大文件。产生与内存版(digest)完全相同的块边界。
     *
     * @param path      文件路径
     * @param minSize   最小块大小(字节)
     * @param avgSize   平均块大小(字节，最好为 2 的幂)
     * @param maxSize   最大块大小(字节)
     * @param algorithm 块哈希算法
     */
    public static Result digestStreaming(Path path, int minSize, int avgSize, int maxSize,
                                         String algorithm)
            throws IOException, NoSuchAlgorithmException {
        validate(minSize, avgSize, maxSize, algorithm);

        var chunkHashes = new ArrayList<String>();
        var buf = new byte[maxSize];
        var md = MessageDigest.getInstance(algorithm);
        long fp = 0;
        int chunkLen = 0;   // 当前块已累积字节数
        boolean any = false;

        try (var in = new java.io.BufferedInputStream(Files.newInputStream(path))) {
            int b;
            while ((b = in.read()) != -1) {
                any = true;
                buf[chunkLen] = (byte) b;
                chunkLen++;
                fp = (fp << 1) + GEAR[b & 0xFF];

                boolean cut = false;
                if (chunkLen >= minSize && (fp & cutMask(chunkLen, avgSize)) == 0) {
                    cut = true;         // 内容定义边界
                } else if (chunkLen >= maxSize) {
                    cut = true;         // 最大块兜底强制切
                }
                if (cut) {
                    md.update(buf, 0, chunkLen);
                    chunkHashes.add(HexFormat.of().formatHex(md.digest()));
                    md.reset();
                    fp = 0;
                    chunkLen = 0;
                }
            }
        }

        // 收尾：EOF 时残留的不足一块（含空文件 -> 1 块空串哈希，与内存版一致）
        if (chunkLen > 0 || !any) {
            md.update(buf, 0, chunkLen);
            chunkHashes.add(HexFormat.of().formatHex(md.digest()));
        }

        return new Result(List.copyOf(chunkHashes), finalizeDigest(chunkHashes, algorithm));
    }

    /** 256 项 Gear 哈希表，固定种子生成保证确定性。 */
    private static final long[] GEAR = makeGearTable();

    private CdcDigest() {
    }

    /** CDC 结果：每块哈希(按文件顺序) + 二次哈希的文件指纹。 */
    public record Result(List<String> chunkHashes, String fileDigest) {
    }

    /**
     * 默认参数 CDC 分块指纹。
     */
    public static Result digest(Path path) throws IOException, NoSuchAlgorithmException {
        return digest(path, DEFAULT_MIN, DEFAULT_AVG, DEFAULT_MAX, DEFAULT_ALGORITHM);
    }

    /**
     * FastCDC 分块指纹。
     *
     * @param path      文件路径
     * @param minSize   最小块大小(字节)
     * @param avgSize   平均块大小(字节，最好为 2 的幂)
     * @param maxSize   最大块大小(字节)
     * @param algorithm 块哈希算法
     */
    public static Result digest(Path path, int minSize, int avgSize, int maxSize, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        validate(minSize, avgSize, maxSize, algorithm);
        var data = Files.readAllBytes(path);

        var chunkHashes = new ArrayList<String>();
        if (data.length == 0) {
            // 空文件产生 1 块：空串 SHA-256（与方案 3 一致）
            var md = MessageDigest.getInstance(algorithm);
            chunkHashes.add(HexFormat.of().formatHex(md.digest()));
        } else {
            for (var range : scanChunks(data, minSize, avgSize, maxSize)) {
                var md = MessageDigest.getInstance(algorithm);
                md.update(data, range[0], range[1] - range[0] + 1);
                chunkHashes.add(HexFormat.of().formatHex(md.digest()));
            }
        }

        return new Result(List.copyOf(chunkHashes), finalizeDigest(chunkHashes, algorithm));
    }

    /**
     * 返回每个块的字节长度（供分析块大小分布/测试用）。
     */
    public static List<Integer> chunkSizes(Path path, int minSize, int avgSize, int maxSize)
            throws IOException {
        validate(minSize, avgSize, maxSize, "SHA-256");
        var data = Files.readAllBytes(path);
        var sizes = new ArrayList<Integer>();
        for (var range : scanChunks(data, minSize, avgSize, maxSize)) {
            sizes.add(range[1] - range[0] + 1);
        }
        return sizes;
    }

    // ---- 核心扫描 ----

    /** 顺序扫描字节流，返回所有块边界 [start, end]。 */
    private static List<int[]> scanChunks(byte[] data, int min, int avg, int max) {
        var chunks = new ArrayList<int[]>();
        int n = data.length;
        int start = 0;

        while (start < n) {
            long fp = 0;
            int cut = n - 1;
            for (int i = start; i < n; i++) {
                int len = i - start + 1;
                fp = (fp << 1) + GEAR[data[i] & 0xFF];
                if (len >= min) {
                    long mask = cutMask(len, avg);
                    if ((fp & mask) == 0) {
                        cut = i;
                        break;
                    }
                }
                if (len >= max) {
                    cut = i;   // 强制切块，兜底
                    break;
                }
            }
            chunks.add(new int[]{start, cut});
            start = cut + 1;
        }
        return chunks;
    }

    /** 规范化掩码：位置越靠前掩码越宽(易切/块短)，后期收窄(块长趋近 avg)。 */
    private static long cutMask(int len, int avg) {
        if (len < avg / 8) return (avg >> 3) - 1;
        if (len < avg / 4) return (avg >> 2) - 1;
        if (len < avg / 2) return (avg >> 1) - 1;
        return avg - 1;
    }

    /** 把块哈希列表按顺序拼接后二次哈希，得文件指纹。 */
    private static String finalizeDigest(List<String> chunkHashes, String algorithm)
            throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance(algorithm);
        for (var h : chunkHashes) {
            md.update(HexFormat.of().parseHex(h));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void validate(int min, int avg, int max, String algorithm) {
        if (min <= 0 || avg < min || max < avg) {
            throw new IllegalArgumentException(
                    "require 0 < min <= avg <= max, got min=" + min + " avg=" + avg + " max=" + max);
        }
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be null/blank");
        }
    }

    /** 生成确定性的 Gear 表（固定种子），保证跨运行/跨机可复现。 */
    private static long[] makeGearTable() {
        var rnd = new Random(0xFEEDC0DEL);
        var t = new long[256];
        for (int i = 0; i < 256; i++) {
            long v = rnd.nextLong();
            if (v == 0) {
                v = 1;
            }
            t[i] = v;
        }
        return t;
    }
}
