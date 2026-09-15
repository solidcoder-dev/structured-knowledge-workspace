package dev.skw.adapter.outbound.persistence

import dev.skw.application.port.out.TransactionRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class SpringTransactionRunner(
    private val transactionTemplate: TransactionTemplate,
) : TransactionRunner {
    override fun <T> inTransaction(action: () -> T): T = transactionTemplate.execute { action() } ?: error("Transaction returned null")
}
