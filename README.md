# 📂 多种文件指纹 Demo 实现

一个基于 Java 25 的文件哈希 / 指纹生成方式演示项目，完整覆盖从单文件到目录、从快速到强安全、从全量到增量的 9 种方案，均含单测与吞吐基准。

## 🛠️ 开发环境

- **构建工具**: Gradle (wrapper 9.6.1，项目自包含，无需全局 Gradle)
- **JDK 版本**: 25（使用 `var`、`Path.of()`、`record`、虚拟线程 `Thread.ofVirtual()` 等现代语法）
- **测试**: JUnit 5

## 🚀 运行方式

```bash
# 所有单测（当前 89 个）
./gradlew test

# 统一入口：对文件/目录跑适用方案
./gradlew run --args="<path> [algorithm]"
./gradlew run --args="resources/testdata/bigFile84MB.pdf"   # 文件示例
./gradlew run --args="/some/dir"                              # 目录示例

# 吞吐基准（默认 bigFile84MB.pdf，可指定文件）
./gradlew benchmark
./gradlew benchmark --args="/path/to/large.bin"
```

## 📂 项目结构

```
file-digest-4-java/
├── build.gradle / settings.gradle / gradlew
├── resources/testdata/           # 大文件测试数据（不追踪）
└── src/main/java/com/example/filedigest/
    ├── FileDigest.java           # 统一入口(main)，两级指纹编排演示
    ├── Benchmark.java            # 吞吐基准(warmup + 中位数)
    ├── SmallFileDigest.java      # 方案 1
    ├── StreamingDigest.java      # 方案 2
    ├── FixedChunkDigest.java     # 方案 3
    ├── MerkleTreeDigest.java     # 方案 4
    ├── FastDigest.java           # 方案 5
    ├── MultiAlgoDigest.java      # 方案 6
    ├── DirectoryDigest.java      # 方案 7
    ├── AttributeDigest.java      # 方案 8
    ├── CdcDigest.java            # 方案 9 (FastCDC)
    └── TwoLevelDigest.java       # 两级指纹编排（L1 哨兵 + L2 精确）
└── src/test/java/com/example/filedigest/
    ├── *Test.java                # 每个方案的单测
    ├── FileDigestContractTest.java # 跨方案参数契约
    └── TwoLevelDigestTest.java   # 两级编排（含篡改局限回归）
```

---

## 🔍 核心指纹设计方案比较

| 编号 | 方案名称 | 核心原理 | 适用场景 | 复杂度 / 性能 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | **小文件 / 内存可放** | 全量读入内存，`MessageDigest` 一次性计算 | ≤ 几十 MB 的小文件 | 时间 O(n)，内存 O(n)，多用户并发易 OOM |
| 2 | **大文件 / 流式** | `BufferedInputStream` + 分块读取，`MessageDigest.update()` 逐块处理 | GB 级大文件 | 时间 O(n)，内存恒定 O(1) |
| 3 | **大文件 + 固定分块指纹** | 固定大小切块算 hash，对 hash 列表二次哈希 | 简单去重、断点续传 | 时间 O(n)，**边界漂移敏感** |
| 4 | **增量指纹（Merkle 树）** | 分块哈希建 Merkle 树，只重算受影响路径，O(log n) 定位差异块 | 版本追踪、同步 | 时间 O(log n)/更新，空间 O(n/块) |
| 5 | **快速指纹** | `CRC32` / `CRC32C` 非加密哈希 | 缓存 key、性能敏感 | 快一个量级，不具加密抗碰撞 |
| 6 | **多算法组合** | 单次读取流同时算 SHA-256 + MD5，返回 `Map` | 兼顾安全与兼容 | 单次 I/O，双哈希并行 |
| 7 | **目录指纹** | 递归扫描，路径归一化排序，递归拼接二次哈希 | 目录一致性比对 | O(总文件数)，需跨平台路径归一 |
| 8 | **文件属性指纹** | 文件名 + 大小 + mtime 拼接哈希 | 极速前置哨兵 | ~0ms，不防篡改 |
| 9 | **CDC 内容定义分块** | Gear 滚动哈希按内容切变长块，块哈希二次哈希 | 备份去重、差异同步 | O(n)，**抗边界漂移** |

---

## ⚙️ 方案实现与设计要点

### 1️⃣ 小文件 / 内存可放 — `SmallFileDigest`
整文件 `readAllBytes` 后一次 `digest`。简单直接，但大文件/高并发易 OOM。企业落地应设内存阈值，超限自动降级到方案 2。

### 2️⃣ 大文件 / 流式 — `StreamingDigest`
固定 8KB 缓冲循环 `update(buf, 0, len)`。**关键**：只喂实际读到的长度，避免末次半满缓冲混入脏字节。内存恒定，是方案 1 的流式版。单测用 1 字节缓冲强迫大量循环验证无丢字节。

### 3️⃣ 固定分块 — `FixedChunkDigest`
按块大小切块，**每块一个虚拟线程**（`Executors.newVirtualThreadPerTaskExecutor()`）并行读+哈希，按块序号收集保证确定性。返回每块哈希（内容寻址 key）+ 二次哈希文件指纹。
**注意**：固定边界有边界漂移问题——开头插入 1 字节，后续所有块边界全错位、去重率崩塌。追求去重率改用方案 9。

### 4️⃣ 增量指纹 / Merkle 树 — `MerkleTreeDigest`
叶子 = 每块哈希，父节点 = 两子哈希字节拼接后哈希，根 = 整文件指纹。奇数节点补齐（复制最后叶子）。
- `update(leaf, new)`：只重算该叶子到根的路径，其余复用旧值（增量）
- `diff(other)`：从根沿不一致分支下探，O(log n) 定位所有变化叶子
Git 对象模型、IPFS、对象存储 ETag 均采用此结构。

### 5️⃣ 快速指纹 — `FastDigest`
JDK 内置 `CRC32` / `CRC32C`（Apple Silicon 有硬件加速），流式内存恒定。速度快但仅 32 位，文件量大时有碰撞——企业去重做前置快筛，命中后用 SHA-256 二次确认。

### 6️⃣ 多算法组合 — `MultiAlgoDigest`
一次 `read` 循环，把同一段缓冲同时 `update` 给多个 `MessageDigest`，只读一遍磁盘。`MessageDigest` 非线程安全，此处主线程内顺序使用不共享。适用 SHA-256(新) + MD5(旧系统兼容) 混合。

### 7️⃣ 目录指纹 — `DirectoryDigest`
`walkFileTree` 递归扫描，每文件流式 SHA-256，**路径归一化**（`\` → `/`，解决跨平台经典 bug）、字典序排序，`path:hash` 拼接二次哈希。符号链接不跟随、权限失败跳过不中断。

### 8️⃣ 文件属性指纹 — `AttributeDigest`
`BasicFileAttributes` 一次取 size + mtime，`"文件名:大小:mtime"` 拼接哈希，不读文件内容。**局限**：不防篡改（可还原元信息伪造），只作哨兵，不可作最终校验。

### 9️⃣ CDC 内容定义分块 — `CdcDigest` (FastCDC 风格)
Gear 滚动哈希 `fp = (fp<<1) + GEAR[byte]` 扫描，由内容定变长块边界。最小/平均/最大块约束 + 规范化掩码（位置越靠前越易切）让块长趋近 avg。
**核心价值**：抗边界漂移，插入/删除字节只影响附近块，去重率稳定（实测见下）。

---

## 🧭 两级指纹编排（企业真实用法）

单一算法不够，企业用分层组合权衡成本与准确率。**独立类 `TwoLevelDigest`** 封装了这套逻辑（`FileDigest` 也有简版示范）：

- **L1 前置哨兵（~0ms）**：方案 8 属性指纹（size + mtime），不读内容。
- **L2 精确校验（读盘）**：方案 2 流式 SHA-256，仅当 L1 未命中才触发。
- **流程**：算属性指纹 → 与缓存比对（未变则复用哈希、零 I/O）→ 变了或首次才升级读全内容。
- **量化**：`contentReads()` 记录实际读盘次数，可对比省了多少 I/O。

```java
var digest = new TwoLevelDigest();
digest.resolve(path, "SHA-256"); // 首次：读盘 1 次
digest.resolve(path, "SHA-256"); // 未变：命中 L1 缓存，0 读盘
digest.contentReads();           // 仍为 1
```

**局限（必须知晓）**：L1 只是哨兵，不防篡改——若修改内容并还原 mtime+size，L1 会误判为"未变"而错误复用旧哈希（`TwoLevelDigestTest.tamperWithSameAttrsIsNotDetected` 固化了此行为）。L1 用于成本控制，L2 才是最终信任锚点，安全敏感场景应加"定期强制 L2"。

避免每次同步/上传都对全量读盘，是云盘、备份、对象存储的高性价比落地方式。

---

## 📊 基准结果

`./gradlew benchmark`，测试文件 `bigFile84MB.pdf`（79.98 MB），warmup 3 + 7 轮中位数：

```
方案1 内存(整文件)          51 ms   1568 MB/s
方案2 流式(8KB)             42 ms   1904 MB/s
方案3 固定分块4MB+虚拟线程   9 ms   8887 MB/s
方案3 固定分块1MB+虚拟线程   9 ms   8887 MB/s
方案3 固定分块64KB+虚拟线程 16 ms   4999 MB/s
方案5 CRC32                21 ms   3809 MB/s
方案5 CRC32C               19 ms   4210 MB/s
方案6 组合SHA-256+MD5     156 ms    513 MB/s
方案6 对比: 分开算         168 ms    476 MB/s
方案8 属性指纹(不读内容)     0 ms   ~微秒级
方案9 CDC(内存版)         131 ms    611 MB/s
方案9 CDC(流式版)         126 ms    635 MB/s
```

解读（诚实标注）：
- 方案 1/2 是单核 SHA-256 物理上限（对照 openssl 单核约 40ms/80MB）。
- 方案 3 的 8887 MB/s 是**虚拟线程多核并行 + 页缓存**叠加，非纯哈希速度。
- 方案 5 比 SHA-256 快约 2 倍（换更轻算法），但单线程，未超方案 3 多核。
- 方案 6 单次 I/O 优势被页缓存掩盖（仅 ~2.5%），在真正磁盘瓶颈场景才显著。
- 方案 8 太快，毫秒计时为 0，恰体现"不读内容"的定位。
- 方案 9 流式版经**免拷贝优化**后略快于内存版（126 vs 131ms）：内存版整读文件到堆并拷贝，流式版直接对大读缓冲 `md.update` 免拷贝，同时内存恒定（maxSize + 8MB 读缓冲），支持超大文件。块边界与内存版逐块一致（有回归测试）。

---

## 🧪 测试覆盖（89 个）

每个方案单测含：已知向量、确定性/区分性、与独立实现交叉验证、真实 84MB PDF 冒烟（文件存在才跑）。额外有：

- **方案 9 抗边界漂移回归**：开头插入 1 字节，断言 CDC 复用率 > 60%、固定分块 < 30%、CDC 显著更高。
- **Merkle 增量正确性**：`update` 后的根 == 从变更叶子整树重建的根；1024 叶子 diff 精确定位。
- **跨方案参数契约**：不存在的文件、非法算法、目录当文件、chunkSize ≤ 0，统一拒绝。

## 📌 已知边界 / 待完善

- 方案 9 提供内存版 `digest` 与流式版 `digestStreaming`（内存只占最大块缓冲，支持超大文件），两者产生完全相同的块边界（有回归测试）。
- 基准受页缓存影响，纯 CPU 对比需冷缓存或 GB 级文件。
- 未接入 XXHash（第三方库），当前快速指纹用 JDK 内置 CRC。
