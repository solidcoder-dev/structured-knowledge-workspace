package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.api.TransactionsApi
import dev.skw.adapter.inbound.rest.generated.model.TransactionRequest
import dev.skw.adapter.inbound.rest.generated.model.TransactionResponse
import dev.skw.application.transaction.ExecuteTransactionUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class TransactionController(
    private val executeTransaction: ExecuteTransactionUseCase,
    private val mapper: TransactionRestMapper,
) : TransactionsApi {
    override fun executeTransaction(
        workspaceId: UUID,
        idempotencyKey: String,
        transactionRequest: TransactionRequest,
    ): ResponseEntity<TransactionResponse> =
        ResponseEntity.ok(executeTransaction.execute(mapper.toCommand(workspaceId, transactionRequest)).let(mapper::toResponse))
}
