package dev.skw.adapter.outbound.semantic

import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingProvider
import dev.skw.application.semantic.EmbeddingProviderUnavailable
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableScheduling
class SemanticProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(EmbeddingProvider::class)
    fun unavailableEmbeddingProvider(
        @Value("\${semantic.embedding.profile-id:unconfigured}") profileId: String,
        @Value("\${semantic.embedding.dimensions:1}") dimensions: Int,
    ): EmbeddingProvider = UnavailableEmbeddingProvider(EmbeddingProfile(profileId, dimensions))
}

private class UnavailableEmbeddingProvider(
    private val activeProfile: EmbeddingProfile,
) : EmbeddingProvider {
    override fun profile(): EmbeddingProfile = activeProfile

    override fun embed(documents: List<String>): List<dev.skw.application.semantic.EmbeddingVector> = throw EmbeddingProviderUnavailable()
}
