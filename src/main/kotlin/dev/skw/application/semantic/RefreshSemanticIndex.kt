package dev.skw.application.semantic

import dev.skw.domain.entry.Entry

fun interface RefreshSemanticIndexUseCase {
    fun refresh(batchSize: Int): SemanticRefreshResult
}

data class SemanticRefreshResult(
    val indexed: Int,
    val reused: Int,
    val skippedStale: Int,
)

class RefreshSemanticIndexService(
    private val provider: EmbeddingProvider,
    private val store: SemanticProjectionStore,
    private val documents: SemanticDocumentBuilder = SemanticDocumentBuilder(),
) : RefreshSemanticIndexUseCase {
    override fun refresh(batchSize: Int): SemanticRefreshResult {
        require(batchSize > 0)
        val profile = provider.profile()
        val pending = store.findPending(profile, batchSize)
        val prepared = pending.map { it to documents.build(it) }
        val projections =
            prepared
                .map { (entry, document) ->
                    val existing = store.findProjection(entry.workspaceId, entry.id, profile)
                    val hash = SemanticDocumentHasher().hash(document)
                    if (existing?.contentHash == hash) {
                        PreparedProjection(entry, hash, existing.embedding, true)
                    } else {
                        PreparedProjection(entry, hash, null, false)
                    }
                }.toMutableList()
        val changed = projections.withIndex().filter { !it.value.reused && prepared[it.index].second != SemanticDocument.EMPTY }
        if (changed.isNotEmpty()) {
            val vectors = provider.embed(changed.map { prepared[it.index].second.text })
            require(vectors.size == changed.size) { "Embedding provider returned an unexpected result count" }
            changed.zip(vectors).forEach { (item, vector) ->
                projections[item.index] = item.value.copy(embedding = vector.requireCompatibleWith(profile))
            }
        }
        var indexed = 0
        var reused = 0
        var skipped = 0
        projections.forEach { preparedProjection ->
            if (preparedProjection.reused) reused++
            val saved =
                store.upsertIfCurrent(
                    SemanticProjection(
                        preparedProjection.entry.workspaceId,
                        preparedProjection.entry.id,
                        profile,
                        preparedProjection.entry.version,
                        preparedProjection.hash,
                        preparedProjection.embedding,
                    ),
                )
            if (saved) indexed++ else skipped++
        }
        return SemanticRefreshResult(indexed, reused, skipped)
    }

    private data class PreparedProjection(
        val entry: Entry,
        val hash: String,
        val embedding: EmbeddingVector?,
        val reused: Boolean,
    )
}
