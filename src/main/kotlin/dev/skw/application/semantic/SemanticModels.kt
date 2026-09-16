package dev.skw.application.semantic

import dev.skw.application.port.out.HybridRankingPolicy
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId

data class EmbeddingProfile(
    val profileId: String,
    val dimensions: Int,
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(dimensions > 0) { "dimensions must be positive" }
    }
}

data class EmbeddingVector private constructor(
    val values: List<Double>,
) {
    init {
        require(values.isNotEmpty()) { "embedding vector must not be empty" }
        require(values.all(Double::isFinite)) { "embedding vector values must be finite" }
    }

    fun requireCompatibleWith(profile: EmbeddingProfile): EmbeddingVector {
        require(values.size == profile.dimensions) {
            "embedding vector dimensions do not match profile"
        }
        return this
    }

    companion object {
        fun of(values: List<Double>): EmbeddingVector = EmbeddingVector(values.toList())
    }
}

data class SemanticDocument(
    val text: String,
) {
    companion object {
        val EMPTY = SemanticDocument("")
    }
}

data class SemanticProjection(
    val workspaceId: WorkspaceId,
    val entryId: EntryId,
    val profile: EmbeddingProfile,
    val sourceVersion: Version,
    val contentHash: String,
    val embedding: EmbeddingVector?,
) {
    init {
        require(contentHash.matches(Regex("[0-9a-f]{64}"))) { "contentHash must be lowercase SHA-256 hex" }
        embedding?.requireCompatibleWith(profile)
    }
}

interface EmbeddingProvider {
    fun profile(): EmbeddingProfile

    fun embed(documents: List<String>): List<EmbeddingVector>
}

interface SemanticProjectionStore {
    fun findPending(
        profile: EmbeddingProfile,
        limit: Int,
    ): List<Entry>

    fun findProjection(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        profile: EmbeddingProfile,
    ): SemanticProjection?

    fun upsertIfCurrent(projection: SemanticProjection): Boolean
}

data class SemanticSearchPlan(
    val workspaceId: WorkspaceId,
    val profile: EmbeddingProfile,
    val queryVector: EmbeddingVector,
    val filters: List<dev.skw.application.search.PropertyFilter>,
    val candidateIds: Set<EntryId>?,
    val continuation: dev.skw.application.search.SearchCursor?,
    val limit: Int,
    val fingerprint: String,
)

interface SemanticKnowledgeSearch {
    fun search(plan: SemanticSearchPlan): dev.skw.application.search.SearchPage
}

class EmbeddingProviderUnavailable : RuntimeException("Embedding provider unavailable")

class SemanticDocumentBuilder {
    fun build(entry: Entry): SemanticDocument =
        SemanticDocument(
            entry.properties
                .toSortedMap(compareBy { it.value })
                .flatMap { (name, value) ->
                    when (value) {
                        is dev.skw.domain.property.PropertyValue.StringValue -> listOf("${name.value}: ${value.value}")
                        is dev.skw.domain.property.PropertyValue.StringListValue -> value.value.map { "${name.value}: $it" }
                        else -> emptyList()
                    }
                }.joinToString("\n"),
        )
}

class SemanticDocumentHasher {
    fun hash(document: SemanticDocument): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(document.text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

object DerivedSearchFingerprint {
    fun withSemanticProfile(
        baseFingerprint: String,
        profile: EmbeddingProfile,
    ): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest("$baseFingerprint\u0000semantic\u0000${profile.profileId}\u0000${profile.dimensions}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun withHybrid(
        baseFingerprint: String,
        profile: EmbeddingProfile,
        policy: HybridRankingPolicy,
    ): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(
                "$baseFingerprint\u0000hybrid\u0000${profile.profileId}\u0000${profile.dimensions}\u0000${policy.identifier}".toByteArray(
                    Charsets.UTF_8,
                ),
            ).joinToString("") { "%02x".format(it) }
}
