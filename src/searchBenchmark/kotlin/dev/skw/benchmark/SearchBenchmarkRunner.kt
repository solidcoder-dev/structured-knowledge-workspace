package dev.skw.benchmark

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private data class Config(
    val sizes: List<Int> = System.getenv("SKW_BENCH_SIZES")?.split(',')?.map(String::toInt) ?: listOf(1_000, 10_000, 50_000),
    val dimensions: Int = System.getenv("SKW_BENCH_DIMENSIONS")?.toInt() ?: 384,
    val warmups: Int = System.getenv("SKW_BENCH_WARMUPS")?.toInt() ?: 5,
    val repetitions: Int = System.getenv("SKW_BENCH_REPETITIONS")?.toInt() ?: 20,
    val seed: Int = System.getenv("SKW_BENCH_SEED")?.toInt() ?: 711_2026,
    val limit: Int = System.getenv("SKW_BENCH_LIMIT")?.toInt() ?: 25,
    val efSearch: Int = System.getenv("SKW_BENCH_EF_SEARCH")?.toInt() ?: 40,
    val candidatePools: List<Int> = System.getenv("SKW_BENCH_POOLS")?.split(',')?.map(String::toInt) ?: listOf(50, 100, 250, 500),
)

private data class Measurement(
    val name: String,
    val mode: String,
    val strategy: String,
    val filter: String,
    val k: Int,
    val pool: Int?,
    val latencies: List<Double>,
    val recall: Double?,
    val overlap: Double?,
)

private data class DatasetMetric(
    val size: Int,
    val setupMs: Double,
    val indexBuildMs: Double,
    val indexBytes: Long,
    val tableBytes: Long,
)

private data class SearchBenchmarkScenario(
    val datasetSize: Int,
    val dimensions: Int,
    val mode: String,
    val query: String,
    val filter: String,
    val candidateSetSize: Int?,
    val limit: Int,
    val strategy: String,
)

object SearchBenchmarkRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        val config = Config()
        require(config.dimensions in listOf(384, 768, 1536))
        require(config.sizes.isNotEmpty() && config.sizes.all { it > 0 })
        require(config.warmups >= 0 && config.repetitions > 0 && config.limit in listOf(10, 25, 100) && config.efSearch > 0)
        val postgres = PostgreSQLContainer<Nothing>("pgvector/pgvector:0.8.6-pg17-bookworm")
        postgres.start()
        try {
            val connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
            connection.use { db ->
                Flyway
                    .configure()
                    .dataSource(
                        postgres.jdbcUrl,
                        postgres.username,
                        postgres.password,
                    ).locations("classpath:db/migration")
                    .schemas("skw")
                    .defaultSchema("skw")
                    .load()
                    .migrate()
                val measurements = mutableListOf<Measurement>()
                val plans = mutableListOf<String>()
                val datasetMetrics = mutableListOf<DatasetMetric>()
                config.sizes.forEachIndexed { sizeIndex, size ->
                    val workspace = UUID.nameUUIDFromBytes("skw-benchmark-${config.seed}-$size".toByteArray())
                    reset(db, workspace)
                    val setupStart = System.nanoTime()
                    load(db, workspace, size, config)
                    val setupMs = (System.nanoTime() - setupStart) / 1e6
                    db.createStatement().use { it.execute("ANALYZE skw.entries; ANALYZE skw.entry_semantic_embeddings") }
                    val queryCluster = Random(config.seed + sizeIndex).nextInt(10)
                    val query = vector(config.dimensions, queryCluster, config.seed + sizeIndex)
                    db.createStatement().use { it.execute("SET hnsw.ef_search = ${config.efSearch}") }
                    val queryText = "common cluster${sizeIndex % 10}"
                    val indexStart = System.nanoTime()
                    db.createStatement().use {
                        it.execute(
                            """
                            CREATE INDEX bench_hnsw_idx
                            ON skw.entry_semantic_embeddings
                            USING hnsw ((embedding::vector(${config.dimensions})) vector_cosine_ops)
                            WHERE profile_id = 'bench-v1'
                            AND dimensions = ${config.dimensions}
                            AND embedding IS NOT NULL
                            """.trimIndent(),
                        )
                        it.execute("ANALYZE skw.entry_semantic_embeddings")
                    }
                    val indexMs = (System.nanoTime() - indexStart) / 1e6
                    val idxSize = scalar(db, "SELECT pg_relation_size('skw.bench_hnsw_idx')")
                    val tableSize = scalar(db, "SELECT pg_total_relation_size('skw.entry_semantic_embeddings')")
                    datasetMetrics += DatasetMetric(size, setupMs, indexMs, idxSize, tableSize)
                    val cases =
                        listOf(
                            "none" to "",
                            "10%" to "AND (e.properties->>'bucket')::int < 10",
                            "1%" to "AND (e.properties->>'bucket')::int < 1",
                        )
                    cases.forEach { (filterName, filterSql) ->
                        val filteredOracle =
                            ids(
                                db,
                                semanticSql(config.dimensions, false, filterSql),
                                workspace,
                                query,
                                config.limit,
                            )
                        listOf("EXACT" to false, "HNSW" to true).shuffled(Random(config.seed + sizeIndex)).forEach {
                            (
                                strategy,
                                ann,
                            ),
                            ->
                            val sql = semanticSql(config.dimensions, ann, filterSql)
                            val times =
                                time(config, "${sizeIndex}_${strategy}_$filterName") {
                                    ids(
                                        db,
                                        sql,
                                        workspace,
                                        query,
                                        config.limit,
                                    )
                                }
                            val result = ids(db, sql, workspace, query, config.limit)
                            measurements +=
                                Measurement(
                                    "$size",
                                    "SEMANTIC",
                                    strategy,
                                    filterName,
                                    config.limit,
                                    null,
                                    times,
                                    recall(result, filteredOracle, config.limit),
                                    null,
                                )
                            if (filterName == "none") {
                                plans +=
                                    plan(
                                        db,
                                        sql,
                                        workspace,
                                        query,
                                        config.limit,
                                        "${size}_semantic_${strategy.lowercase()}",
                                    )
                            }
                        }
                    }
                    val textSql =
                        """
                        SELECT e.id FROM skw.entries e
                        WHERE e.workspace_id = ?
                        AND e.search_vector @@ websearch_to_tsquery('simple', ?)
                        ORDER BY ts_rank_cd(e.search_vector, websearch_to_tsquery('simple', ?)) DESC, e.id ASC
                        LIMIT ?
                        """.trimIndent()
                    listOf("none" to "", "10%" to "AND (e.properties->>'bucket')::int < 10").forEach { (filterName, filterSql) ->
                        val sql = textSql.replace("ORDER BY", "$filterSql ORDER BY")
                        measurements +=
                            Measurement(
                                "$size",
                                "TEXT",
                                "EXACT",
                                filterName,
                                config.limit,
                                null,
                                time(
                                    config,
                                    "${size}_TEXT_$filterName",
                                ) { textIds(db, sql, workspace, queryText, config.limit) },
                                null,
                                null,
                            )
                        if (filterName == "none") plans += textPlan(db, sql, workspace, queryText, config.limit, "${size}_text")
                    }
                    val hybrid = hybridSql(config.dimensions, false, "", 50, null)
                    val exactHybrid = idsWithText(db, hybrid, workspace, queryText, query, config.limit)
                    val candidatePools = config.candidatePools.filter { it >= config.limit }
                    candidatePools.forEach { pool ->
                        val approx = hybridSql(config.dimensions, true, "", 50, pool)
                        val times =
                            time(config, "${size}_HYBRID_pool$pool") {
                                idsWithText(
                                    db,
                                    approx,
                                    workspace,
                                    queryText,
                                    query,
                                    config.limit,
                                )
                            }
                        val actual = idsWithText(db, approx, workspace, queryText, query, config.limit)
                        measurements +=
                            Measurement(
                                "$size",
                                "HYBRID",
                                "HNSW_TOP_N_RRF",
                                "none",
                                config.limit,
                                pool,
                                times,
                                recall(actual, exactHybrid, config.limit),
                                recall(actual, exactHybrid, config.limit),
                            )
                    }
                    val graphIds = (0 until min(size, 100)).map { entryId(config.seed, size, it) }
                    val graphExact =
                        ids(
                            db,
                            semanticSql(config.dimensions, false, "AND e.id = ANY (?)"),
                            workspace,
                            query,
                            config.limit,
                            graphIds,
                        )
                    val graphAnn =
                        ids(
                            db,
                            semanticSql(config.dimensions, true, "AND e.id = ANY (?)"),
                            workspace,
                            query,
                            config.limit,
                            graphIds,
                        )
                    val graphExactTime =
                        time(config, "${size}_graph_exact") {
                            ids(
                                db,
                                semanticSql(
                                    config.dimensions,
                                    false,
                                    "AND e.id = ANY (?)",
                                ),
                                workspace,
                                query,
                                config.limit,
                                graphIds,
                            )
                        }
                    val graphAnnTime =
                        time(config, "${size}_graph_hnsw") {
                            ids(
                                db,
                                semanticSql(
                                    config.dimensions,
                                    true,
                                    "AND e.id = ANY (?)",
                                ),
                                workspace,
                                query,
                                config.limit,
                                graphIds,
                            )
                        }
                    measurements +=
                        Measurement(
                            "$size",
                            "SEMANTIC",
                            "EXACT_GRAPH_100",
                            "graph-100",
                            config.limit,
                            null,
                            graphExactTime,
                            1.0,
                            null,
                        )
                    measurements +=
                        Measurement(
                            "$size",
                            "SEMANTIC",
                            "HNSW_GRAPH_100",
                            "graph-100",
                            config.limit,
                            null,
                            graphAnnTime,
                            recall(graphAnn, graphExact, config.limit),
                            null,
                        )
                    if (sizeIndex == 0) {
                        plans +=
                            plan(
                                db,
                                semanticSql(config.dimensions, false, "AND (e.properties->>'bucket')::int < 1"),
                                workspace,
                                query,
                                config.limit,
                                "filtered_semantic_exact",
                            )
                        plans +=
                            textPlan(
                                db,
                                textSql.replace("ORDER BY", "AND (e.properties->>'bucket')::int < 10 ORDER BY"),
                                workspace,
                                queryText,
                                config.limit,
                                "filtered_text",
                            )
                        plans +=
                            hybridPlan(
                                db,
                                hybridSql(config.dimensions, false, "", 50, null),
                                workspace,
                                queryText,
                                query,
                                config.limit,
                                "hybrid_exact",
                            )
                        plans +=
                            plan(
                                db,
                                semanticSql(config.dimensions, false, "AND e.id = ANY (?)"),
                                workspace,
                                query,
                                config.limit,
                                "graph_limited_semantic",
                            )
                    }
                    println(
                        "loaded $size rows in ${"%.1f".format(setupMs)} ms; " +
                            "HNSW build ${"%.1f".format(indexMs)} ms; index=$idxSize bytes; table=$tableSize bytes",
                    )
                    db.createStatement().use { it.execute("DROP INDEX skw.bench_hnsw_idx") }
                }
                writeReports(config, measurements, plans, datasetMetrics)
            }
        } finally {
            postgres.stop()
        }
    }

    private fun reset(
        db: Connection,
        workspace: UUID,
    ) {
        db.createStatement().use { it.execute("DELETE FROM skw.entry_semantic_embeddings") }
        db.prepareStatement("DELETE FROM skw.entries WHERE workspace_id=?").use {
            it.setObject(1, workspace)
            it.executeUpdate()
        }
        db.prepareStatement("DELETE FROM skw.workspaces WHERE id=?").use {
            it.setObject(1, workspace)
            it.executeUpdate()
        }
        db.prepareStatement("INSERT INTO skw.workspaces(id) VALUES (?)").use {
            it.setObject(1, workspace)
            it.executeUpdate()
        }
    }

    private fun load(
        db: Connection,
        workspace: UUID,
        size: Int,
        config: Config,
    ) {
        db.autoCommit = false
        try {
            db.prepareStatement("INSERT INTO skw.entries(workspace_id,id,properties) VALUES (?,?,?::jsonb)").use { entry ->
                db
                    .prepareStatement(
                        """
                        INSERT INTO skw.entry_semantic_embeddings
                        (workspace_id,entry_id,profile_id,source_version,content_hash,dimensions,embedding)
                        VALUES (?,?,'bench-v1',1,?, ?, ?::vector)
                        """.trimIndent(),
                    ).use { embedding ->
                        repeat(size) { i ->
                            val cluster = i % 10
                            val id = entryId(config.seed, size, i)
                            val props =
                                """{"name":"cluster$cluster item$i","description":"common medium${i % 100} """ +
                                    """rare${i % 1000} cluster$cluster software finance education","category":"cluster$cluster", """ +
                                    """"tags":["common","medium${i % 100}","rare${i % 1000}"],"bucket":${i % 100}}"""
                            entry.setObject(1, workspace)
                            entry.setObject(2, id)
                            entry.setString(3, props)
                            entry.addBatch()
                            embedding.setObject(1, workspace)
                            embedding.setObject(2, id)
                            embedding.setString(
                                3,
                                "0".repeat(64),
                            )
                            embedding.setInt(4, config.dimensions)
                            embedding.setString(
                                5,
                                vector(
                                    config.dimensions,
                                    cluster,
                                    config.seed + i,
                                ),
                            )
                            embedding.addBatch()
                            if (i % 1000 == 999 || i == size - 1) {
                                entry.executeBatch()
                                embedding.executeBatch()
                            }
                        }
                    }
            }
            db.commit()
        } catch (failure: Throwable) {
            db.rollback()
            throw failure
        } finally {
            db.autoCommit = true
        }
    }

    private fun vector(
        dimensions: Int,
        cluster: Int,
        noiseSeed: Int = 100_000 + cluster,
    ): String {
        val random = Random(noiseSeed)
        val values = DoubleArray(dimensions) { if (it % 10 == cluster) 1.0 else 0.0 }
        for (i in values.indices) values[i] += random.nextDouble(-0.03, 0.03)
        val norm = kotlin.math.sqrt(values.sumOf { it * it })
        return values.joinToString(prefix = "[", postfix = "]") { "${it / norm}" }
    }

    private fun entryId(
        seed: Int,
        size: Int,
        i: Int,
    ) = UUID.nameUUIDFromBytes("$seed-$size-$i".toByteArray())

    private fun semanticSql(
        dimensions: Int,
        ann: Boolean,
        filter: String,
    ) = """
        SELECT e.id FROM skw.entries e
        JOIN skw.entry_semantic_embeddings p
          ON p.workspace_id=e.workspace_id AND p.entry_id=e.id
        WHERE e.workspace_id=? AND p.profile_id='bench-v1'
          AND p.source_version=e.version AND p.dimensions=$dimensions
          AND p.embedding IS NOT NULL $filter
        ORDER BY ${if (ann) "p.embedding::vector($dimensions) <=> ?::vector($dimensions), e.id" else "p.embedding <=> ?::vector, e.id"}
        LIMIT ?
        """.trimIndent()

    private fun ids(
        db: Connection,
        sql: String,
        workspace: UUID,
        query: String,
        limit: Int,
        candidateIds: List<UUID>? = null,
    ): List<UUID> =
        db.prepareStatement(sql).use { ps ->
            ps.setObject(1, workspace)
            if (candidateIds != null) ps.setArray(2, db.createArrayOf("uuid", candidateIds.toTypedArray()))
            ps.setString(if (candidateIds == null) 2 else 3, query)
            ps.setInt(if (candidateIds == null) 3 else 4, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }

    private fun textIds(
        db: Connection,
        sql: String,
        workspace: UUID,
        query: String,
        limit: Int,
    ): List<UUID> =
        db.prepareStatement(sql).use { ps ->
            ps.setObject(1, workspace)
            ps.setString(2, query)
            ps.setString(3, query)
            ps.setInt(4, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }

    private fun hybridSql(
        dimensions: Int,
        ann: Boolean,
        filter: String,
        rrfK: Int,
        pool: Int?,
    ): String {
        val annLimit = if (ann) "ORDER BY embedding::vector($dimensions) <=> ?::vector($dimensions) LIMIT $pool" else ""
        return """
            WITH eligible AS (
                SELECT e.* FROM skw.entries e WHERE e.workspace_id=? $filter
            ), text_ranked AS (
                SELECT id, ROW_NUMBER() OVER (
                    ORDER BY ts_rank_cd(search_vector, websearch_to_tsquery('simple', ?)) DESC, id
                ) pos FROM eligible WHERE search_vector @@ websearch_to_tsquery('simple', ?)
            ), semantic_ranked AS (
                SELECT e.id, ROW_NUMBER() OVER (
                    ORDER BY p.embedding::vector($dimensions) <=> ?::vector($dimensions), e.id
                ) pos
                FROM eligible e
                JOIN skw.entry_semantic_embeddings p
                  ON p.workspace_id=e.workspace_id AND p.entry_id=e.id
                WHERE p.profile_id='bench-v1' AND p.dimensions=$dimensions
                  AND p.source_version=e.version
                $annLimit
            ), fused AS (
                SELECT coalesce(t.id,s.id) id,
                    coalesce(1.0/($rrfK+t.pos),0)+coalesce(1.0/($rrfK+s.pos),0) score
                FROM text_ranked t FULL JOIN semantic_ranked s USING(id)
            )
            SELECT id FROM fused ORDER BY score DESC,id LIMIT ?
            """.trimIndent()
    }

    private fun idsWithText(
        db: Connection,
        sql: String,
        workspace: UUID,
        text: String,
        vector: String,
        limit: Int,
    ): List<UUID> =
        db.prepareStatement(sql).use { ps ->
            ps.setObject(1, workspace)
            ps.setString(2, text)
            ps.setString(3, text)
            ps.setString(4, vector)
            val hasAnnLimit = sql.contains("ORDER BY embedding::vector")
            if (hasAnnLimit) {
                ps.setString(
                    5,
                    vector,
                )
            }
            ps.setInt(if (hasAnnLimit) 6 else 5, limit)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }

    private fun time(
        config: Config,
        label: String,
        action: () -> Any,
    ): List<Double> {
        repeat(config.warmups) { action() }
        return (0 until config.repetitions).map {
            System.nanoTime().let { start ->
                action()
                (System.nanoTime() - start) / 1e6
            }
        }
    }

    private fun recall(
        actual: List<UUID>,
        exact: List<UUID>,
        k: Int,
    ): Double {
        if (k == 0) return 1.0
        return actual
            .take(k)
            .toSet()
            .intersect(exact.take(k).toSet())
            .size
            .toDouble() / k
    }

    private fun plan(
        db: Connection,
        sql: String,
        workspace: UUID,
        vector: String,
        limit: Int,
        name: String,
    ): String =
        db.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql").use { ps ->
            ps.setObject(1, workspace)
            if (sql.contains("ANY (?)")) {
                ps.setArray(
                    2,
                    db.createArrayOf(
                        "uuid",
                        emptyArray<UUID>(),
                    ),
                )
                ps.setString(3, vector)
                ps.setInt(4, limit)
            } else {
                ps.setString(2, vector)
                ps.setInt(
                    3,
                    limit,
                )
            }
            ps.executeQuery().use { rs ->
                rs.next()
                "\"$name\":${rs.getString(1)}"
            }
        }

    private fun textPlan(
        db: Connection,
        sql: String,
        workspace: UUID,
        query: String,
        limit: Int,
        name: String,
    ): String =
        db.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql").use { ps ->
            ps.setObject(1, workspace)
            ps.setString(2, query)
            ps.setString(3, query)
            ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                rs.next()
                "\"$name\":${rs.getString(1)}"
            }
        }

    private fun hybridPlan(
        db: Connection,
        sql: String,
        workspace: UUID,
        text: String,
        vector: String,
        limit: Int,
        name: String,
    ): String =
        db.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql").use { ps ->
            ps.setObject(1, workspace)
            ps.setString(2, text)
            ps.setString(3, text)
            ps.setString(4, vector)
            ps.setInt(5, limit)
            ps.executeQuery().use { rs ->
                rs.next()
                "\"$name\":${rs.getString(1)}"
            }
        }

    private fun scalar(
        db: Connection,
        sql: String,
    ) = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun stats(values: List<Double>): List<Double> {
        val sorted = values.sorted()

        fun percentile(p: Double) = sorted[(max(1, kotlin.math.ceil(p * sorted.size).toInt()) - 1).coerceIn(sorted.indices)]
        return listOf(sorted.first(), sorted.last(), values.average(), percentile(.50), percentile(.95), percentile(.99))
    }

    private fun writeReports(
        config: Config,
        rows: List<Measurement>,
        plans: List<String>,
        datasetMetrics: List<DatasetMetric>,
    ) {
        val out = Path.of("build/reports/search-benchmark")
        Files.createDirectories(out)
        val now = Instant.now().toString()
        val commit =
            runCatching {
                ProcessBuilder(
                    "git",
                    "rev-parse",
                    "HEAD",
                ).start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")
        val metadata =
            buildString {
                append("\"timestamp\":\"$now\",\"gitCommit\":\"$commit\",")
                append("\"postgres\":\"pgvector/pgvector:0.8.6-pg17-bookworm\",\"pgvector\":\"0.8.6\",")
                append("\"jvm\":\"${System.getProperty("java.version")}\",")
                append("\"os\":\"${System.getProperty("os.name")} ${System.getProperty("os.arch")}\",")
                append("\"cpuCount\":${Runtime.getRuntime().availableProcessors()},")
                append("\"maxMemoryBytes\":${Runtime.getRuntime().maxMemory()},\"seed\":${config.seed},")
                append("\"dimensions\":${config.dimensions},\"warmups\":${config.warmups},")
                append("\"repetitions\":${config.repetitions},\"limit\":${config.limit},")
                append("\"efSearch\":${config.efSearch},\"candidatePools\":[${config.candidatePools.joinToString(",")}] ")
            }
        val jsonRows =
            rows.joinToString(",") { r ->
                val s = stats(r.latencies)
                buildString {
                    append("{\"dataset\":${r.name},\"mode\":\"${r.mode}\",\"strategy\":\"${r.strategy}\",")
                    append("\"filter\":\"${r.filter}\",\"k\":${r.k},\"pool\":${r.pool ?: "null"},")
                    append("\"count\":${r.latencies.size},\"minMs\":${s[0]},\"maxMs\":${s[1]},")
                    append("\"meanMs\":${s[2]},\"p50Ms\":${s[3]},\"p95Ms\":${s[4]},\"p99Ms\":${s[5]},")
                    append("\"recallAtK\":${r.recall ?: "null"},\"overlapAtK\":${r.overlap ?: "null"}}")
                }
            }
        val jsonDatasetMetrics =
            datasetMetrics.joinToString(",") { metric ->
                "{\"dataset\":${metric.size},\"setupMs\":${metric.setupMs}," +
                    "\"indexBuildMs\":${metric.indexBuildMs},\"indexBytes\":${metric.indexBytes}," +
                    "\"tableBytes\":${metric.tableBytes}}"
            }
        Files.writeString(
            out.resolve("results.json"),
            "{\"metadata\":{$metadata},\"datasets\":[$jsonDatasetMetrics]," +
                "\"results\":[$jsonRows],\"plans\":{${plans.joinToString(",")}}}\n",
        )
        val csvRows =
            rows.joinToString("\n") { r ->
                val s = stats(r.latencies)
                (
                    listOf<Any?>(r.name, r.mode, r.strategy, r.filter, r.k, r.pool ?: "", r.latencies.size) +
                        s + listOf(r.recall ?: "", r.overlap ?: "")
                ).joinToString(",")
            }
        Files.writeString(
            out.resolve("results.csv"),
            "dataset,mode,strategy,filter,k,pool,count,min_ms,max_ms,mean_ms,p50_ms,p95_ms,p99_ms,recall_at_k,overlap_at_k\n$csvRows",
        )
        Files.writeString(
            out.resolve("summary.md"),
            buildString {
                appendLine("# Search benchmark")
                appendLine(
                    "Warm steady state; PostgreSQL pgvector/pgvector:0.8.6-pg17-bookworm; " +
                        "seed ${config.seed}; ${config.dimensions} dimensions; " +
                        "${config.warmups} warmups and ${config.repetitions} repetitions. Setup, loading, ANALYZE and index build " +
                        "are excluded from query latency.",
                )
                appendLine("\n| Dataset | Mode | Strategy | Filter | K | Pool | p50 ms | p95 ms | p99 ms | Recall@K |")
                appendLine("|---:|---|---|---|---:|---:|---:|---:|---:|---:|")
                rows.forEach { r ->
                    val s = stats(r.latencies)
                    appendLine(
                        "| ${r.name} | ${r.mode} | ${r.strategy} | ${r.filter} | ${r.k} | ${r.pool ?: ""} | " +
                            "${"%.3f".format(s[3])} | ${"%.3f".format(s[4])} | ${"%.3f".format(s[5])} | " +
                            "${r.recall?.let { "%.3f".format(it) } ?: ""} |",
                    )
                }
                appendLine("\n## Dataset and index setup")
                appendLine("\n| Dataset | Load ms | HNSW build ms | HNSW bytes | Embedding table bytes |")
                appendLine("|---:|---:|---:|---:|---:|")
                datasetMetrics.forEach { metric ->
                    appendLine(
                        "| ${metric.size} | ${"%.1f".format(metric.setupMs)} | " +
                            "${"%.1f".format(metric.indexBuildMs)} | ${metric.indexBytes} | ${metric.tableBytes} |",
                    )
                }
                appendLine(
                    "\nPlans are stored as JSON in results.json. Index size and build duration are printed per dataset. " +
                        "Check the HNSW plan tree before interpreting ANN timings.",
                )
            },
        )
    }
}
