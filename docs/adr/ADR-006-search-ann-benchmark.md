# ADR-006: Search ANN strategy

- Status: Accepted
- Date: 2026-09-16

## Context

`JdbcSemanticKnowledgeSearch` is the correctness oracle: fresh projection only, cosine distance ascending, then entry UUID ascending. `JdbcHybridKnowledgeSearch` computes complete text and semantic `ROW_NUMBER()` rankings before RRF. The HYBRID query shape cannot directly use nearest-neighbor ordering; approximate candidate generation changes ranking semantics and needs a candidate-pool policy.

## Benchmark setup

Run: `./gradlew searchBenchmark` with PostgreSQL 17.11 / pgvector 0.8.6, image `pgvector/pgvector:0.8.6-pg17-bookworm`; dimensions 384; seed 7112026; 5 warmups, 20 timed repetitions, K=25 and `ef_search=40`. Synthetic unit-centroid clusters have deterministic per-entry noise. Reports are warm steady-state, single-client, local Docker Desktop on Linux amd64, 8 CPUs, JVM 21.0.12 (max heap 1.94 GB). Absolute timings are environment-specific.

## Measured semantic results

| Rows | Exact p50 / p95 ms | HNSW p50 / p95 ms | HNSW Recall@25 | HNSW 10% Recall@25 | HNSW 1% Recall@25 |
|---:|---:|---:|---:|---:|---:|
| 1,000 | 3.691 / 4.392 | 3.550 / 4.665 | 1.00 | 1.00 | 0.40 |
| 10,000 | 15.824 / 19.620 | 2.359 / 3.289 | 0.96 | 0.16 | 0.00 |
| 50,000 | 57.308 / 65.269 | 2.989 / 3.409 | 0.76 | 0.12 | 0.00 |

At 1k HNSW has little latency benefit. At 10k and 50k the unfiltered latency improves substantially, but recall falls as volume grows. Filtered recall is poor at 10k/50k despite faster queries. For graph-limited candidate sets of 100, HNSW was not faster in this run (p50 8.480 ms versus exact 8.301 ms at 1k; 10.277 versus 11.310 ms at 10k; 11.005 versus 8.674 ms at 50k); exact remains preferable for small candidate sets.

## Setup cost

| Rows | Load and ANALYZE ms | HNSW build ms | HNSW size | Embedding table size |
|---:|---:|---:|---:|---:|
| 1,000 | 736.1 | 210.3 | 2,056,192 bytes | 4,243,456 bytes |
| 10,000 | 4,740.4 | 2,376.4 | 20,488,192 bytes | 44,007,424 bytes |
| 50,000 | 23,245.8 | 50,169.1 | 102,408,192 bytes | 210,452,480 bytes |

The index is material in size and build time. Setup and index build are excluded from query latency.

## Filter and HYBRID observations

Selective property filters reduce HNSW recall sharply at `ef_search=40`; this benchmark did not explore iterative scans or higher `ef_search`. Approximate HYBRID top-25 overlap against exact RRF was 0.80/0.80/0.92/0.96 for pools 50/100/250/500 at 1k, and 1.00 for all tested pools at 10k and 50k. However its p50 was 66.5–72.9 ms at 10k and 359.6–442.9 ms at 50k, showing that this candidate SQL shape still pays for broad text ranking and is not yet an attractive HYBRID optimization.

The captured JSON plans show HNSW index use at 10k and 50k (`bench_hnsw_idx`), but not at 1k. Filtered HNSW and filtered HYBRID plans were not separately captured, so planner behavior for those scenarios remains an evidence gap. Full plans are in `build/reports/search-benchmark/results.json` (local generated output, not committed).

## Decision

**A. Keep exact search for now.** The measured unfiltered speedup at 10k/50k does not offset recall loss at the tested `ef_search`, severe filtered recall loss, graph candidate sets where exact is cheaper, and index build/storage cost. No production code, API, or migration changes are made.

## Revisit conditions

Repeat the experiments with `ef_search` sweeps and pgvector iterative scans, capture filtered and graph-limited plans, and measure recall across more query clusters and K=10/25. Consider exact retrieval for small candidate sets and HNSW only for broad unfiltered retrieval if recall reaches the agreed quality threshold while preserving a meaningful p95 gain. Any future HYBRID policy must version its candidate pool in the ranking fingerprint and compare against exact RRF before production adoption.
