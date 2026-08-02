# 📂 File Fingerprint Schemes Demo (file-digest-4-java)

> **Language**: [English](README_EN.md) · [中文](README.md)

A Java 25 demo project covering multiple file hash / fingerprint generation schemes — from single file to directory, from fast to cryptographically strong, from full-scan to incremental. All 9 schemes ship with unit tests and a throughput benchmark.

## 🛠️ Environment

- **Build**: Gradle (wrapper 9.6.1, self-contained, no global Gradle needed)
- **JDK**: 25 (uses modern syntax: `var`, `Path.of()`, `record`, virtual threads `Thread.ofVirtual()`)
- **Testing**: JUnit 5

## 🚀 Usage

```bash
# Run all tests (currently 105)
./gradlew test

# Unified entry point: digest a file or directory
./gradlew run --args="<path> [algorithm]"
./gradlew run --args="resources/testdata/bigFile84MB.pdf"   # file example
./gradlew run --args="/some/dir"                              # directory example

# Throughput benchmark (default bigFile84MB.pdf, or pass a file)
./gradlew benchmark
./gradlew benchmark --args="/path/to/large.bin"
```

## 📂 Project Structure

```
file-digest-4-java/
├── build.gradle / settings.gradle / gradlew
├── resources/testdata/           # large test data (untracked)
└── src/main/java/com/example/filedigest/
    ├── FileDigest.java           # unified entry (main), two-level orchestration demo
    ├── Benchmark.java            # throughput benchmark (warmup + median)
    ├── SmallFileDigest.java      # Scheme 1
    ├── StreamingDigest.java      # Scheme 2
    ├── FixedChunkDigest.java     # Scheme 3
    ├── MerkleTreeDigest.java     # Scheme 4
    ├── FastDigest.java           # Scheme 5
    ├── MultiAlgoDigest.java      # Scheme 6
    ├── DirectoryDigest.java      # Scheme 7
    ├── AttributeDigest.java      # Scheme 8
    ├── CdcDigest.java            # Scheme 9 (FastCDC)
    ├── SmartFileDigest.java      # memory-threshold auto-degrade (memory/streaming adaptive)
    └── TwoLevelDigest.java       # two-level fingerprint orchestration (L1 sentinel + L2 precise)
└── src/test/java/com/example/filedigest/
    ├── *Test.java                # per-scheme unit tests
    ├── FileDigestContractTest.java # cross-scheme parameter contracts
    ├── SmartFileDigestTest.java  # threshold degrade + three implementations agree
    ├── TwoLevelDigestTest.java   # two-level orchestration (incl. tamper-limit regression)
    └── TwoLevelDigestConcurrencyTest.java # concurrency stress (reads disk once)
```

---

## 🔍 Scheme Comparison

| # | Scheme | Core Idea | Use Case | Complexity / Notes |
| :--- | :--- | :--- | :--- | :--- |
| 1 | **Small file / in-memory** | Read whole file, one-shot `MessageDigest` | Files ≤ tens of MB | Time O(n), memory O(n), OOM risk under concurrency |
| 2 | **Large file / streaming** | `BufferedInputStream` + chunked `update()` | GB-scale files | Time O(n), constant O(1) memory |
| 3 | **Fixed-size chunking** | Fixed-size chunk hashes, then re-hash list | Simple dedup, resume | Time O(n), **boundary-shift sensitive** |
| 4 | **Incremental / Merkle tree** | Chunk-hash tree, recompute only affected path, O(log n) diff | Versioning, sync | O(log n)/update, O(n/chunk) space |
| 5 | **Fast fingerprint** | `CRC32` / `CRC32C` / `XXH3` non-crypto hashes | Cache keys, perf-sensitive | ~2x faster than SHA, no collision resistance |
| 6 | **Multi-algorithm combo** | One read pass, feed all `MessageDigest`s | Security + legacy compat | Single-pass I/O, parallel hashes |
| 7 | **Directory fingerprint** | Recursive scan, normalized path sort, re-hash | Directory consistency | O(total files), needs path normalization |
| 8 | **File attribute fingerprint** | name + size + mtime hashed | Ultra-fast sentinel | ~0ms, tamperable |
| 9 | **CDC content-defined chunking** | Gear rolling hash, content-based variable chunks | Backup dedup, diff sync | O(n), **boundary-shift resistant** |

---

## ⚙️ Implementation & Design Notes

### 1️⃣ Small file / in-memory — `SmallFileDigest`
`readAllBytes` then one `digest`. Simple, but large files / high concurrency risk OOM. For production use **`SmartFileDigest` memory-threshold auto-degrade**: `size ≤ threshold` → in-memory, above → auto-switch to streaming (Scheme 2) to avoid OOM; callers never worry about file size.

### 2️⃣ Large file / streaming — `StreamingDigest`
Fixed 8KB buffer loop `update(buf, 0, len)`. **Key**: feed only the actually-read length, so a partially-filled final buffer can't inject stale bytes. Constant memory. A test uses a 1-byte buffer to force many iterations and verify no byte loss.

### 3️⃣ Fixed-size chunking — `FixedChunkDigest`
Chunk by fixed size, **one virtual thread per chunk** (`Executors.newVirtualThreadPerTaskExecutor()`) to read + hash in parallel, collected by index for determinism. Returns per-chunk hashes (content-addressing keys) + a re-hashed file fingerprint.
**Note**: fixed boundaries suffer boundary shift — inserting 1 byte at the start misaligns every later boundary and collapses the dedup rate. For dedup-rate-sensitive workloads use Scheme 9.

### 4️⃣ Incremental / Merkle tree — `MerkleTreeDigest`
Leaves = chunk hashes; parent = hash of the two children's bytes concatenated; root = file fingerprint. Odd node counts are padded by duplicating the last leaf.
- `update(leaf, new)`: recomputes only the path from that leaf to the root; everything else is reused (incremental)
- `diff(other)`: descends along mismatching branches from the root, O(log n) to locate all changed leaves
Used by Git's object model, IPFS, and object-storage ETags.

### 5️⃣ Fast fingerprint — `FastDigest`
JDK built-in `CRC32` / `CRC32C` (hardware-accelerated on Apple Silicon) + third-party `XXH3` (zero-allocation-hashing), streaming constant memory. Fast but non-cryptographic, so collisions are possible at scale — use as a fast pre-filter, then confirm with SHA-256.
**Note**: zero-allocation-hashing 0.16 lacks a streaming API, so XXH3 reads the whole file into memory; use CRC32/CRC32C (streaming) or a newer library version for huge files.

### 6️⃣ Multi-algorithm combo — `MultiAlgoDigest`
One `read` loop feeds the same buffer to multiple `MessageDigest`s, reading the disk once. `MessageDigest` is not thread-safe, so instances are used sequentially on the main thread without sharing. Suited to SHA-256 (new) + MD5 (legacy compat) mixes.

### 7️⃣ Directory fingerprint — `DirectoryDigest`
`walkFileTree` recursive scan, per-file streaming SHA-256, **path normalization** (`\` → `/`, solving the cross-platform classic bug), lexicographic sort, then re-hash `path:hash` records. Symlinks not followed; permission failures are skipped without aborting.

### 8️⃣ File attribute fingerprint — `AttributeDigest`
`BasicFileAttributes` reads size + mtime once, hashes `"filename:size:mtime"`, never reads file content. **Limitation**: tamperable (metadata can be faked) — a sentinel only, never a final check.

### 9️⃣ CDC content-defined chunking — `CdcDigest` (FastCDC-style)
Gear rolling hash `fp = (fp<<1) + GEAR[byte]` scans and picks content-based variable chunk boundaries. Min/avg/max constraints + a normalization mask (wider near the front, easier to cut) push chunk lengths toward `avg`.
**Core value**: boundary-shift resistance — inserting/deleting bytes only affects nearby chunks, so the dedup rate stays stable (measured below).

---

## 🧭 Two-Level Fingerprint Orchestration (production pattern)

One algorithm isn't enough; production systems layer cheap-then-precise to balance cost vs accuracy. **`TwoLevelDigest`** encapsulates this (a simplified demo also lives in `FileDigest`):

- **L1 sentinel (~0ms)**: Scheme 8 attribute fingerprint (size + mtime), no content read.
- **L2 precise (reads disk)**: Scheme 2 streaming SHA-256, only when L1 misses.
- **Flow**: compute attribute fingerprint → compare with cache (unchanged → reuse hash, zero I/O) → else upgrade to full content read.
- **Quantify**: `contentReads()` counts actual disk reads to show I/O saved.

```java
var digest = new TwoLevelDigest();
digest.resolve(path, "SHA-256"); // first call: 1 disk read
digest.resolve(path, "SHA-256"); // unchanged: L1 cache hit, 0 disk reads
digest.contentReads();           // still 1
```

**Limitation (must know)**: L1 is only a sentinel — if content is modified and mtime+size are restored, L1 misjudges it as unchanged and wrongly reuses the stale hash (locked in by `TwoLevelDigestTest.tamperWithSameAttrsIsNotDetected`). L1 controls cost; L2 is the trust anchor — for security-sensitive workloads add periodic forced-L2 checks.

**Concurrency safety**: `resolve` uses `compute` atomically, so a file is read at most once even under concurrent first access (`TwoLevelDigestConcurrencyTest` verifies: 32 threads → 1 read, 64 threads cache hits → no extra reads, 84MB PDF concurrent → 1 read).

Avoiding a full read on every sync/upload is the cost-effective pattern behind cloud drives, backups, and object stores.

---

## 📊 Benchmark Results

`./gradlew benchmark`, file `bigFile84MB.pdf` (79.98 MB), warmup 3 + 7 rounds median:

```
Scheme1 in-memory(whole file)   51 ms   1568 MB/s
Scheme2 streaming(8KB)          42 ms   1904 MB/s
Scheme3 fixed 4MB + vthread      9 ms   8887 MB/s
Scheme3 fixed 1MB + vthread      9 ms   8887 MB/s
Scheme3 fixed 64KB + vthread    11 ms   7271 MB/s
Scheme5 CRC32                   21 ms   3809 MB/s
Scheme5 CRC32C                  19 ms   4210 MB/s
Scheme5 XXH3                    19 ms   4209 MB/s
Scheme6 combo SHA-256+MD5      156 ms    513 MB/s
Scheme6 separate SHA-256+MD5   168 ms    476 MB/s
Scheme8 attribute (no read)      0 ms   ~microseconds
Scheme9 CDC (in-memory)        131 ms    611 MB/s
Scheme9 CDC (streaming)        126 ms    635 MB/s
```

Interpretation (honest):
- Schemes 1/2 hit the single-core SHA-256 physical ceiling (openssl single-core ≈ 40ms/80MB).
- Scheme 3's 8887 MB/s is **virtual-thread multi-core parallelism + page cache**, not pure hash speed.
- Scheme 5 is ~2x faster than SHA-256 (lighter algorithm) but single-threaded, below Scheme 3's multi-core.
- Scheme 6's single-pass I/O edge is masked by page cache (~2.5%); it matters only under real disk bottlenecks.
- Scheme 8 is too fast to time in ms — exactly its "no content read" positioning.
- Scheme 9's streaming version, after a **zero-copy optimization**, is slightly faster than in-memory (126 vs 131ms): the in-memory version reads the whole file onto the heap and copies, while streaming updates a large read buffer directly without copying, with constant memory (maxSize + 8MB buffer) — good for huge files. Boundaries are byte-identical to the in-memory version (regression-tested).

---

## 🧪 Test Coverage (105)

Each scheme's tests include known vectors, determinism/distinctness, cross-validation against independent implementations, and a real 84MB PDF smoke test (skipped if the file is absent). Additional highlights:

- **Scheme 9 boundary-shift regression**: inserting 1 byte at the start, asserts CDC reuse > 60%, fixed-chunking < 30%, CDC significantly higher.
- **Merkle incremental correctness**: `update`'s root == root rebuilt from the changed leaf list; 1024-leaf diff pinpoints changes.
- **Cross-scheme parameter contracts**: missing files, invalid algorithms, directories-as-files, and chunkSize ≤ 0 are consistently rejected.
- **Concurrency**: TwoLevelDigest reads each file at most once under multi-thread contention.

## 📌 Known Boundaries / TODO

- Scheme 9 offers both in-memory `digest` and streaming `digestStreaming` (memory = max chunk buffer, supports huge files); they produce byte-identical boundaries (regression-tested).
- Benchmarks are affected by page cache; cold-cache or GB-scale files are needed for pure CPU comparison.
- zero-allocation-hashing 0.16 has no streaming API for XXH3 (whole-file in memory); use CRC32/CRC32C (streaming) or a newer library for huge files.
