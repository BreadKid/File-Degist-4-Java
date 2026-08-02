/*
 * 统一入口：FileDigest
 *
 * 用法：
 *   ./gradlew run --args="<path>"
 *   ./gradlew run --args="<path> <algorithm>"
 *
 * 对文件：跑方案 1/2/5/6/8，并演示"两级指纹编排"(方案8哨兵 -> 内容哈希)。
 *         方案 3/4/9(分块类) 也输出，展示其块级指纹。
 * 对目录：跑方案 7 目录指纹。
 */
package com.example.filedigest;

import java.nio.file.Files;
import java.nio.file.Path;

public final class FileDigest {

    private FileDigest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: ./gradlew run --args=\"<path> [algorithm]\"");
            System.err.println("  <path>      文件或目录");
            System.err.println("  [algorithm] 可选，默认 SHA-256");
            System.exit(1);
        }
        var path = Path.of(args[0]);
        var algo = args.length > 1 ? args[1] : "SHA-256";

        if (!Files.exists(path)) {
            System.err.println("路径不存在: " + path);
            System.exit(1);
        }

        System.out.println("=== 目标: " + path + " ===");
        if (Files.isDirectory(path)) {
            runDirectory(path, algo);
        } else {
            runFile(path, algo);
        }
    }

    private static void runFile(Path path, String algo) throws Exception {
        long size = Files.size(path);
        System.out.printf("文件大小: %.2f MB (%d 字节)%n%n", size / 1024.0 / 1024.0, size);

        // 方案 1 & 2：单文件完整哈希
        System.out.println("── 单文件完整哈希 ──");
        System.out.println("方案1 内存(整文件) : " + SmallFileDigest.digest(path, algo));
        System.out.println("方案2 流式         : " + StreamingDigest.digest(path, algo));

        // 两级指纹编排：方案8 属性哨兵 -> 命中才升级内容哈希（真实企业用法）
        System.out.println("\n── 两级指纹编排(哨兵 -> 精确) ──");
        var attrDigest = AttributeDigest.digest(path, algo);
        System.out.println("方案8 属性哨兵(不读内容): " + attrDigest);
        System.out.println("         ↑ 先看元信息，未变则跳过内容哈希；变了才升级到上面的方案1/2");

        // 方案 5：快速指纹
        System.out.println("\n── 快速指纹(非加密) ──");
        System.out.println("方案5 CRC32  : " + FastDigest.crc32Hex(path));
        System.out.println("方案5 CRC32C : " + FastDigest.crc32cHex(path));

        // 方案 6：单次 I/O 多算法
        System.out.println("\n── 多算法组合(单次 I/O) ──");
        var multi = MultiAlgoDigest.digest(path, "SHA-256", "MD5");
        multi.forEach((k, v) -> System.out.println("方案6 " + k + " : " + v));

        // 方案 3/4/9：分块类
        System.out.println("\n── 分块类指纹 ──");
        var chunks = FixedChunkDigest.digest(path);
        System.out.println("方案3 固定分块(4MB): " + chunks.chunkHashes().size() + " 块, 指纹 " + chunks.fileDigest());

        var merkle = MerkleTreeDigest.fromPath(path, FixedChunkDigest.DEFAULT_CHUNK_SIZE, algo);
        System.out.println("方案4 Merkle树      : " + merkle.leafCount() + " 叶子, 根指纹 " + merkle.root());

        var cdc = CdcDigest.digest(path);
        System.out.println("方案9 CDC(16KB平均) : " + cdc.chunkHashes().size() + " 块, 指纹 " + cdc.fileDigest());
    }

    private static void runDirectory(Path path, String algo) throws Exception {
        var result = DirectoryDigest.digest(path, algo);
        System.out.println("文件数: " + result.entries().size());
        System.out.println("方案7 目录指纹: " + result.directoryDigest());
        System.out.println("\n条目明细:");
        result.entries().forEach(e ->
                System.out.println("  " + e.relativePath() + "  " + e.hash()));
    }
}
