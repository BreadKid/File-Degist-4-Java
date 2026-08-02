/*
 * 性能基准（自建，非 JMH）
 *
 * 对比方案 1(内存)/2(流式)/3(分块+虚拟线程) 对同一大文件的吞吐与耗时。
 * 采用 warmup + 多轮迭代取中位数，降低 JIT / GC 干扰。
 *
 * 运行：
 *   ./gradlew benchmark --args="resources/testdata/bigFile84MB.pdf"
 */
package com.example.filedigest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Benchmark {

    private static final int WARMUP = 3;
    private static final int ROUNDS = 7;

    private Benchmark() {
    }

    public static void main(String[] args) throws Exception {
        var path = Path.of(args.length > 0 ? args[0] : "resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            System.exit(1);
        }
        long size = Files.size(path);
        System.out.printf("=== Benchmark: %s (%.1f MB) ===%n%n",
                path.getFileName(), size / 1024.0 / 1024.0);

        // warmup：让 JIT 预热各方案路径
        for (int i = 0; i < WARMUP; i++) {
            SmallFileDigest.digest(path, "SHA-256");
            StreamingDigest.digest(path, "SHA-256");
            FixedChunkDigest.digest(path);
        }

        bench("方案1 内存(整文件)", () -> SmallFileDigest.digest(path, "SHA-256"), size);
        bench("方案2 流式(8KB)", () -> StreamingDigest.digest(path, "SHA-256"), size);
        bench("方案3 分块4MB+虚拟线程", () -> FixedChunkDigest.digest(path), size);
        bench("方案3 分块1MB+虚拟线程", () -> FixedChunkDigest.digest(path, 1024 * 1024, "SHA-256"), size);
        bench("方案3 分块64KB+虚拟线程", () -> FixedChunkDigest.digest(path, 64 * 1024, "SHA-256"), size);
        bench("方案5 CRC32", () -> FastDigest.crc32(path), size);
        bench("方案5 CRC32C", () -> FastDigest.crc32c(path), size);
        // 单次 I/O 同时算 SHA-256+MD5，对比"读两遍"分算
        bench("方案6 组合 SHA-256+MD5(单次I/O)", () -> MultiAlgoDigest.digestSha256AndMd5(path), size);
        bench("方案6 对比: 分开算 SHA-256+MD5", () -> {
            StreamingDigest.digest(path, "SHA-256");
            StreamingDigest.digest(path, "MD5");
        }, size);
        // 方案8 只读元信息，不读内容，应极快（~微秒级）
        bench("方案8 属性指纹(不读内容)", () -> AttributeDigest.digest(path, "SHA-256"), size);
        // 方案9 CDC：内存版 vs 流式版
        bench("方案9 CDC(内存版)", () -> CdcDigest.digest(path), size);
        bench("方案9 CDC(流式版)", () -> CdcDigest.digestStreaming(path,
                CdcDigest.DEFAULT_MIN, CdcDigest.DEFAULT_AVG, CdcDigest.DEFAULT_MAX,
                CdcDigest.DEFAULT_ALGORITHM), size);
    }

    private static void bench(String name, ThrowingRunnable task, long bytes) throws Exception {
        var times = new ArrayList<Long>(ROUNDS);
        for (int i = 0; i < ROUNDS; i++) {
            long start = System.nanoTime();
            task.run();
            times.add((System.nanoTime() - start) / 1_000_000);
        }
        times.sort(Comparator.naturalOrder());
        long median = times.get(times.size() / 2);
        double mbps = bytes / 1024.0 / 1024.0 / (median / 1000.0);
        System.out.printf("%-28s 中位数 %6d ms  吞吐 %8.1f MB/s%n", name, median, mbps);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
