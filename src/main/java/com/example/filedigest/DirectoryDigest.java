/*
 * 方案 7：目录指纹
 *
 * 原理：递归扫描目录下所有文件，对每个文件计算内容哈希，然后
 *       将"相对路径 + 分隔符 + 哈希"按路径排序拼接，整体二次哈希，
 *       得到目录指纹。用于文件夹一致性比对、发布包校验。
 *
 * 关键工程点：
 *   - 路径归一化：Windows 用 '\'、Unix 用 '/'，直接拼接会导致同一目录
 *     在不同系统上指纹不同（经典 bug）。这里统一替换为 '/'(POSIX)。
 *   - 确定性排序：按归一化后的相对路径字典序排序，保证指纹稳定可复现。
 *   - 符号链接：默认不跟随（避免循环链接/越出目录），只记录链接本身。
 *   - 哈希方式可配置：默认流式 SHA-256（方案 2），可换快速指纹。
 *
 * 注意：目录指纹随文件内容变化而变化；若只要"结构一致性"而非内容，
 * 应改用方案 8 的属性指纹（不读文件内容）。
 */
package com.example.filedigest;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class DirectoryDigest {

    private DirectoryDigest() {
    }

    /** 每条记录：相对路径(POSIX 归一化) 与 内容哈希。 */
    public record Entry(String relativePath, String hash) {
    }

    /** 目录指纹结果：条目列表 + 最终指纹。 */
    public record Result(List<Entry> entries, String directoryDigest) {
    }

    /**
     * 计算目录指纹。默认：跟随普通文件、不跟随符号链接、流式 SHA-256。
     *
     * @param dir 目录路径
     * @return 目录指纹结果
     */
    public static Result digest(Path dir) throws IOException, NoSuchAlgorithmException {
        return digest(dir, "SHA-256");
    }

    /**
     * 计算目录指纹，可指定文件内容哈希算法。
     */
    public static Result digest(Path dir, String algorithm) throws IOException, NoSuchAlgorithmException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        // 前置校验算法名，确保非法算法在遍历前立即暴露，而非在 visitFile 内
        MessageDigest.getInstance(algorithm);

        var entries = new ArrayList<Entry>();

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE; // 跳过非普通文件(如设备)
                }
                String rel = dir.relativize(file).toString().replace('\\', '/');
                try {
                    entries.add(new Entry(rel, StreamingDigest.digest(file, algorithm)));
                } catch (NoSuchAlgorithmException e) {
                    // 已在上面前置校验，正常不会走到
                    throw new IllegalStateException("algorithm validated above", e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE; // 权限不足等跳过，不中断整个目录
            }
        });

        // 确定性：按归一化相对路径字典序排序
        entries.sort(java.util.Comparator.comparing(Entry::relativePath));

        return new Result(List.copyOf(entries), finalizeDigest(entries, algorithm));
    }

    /** 把 (path:hash) 列表按顺序拼接后二次哈希，得到目录指纹。 */
    private static String finalizeDigest(List<Entry> entries, String algorithm)
            throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance(algorithm);
        for (var e : entries) {
            // 用 ASCII 拼接 "path:hash\n"，路径和哈希都用归一化后的稳定表示
            md.update(e.relativePath().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) ':');
            md.update(HexFormat.of().parseHex(e.hash()));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
