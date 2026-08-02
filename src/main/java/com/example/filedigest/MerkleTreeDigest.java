/*
 * 方案 4：增量指纹 —— Merkle 哈希树 (Merkle Tree)
 *
 * 结构：
 *   - 叶子层(level 0)：每块一个哈希
 *   - 父节点 = 两个子节点哈希拼接后再哈希，逐层向上
 *   - 根节点(root) = 整文件指纹
 *   - 奇数节点补齐：复制最后一个，保证每层偶数（标准 Merkle 做法）
 *
 * 价值：
 *   - 增量校验：修改任意一块，只需重算"该块到根路径"上的 O(log n) 个节点，
 *     兄弟子树哈希全部复用，无需整文件重算。
 *   - 差异定位：对比两棵树，从根向下只沿"不一致"分支下降，O(log n) 找差异块。
 *   - 独立校验：任意子块可独立验证，无需持有整个文件。
 *
 * Git 对象模型、IPFS 分块寻址、对象存储 ETag 校验均采用此结构。
 */
package com.example.filedigest;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class MerkleTreeDigest {

    /** levels.get(0) = 叶子层；levels.get(levels.size()-1) = 根层(单节点)。 */
    private final List<List<String>> levels;
    private final String algorithm;

    private MerkleTreeDigest(List<List<String>> levels, String algorithm) {
        this.levels = levels;
        this.algorithm = algorithm;
    }

    /**
     * 从叶子哈希列表构建 Merkle 树。
     *
     * @param leafHashes 按文件顺序的每块哈希（十六进制小写）
     * @param algorithm  父节点拼接所用的哈希算法
     */
    public static MerkleTreeDigest build(List<String> leafHashes, String algorithm)
            throws NoSuchAlgorithmException {
        if (leafHashes == null || leafHashes.isEmpty()) {
            throw new IllegalArgumentException("leafHashes must not be empty");
        }
        // 前置校验算法名，即使单叶子(不触发父节点哈希)也立即暴露非法算法
        MessageDigest.getInstance(algorithm);

        var levels = new ArrayList<List<String>>();
        var current = new ArrayList<>(leafHashes);
        levels.add(new ArrayList<>(current)); // 深拷贝，避免后续补齐污染叶子层

        while (current.size() > 1) {
            if (current.size() % 2 != 0) {
                current.add(current.get(current.size() - 1)); // 奇数补齐
            }
            var next = new ArrayList<String>(current.size() / 2);
            for (int i = 0; i < current.size(); i += 2) {
                next.add(hashParent(current.get(i), current.get(i + 1), algorithm));
            }
            levels.add(next);
            current = next;
        }

        return new MerkleTreeDigest(levels, algorithm);
    }

    /** 便捷：直接对文件分块(方案3)构造 Merkle 树。 */
    public static MerkleTreeDigest fromPath(Path path, int chunkSize, String algorithm)
            throws Exception {
        var res = FixedChunkDigest.digest(path, chunkSize, algorithm);
        return build(res.chunkHashes(), algorithm);
    }

    /** 根指纹（整文件指纹）。 */
    public String root() {
        return levels.get(levels.size() - 1).get(0);
    }

    /** 树层数（含叶子层）。 */
    public int height() {
        return levels.size();
    }

    /** 叶子(块)数。 */
    public int leafCount() {
        return levels.get(0).size();
    }

    /**
     * 增量更新：仅重算"叶子 leafIndex 到根路径"上的节点，其余节点复用旧值。
     * 返回重算后的新树（旧树不可变）。
     *
     * @param leafIndex 变化的叶子索引
     * @param newLeaf   该叶子的新哈希
     */
    public MerkleTreeDigest update(int leafIndex, String newLeaf)
            throws NoSuchAlgorithmException {
        checkLeafIndex(leafIndex);

        var newLevels = new ArrayList<List<String>>(levels.size());
        var l0 = new ArrayList<>(levels.get(0));
        l0.set(leafIndex, newLeaf);
        newLevels.add(l0);

        int affected = leafIndex;
        for (int level = 0; level < levels.size() - 1; level++) {
            var cur = newLevels.get(level);
            int parentCount = (cur.size() + 1) / 2;
            var next = new ArrayList<String>(parentCount);
            // 复制上一层的旧值，仅重算受影响父节点
            for (int i = 0; i < parentCount; i++) {
                next.add(levels.get(level + 1).get(i));
            }
            int p = affected / 2;
            int left = p * 2;
            int right = Math.min(left + 1, cur.size() - 1);
            next.set(p, hashParent(cur.get(left), cur.get(right), algorithm));
            newLevels.add(next);
            affected = p;
        }

        return new MerkleTreeDigest(newLevels, algorithm);
    }

    /**
     * 差异定位：对比两棵结构相同的树，返回所有变化的叶子(块)索引（升序、去重）。
     * 从根沿不一致分支下探，平均 O(log n) 次比较即可定位，无需全量比对。
     */
    public List<Integer> diff(MerkleTreeDigest other) {
        if (other == null) {
            throw new IllegalArgumentException("other tree must not be null");
        }
        if (this.height() != other.height() || this.leafCount() != other.leafCount()) {
            throw new IllegalArgumentException("trees must have identical structure");
        }
        var out = new ArrayList<Integer>();
        collectDiff(other, levels.size() - 1, 0, out);
        return out;
    }

    // ---- private ----

    /** 父节点 = 两个子节点哈希拼接后的哈希。 */
    private static String hashParent(String left, String right, String algorithm)
            throws NoSuchAlgorithmException {
        var md = MessageDigest.getInstance(algorithm);
        md.update(HexFormat.of().parseHex(left));
        md.update(HexFormat.of().parseHex(right));
        return HexFormat.of().formatHex(md.digest());
    }

    private void checkLeafIndex(int idx) {
        if (idx < 0 || idx >= leafCount()) {
            throw new IllegalArgumentException("leafIndex out of range: " + idx);
        }
    }

    /**
     * 递归对比。level 为当前层，nodeIndex 为当前节点在该层的下标。
     * 若节点相同则整棵子树相同，剪枝；否则下探子节点。
     */
    private void collectDiff(MerkleTreeDigest other, int level, int nodeIndex,
                             List<Integer> out) {
        if (this.levels.get(level).get(nodeIndex)
                .equals(other.levels.get(level).get(nodeIndex))) {
            return; // 该节点相同 -> 子树相同，剪枝
        }
        if (level == 0) {
            out.add(nodeIndex); // 叶子层差异
            return;
        }
        // 下探子节点。左子必存在；右子仅在非 padding 时存在。
        collectDiff(other, level - 1, nodeIndex * 2, out);
        int rightChild = nodeIndex * 2 + 1;
        if (rightChild < this.levels.get(level - 1).size()) {
            collectDiff(other, level - 1, rightChild, out);
        }
    }
}
