package dev.skw

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class StructuredKnowledgeWorkspaceApplication

fun main(args: Array<String>) {
    runApplication<StructuredKnowledgeWorkspaceApplication>(*args)
}
