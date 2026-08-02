/*
 * 两级指纹编排（企业真实用法）
 *
 * 思想：单一算法不够，用分层组合权衡"成本 vs 准确率"。
 *
 *   L1 前置哨兵（~0ms）: 方案 8 属性指纹(size+mtime)，不读文件内容
 *   L2 精确校验（读盘）: 方案 2 流式 SHA-256，全内容哈希
 *
 * 流程（每次请求）：
 *   1. 算属性指纹（快，不读内容）
 *   2. 与缓存比对：属性指纹未变 -> 直接复用缓存的完整哈希，零 I/O
 *   3. 变了或首次 -> 升级读全内容算 SHA-256，更新缓存
 *
 * 收益：避免每次同步/上传对全量文件读盘。云盘、备份、对象存储通用。
 *
 * 局限（必须知晓）：L1 只是"哨兵"，不防篡改。若攻击者修改内容并还原
 *   mtime+size，L1 会误判为"未变"而错误复用旧哈希。因此 L1 用于成本控制，
 *   L2 才是最终信任锚点——对安全敏感场景可加"定期强制 L2"策略。
 */
package com.example.filedigest;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TwoLevelDigest {

    /** 缓存条目：上次的属性指纹 与 对应的完整内容哈希。 */
    private record CacheEntry(String attrFingerprint, String contentHash) {
    }

    /** 解析结果。 */
    public record Resolution(String contentHash, boolean fromCache, String attrFingerprint) {
    }

    private final ConcurrentHashMap<Path, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicLong contentReads = new AtomicLong();

    /**
     * 两级解析：返回文件内容哈希，尽量命中 L1 哨兵以减少读盘。
     *
     * @param path      文件路径
     * @param algorithm 哈希算法（内容哈希与属性哨兵都用它）
     * @return 解析结果，含是否命中缓存
     */
    public Resolution resolve(Path path, String algorithm)
            throws IOException, NoSuchAlgorithmException {
        var attr = AttributeDigest.digest(path, algorithm);

        var cached = cache.get(path);
        if (cached != null && cached.attrFingerprint().equals(attr)) {
            // L1 命中：属性未变，复用缓存的完整哈希，零读盘
            return new Resolution(cached.contentHash(), true, attr);
        }

        // L2 升级：属性变了或首次，读全内容算精确哈希
        var contentHash = StreamingDigest.digest(path, algorithm);
        cache.put(path, new CacheEntry(attr, contentHash));
        contentReads.incrementAndGet();
        return new Resolution(contentHash, false, attr);
    }

    /** 累计实际读盘的次数（L2 触发次数）。 */
    public long contentReads() {
        return contentReads.get();
    }

    /** 清空缓存与计数。 */
    public void reset() {
        cache.clear();
        contentReads.set(0);
    }
}
