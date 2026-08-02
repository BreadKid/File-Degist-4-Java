/*
 * 方案 8：文件属性指纹（轻量，极速）
 *
 * 原理：完全不读文件内容，只提取元信息——文件名、大小(Byte)、最后修改时间
 *       (mtime, 毫秒时间戳)——拼接后哈希。耗时接近 0ms，开销极小。
 *
 * 适用：快速前置校验哨兵。先看元信息是否变化，变了才升级去算完整内容哈希
 *       (方案 1/2)。避免每次同步/上传都对全量文件读盘。
 *
 * 局限：不防篡改。攻击者可修改内容并还原 mtime+大小，指纹不变。
 *       元信息相同的不同内容文件会碰撞 -> 只作"哨兵"，不可作最终校验。
 */
package com.example.filedigest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AttributeDigest {

    private AttributeDigest() {
    }

    /** 指纹输入：文件名 + 大小 + 最后修改时间(millis)。 */
    public record Attrs(String fileName, long size, long lastModifiedMillis) {
    }

    /**
     * 计算文件属性指纹（不读内容）。
     *
     * @param path      文件路径
     * @param algorithm 哈希算法，如 "SHA-256"、"MD5"
     * @return 属性指纹十六进制
     */
    public static String digest(Path path, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        var attrs = attrs(path);
        return hash(attrs, algorithm);
    }

    /**
     * 只读文件属性，不计算哈希（供调用方复用/查看）。
     */
    public static Attrs attrs(Path path) throws IOException {
        var a = Files.readAttributes(path, BasicFileAttributes.class);
        return new Attrs(
                path.getFileName().toString(),
                a.size(),
                a.lastModifiedTime().toMillis());
    }

    /** 将属性拼接后哈希。 */
    private static String hash(Attrs a, String algorithm) throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance(algorithm);
        // "文件名:大小:mtimeMillis" 用 UTF-8 字节输入，保证确定性
        var canonical = a.fileName() + ":" + a.size() + ":" + a.lastModifiedMillis();
        md.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest());
    }
}
