package dev.skw.adapter.outbound.semantic

import dev.skw.application.semantic.RefreshSemanticIndexUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.math.max

@Component
@ConditionalOnProperty(prefix = "semantic.indexing", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty(prefix = "semantic.embedding", name = ["enabled"], havingValue = "true")
class SemanticIndexScheduler(
    private val refresh: RefreshSemanticIndexUseCase,
    @org.springframework.beans.factory.annotation.Value("\${semantic.indexing.batch-size:100}") batchSize: Int,
) {
    private val batchSize = max(1, batchSize)

    @Scheduled(fixedDelayString = "\${semantic.indexing.interval:60000}")
    fun refreshBatch() {
        runCatching { refresh.refresh(batchSize) }
    }
}
