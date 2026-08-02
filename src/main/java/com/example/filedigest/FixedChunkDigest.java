/*
 * 方案 3：大文件 + 固定分块指纹
 *
 * 原理：把文件按固定块大小(如 4MB)切成若干块，每块独立计算一个哈希；
 *       收集所有块的哈希，按文件内顺序拼接后二次哈希，得到最终文件指纹。
 *       （每块哈希本身即"内容寻址"的 key，可支持秒传 / 断点续传。）
 *
 * 亮点(JDK 21+)：用虚拟线程 Thread.ofVirtual() + ExecutorService
 *       newVirtualThreadPerTaskExecutor() 并行计算各块哈希，吞吐高、
 *       开销小。合并结果严格按块顺序，保证指纹确定性。
 *
 * 局限：固定边界有"边界漂移(boundary shift)"问题——文件开头插入/删除
 *       一个字节会让后续所有块边界错位、哈希全变。追求去重率请用方案 9(CDC)。
 */
package com.example.filedigest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class FixedChunkDigest {

    /** 默认分块大小：4MB。 */
    public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;

    /** 默认算法。 */
    public static final String DEFAULT_ALGORITHM = "SHA-256";

    private FixedChunkDigest() {
    }

    /**
     * 分块指纹结果：每块哈希 + 最终指纹。
     *
     * @param chunkHashes 按文件顺序的每块哈希（十六进制小写）
     * @param fileDigest  对块哈希列表二次哈希得到的最终文件指纹
     */
    public record Result(List<String> chunkHashes, String fileDigest) {
    }

    /**
     * 固定分块 + 虚拟线程并行计算文件指纹。
     *
     * @param path      文件路径
     * @param chunkSize 分块大小(字节)，须 &gt; 0
     * @param algorithm 哈希算法
     */
    public static Result digest(Path path, int chunkSize, String algorithm)
            throws IOException, NoSuchAlgorithmException, InterruptedException,
            ExecutionException {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0, got " + chunkSize);
        }
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be null/blank");
        }

        long fileSize;
        try (var ch = FileChannel.open(path)) {
            fileSize = ch.size();
        }

        int numChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
        if (numChunks == 0) {
            numChunks = 1; // 空文件也产生 1 块
        }

        // 每个虚拟线程负责读+哈希一个块，结果按块序号收集
        var results = new String[numChunks];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new Future<?>[numChunks];
            for (int i = 0; i < numChunks; i++) {
                final int idx = i;
                futures[i] = executor.submit(() -> results[idx] = hashChunk(path, idx, chunkSize, algorithm));
            }
            for (var f : futures) {
                f.get(); // 等所有块算完（虚拟线程数量=块数，无池化阻塞）
            }
        }

        var chunkHashes = List.of(results);
        return new Result(chunkHashes, hashChunkList(chunkHashes, algorithm));
    }

    /** 便捷入口：默认 4MB 分块 + SHA-256。 */
    public static Result digest(Path path)
            throws IOException, NoSuchAlgorithmException, InterruptedException, ExecutionException {
        return digest(path, DEFAULT_CHUNK_SIZE, DEFAULT_ALGORITHM);
    }

    /**
     * 读取并哈希单个块。
     * 用 FileChannel 按 position 精确定位，每个虚拟线程只读自己的区间。
     */
    private static String hashChunk(Path path, int index, int chunkSize, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        var md = MessageDigest.getInstance(algorithm);
        long start = (long) index * chunkSize;

        try (var ch = FileChannel.open(path)) {
            long remaining = ch.size() - start;
            long toRead = Math.min(chunkSize, remaining);
            var buf = ByteBuffer.allocate((int) toRead);
            ch.position(start);
            while (buf.hasRemaining()) {
                int n = ch.read(buf);
                if (n < 0) break;
            }
            buf.flip();
            md.update(buf);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** 把块哈希列表按顺序拼接后二次哈希，得到最终文件指纹。 */
    private static String hashChunkList(List<String> chunkHashes, String algorithm)
            throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance(algorithm);
        for (var h : chunkHashes) {
            md.update(HexFormat.of().parseHex(h)); // 用块哈希的原始字节，而非 ASCII
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
