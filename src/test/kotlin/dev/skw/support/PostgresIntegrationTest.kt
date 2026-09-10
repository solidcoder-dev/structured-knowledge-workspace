package dev.skw.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
abstract class PostgresIntegrationTest {
    private class TestPostgres : PostgreSQLContainer<TestPostgres>("postgres:17.11")

    companion object {
        // One database per test JVM. Testcontainers/Ryuk removes it at JVM shutdown.
        // Docker is required; these tests must fail rather than silently skip.
        private val database by lazy {
            TestPostgres().apply {
                withDatabaseName("skw_test")
                start()
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.jdbcUrl }
            registry.add("spring.datasource.username") { database.username }
            registry.add("spring.datasource.password") { database.password }
        }
    }
}
