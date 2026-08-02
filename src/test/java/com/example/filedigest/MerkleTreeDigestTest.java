package com.example.filedigest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerkleTreeDigestTest {

    @TempDir
    Path tempDir;

    /** 单叶子：根 == 该叶子哈希。 */
    @Test
    void singleLeafRootEqualsLeaf() throws Exception {
        var tree = MerkleTreeDigest.build(List.of("aa"), "SHA-256");
        assertEquals("aa", tree.root());
        assertEquals(1, tree.height());
        assertEquals(1, tree.leafCount());
    }

    /** 4 叶子标准情形：手工计算 root（父节点=两子哈希字节拼接后哈希），验证结构正确。 */
    @Test
    void fourLeafManualRoot() throws Exception {
        var tree = MerkleTreeDigest.build(List.of("aa", "bb", "cc", "dd"), "SHA-256");
        assertEquals(parent(parent("aa", "bb"), parent("cc", "dd")), tree.root());
        assertEquals(3, tree.height());
    }

    /** 奇数叶子：5 叶子 -> 补齐，层数正确且可复现。 */
    @Test
    void oddLeafCountPadding() throws Exception {
        var tree = MerkleTreeDigest.build(List.of("aa", "bb", "cc", "dd", "ee"), "SHA-256");
        assertEquals(4, tree.height());
        assertEquals(5, tree.leafCount());
        var again = MerkleTreeDigest.build(List.of("aa", "bb", "cc", "dd", "ee"), "SHA-256");
        assertEquals(tree.root(), again.root());
    }

    /** 核心：update 增量重算后 root == 从变更叶子整树重建的 root。 */
    @Test
    void incrementalUpdateEqualsRebuild() throws Exception {
        var leaves = List.of("00", "01", "02", "03", "04", "05", "06", "07");
        var original = MerkleTreeDigest.build(leaves, "SHA-256");

        var updated = original.update(3, "FE");
        var rebuilt = MerkleTreeDigest.build(
                List.of("00", "01", "02", "FE", "04", "05", "06", "07"), "SHA-256");

        assertEquals(rebuilt.root(), updated.root());
        assertNotEquals(original.root(), updated.root());
    }

    /** 单块变更 diff 精确定位到该块。 */
    @Test
    void diffFindsSingleChangedLeaf() throws Exception {
        var leaves = List.of("00", "01", "02", "03", "04", "05", "06", "07");
        var original = MerkleTreeDigest.build(leaves, "SHA-256");
        var updated = original.update(3, "FE");

        assertEquals(List.of(3), original.diff(updated));
    }

    /** 多块变更 diff 返回全部变化叶子。 */
    @Test
    void diffFindsMultipleLeaves() throws Exception {
        var leaves = List.of("00", "01", "02", "03", "04", "05", "06", "07");
        var original = MerkleTreeDigest.build(leaves, "SHA-256");
        var updated = original.update(1, "F1").update(6, "F6");

        var diffs = original.diff(updated);
        assertEquals(2, diffs.size());
        assertTrue(diffs.contains(1));
        assertTrue(diffs.contains(6));
    }

    /** 相同树 diff 为空。 */
    @Test
    void diffIdenticalTreesEmpty() throws Exception {
        var leaves = List.of("a0", "a1", "a2", "a3");
        var t1 = MerkleTreeDigest.build(leaves, "SHA-256");
        var t2 = MerkleTreeDigest.build(leaves, "SHA-256");
        assertTrue(t1.diff(t2).isEmpty());
    }

    /** 空叶子列表应抛异常。 */
    @Test
    void emptyLeavesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MerkleTreeDigest.build(List.of(), "SHA-256"));
    }

    /** 越界 update 抛异常。 */
    @Test
    void outOfRangeUpdateRejected() throws Exception {
        var tree = MerkleTreeDigest.build(List.of("aa", "bb", "cc"), "SHA-256");
        assertThrows(IllegalArgumentException.class, () -> tree.update(3, "x"));
    }

    /** 结构不同的两棵树 diff 抛异常。 */
    @Test
    void diffMismatchedStructureRejected() throws Exception {
        var t1 = MerkleTreeDigest.build(List.of("aa", "bb", "cc", "dd"), "SHA-256");
        var t2 = MerkleTreeDigest.build(List.of("aa", "bb", "cc", "dd", "ee"), "SHA-256");
        assertThrows(IllegalArgumentException.class, () -> t1.diff(t2));
    }

    /**
     * 真实大文件集成：对 84MB PDF 分块建树，修改中间某块后增量更新，
     * diff 应精确定位到该块。
     */
    @Test
    void realPdfIncrementalDiff() throws Exception {
        var pdf = Path.of("resources/testdata/bigFile84MB.pdf");
        if (!Files.exists(pdf)) {
            return; // 大文件未放时跳过
        }

        int chunkSize = FixedChunkDigest.DEFAULT_CHUNK_SIZE;
        var res = FixedChunkDigest.digest(pdf, chunkSize, "SHA-256");
        var original = MerkleTreeDigest.build(res.chunkHashes(), "SHA-256");
        int n = res.chunkHashes().size();
        assertTrue(n > 1);

        int target = n / 2;
        var updated = original.update(target, "deadbeef");

        var diffs = original.diff(updated);
        assertEquals(1, diffs.size());
        assertEquals(target, diffs.get(0));
    }

    /**
     * 大规模(1024 叶子)：随机改动若干叶子后，增量 update 的 root 必须等于
     * 从新叶子列表整树重建的 root，diff 必须精确定位所有改动叶子。
     * 覆盖大树的增量更新与差异定位正确性。
     */
    @Test
    void thousandLeafIncrementalAndDiff() throws Exception {
        int n = 1024;
        var leaves = new java.util.ArrayList<String>(n);
        for (int i = 0; i < n; i++) {
            leaves.add(String.format("%02x%02x%02x%02x", i & 0xff, (i >> 8) & 0xff,
                    (i * 7) & 0xff, (i * 13) & 0xff));
        }
        var original = MerkleTreeDigest.build(leaves, "SHA-256");

        // 改动 3 个不相邻叶子
        int[] changed = {17, 500, 1023};
        var newLeaves = new java.util.ArrayList<>(leaves);
        for (int idx : changed) {
            newLeaves.set(idx, String.format("f0%02xf0%02x", idx & 0xff, (idx >> 8) & 0xff));
        }

        var updated = original.update(changed[0], newLeaves.get(changed[0]))
                .update(changed[1], newLeaves.get(changed[1]))
                .update(changed[2], newLeaves.get(changed[2]));
        var rebuilt = MerkleTreeDigest.build(newLeaves, "SHA-256");

        // 增量更新的 root 必须与整树重建一致
        assertEquals(rebuilt.root(), updated.root());
        // diff 必须精确定位 3 个改动叶子
        var diffs = original.diff(updated);
        assertEquals(3, diffs.size());
        for (int idx : changed) {
            assertTrue(diffs.contains(idx));
        }
    }

    // ---- helper ----

    /** 父节点 = 两个子哈希字节拼接后哈希。 */
    private static String parent(String left, String right) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        md.update(HexFormat.of().parseHex(left));
        md.update(HexFormat.of().parseHex(right));
        return HexFormat.of().formatHex(md.digest());
    }
}
