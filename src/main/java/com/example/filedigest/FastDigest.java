/*
 * 方案 5：快速指纹（非加密哈希，仅去重）
 *
 * 原理：弃用 SHA/MD 系列安全哈希，改用 CRC32 / CRC32C 等纯数学快速算法，
 *       以吞吐优先。适用缓存 key、短时间重复检测、去重前置快筛。
 *
 * 与安全哈希的区别：
 *   - 速度快一个量级，CPU 占用低
 *   - 不具加密抗碰撞性：CRC32 仅 32 位，文件量大时有碰撞误判风险
 *   - 企业去重若用快速指纹做 key，命中后须用 SHA-256 二次确认（见两级指纹编排）
 *
 * JDK 内置两种 CRC：
 *   - CRC32  ：经典多项式 0x04C11DB7，兼容旧系统
 *   - CRC32C ：Castagnoli 多项式 0x1EDC6F41，有硬件(SSE4.2/ARM)加速，更快
 */
package com.example.filedigest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;

public final class FastDigest {

    private FastDigest() {
    }

    /** 用 CRC32 计算（流式，内存恒定）。 */
    public static long crc32(Path path) throws IOException {
        var crc = new CRC32();
        var buf = new byte[8192];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int len;
            while ((len = in.read(buf)) != -1) {
                crc.update(buf, 0, len);
            }
        }
        return crc.getValue();
    }

    /** 用 CRC32C 计算（可能走硬件加速，通常最快）。 */
    public static long crc32c(Path path) throws IOException {
        var crc = new CRC32C();
        var buf = new byte[8192];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int len;
            while ((len = in.read(buf)) != -1) {
                crc.update(buf, 0, len);
            }
        }
        return crc.getValue();
    }

    /** 16 进制形式（小写）。CRC32 为 8 字符。 */
    public static String crc32Hex(Path path) throws IOException {
        return String.format("%08x", crc32(path));
    }

    /** 16 进制形式（小写）。 */
    public static String crc32cHex(Path path) throws IOException {
        return String.format("%08x", crc32c(path));
    }
}
