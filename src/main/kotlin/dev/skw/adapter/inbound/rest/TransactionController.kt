package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.model.TransactionResponse
import dev.skw.application.idempotency.IdempotencyScope
import dev.skw.application.transaction.ExecuteTransactionUseCase
import dev.skw.domain.accesscontrol.PrincipalId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class TransactionController(
    private val executeTransaction: ExecuteTransactionUseCase,
    private val mapper: TransactionRestMapper,
    private val idempotent: IdempotentRestExecutor,
) {
    @PostMapping("/api/v1/workspaces/{workspaceId}/transactions", consumes = ["application/json"], produces = ["application/json"])
    fun executeTransaction(
        @PathVariable workspaceId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-Principal-Id", required = false) principalId: String?,
        @RequestBody transactionRequest: TransactionRequestDto,
    ): ResponseEntity<TransactionResponse> =
        idempotent.execute(
            IdempotencyScope("POST", "/api/v1/workspaces/{workspaceId}/transactions", workspaceId.toString(), principalId),
            idempotencyKey,
            transactionRequest,
            TransactionResponse::class.java,
        ) {
            ResponseEntity.ok(
                executeTransaction
                    .execute(
                        mapper.toCommand(workspaceId, transactionRequest).copy(principal = principalId?.let(::PrincipalId)),
                    ).let(mapper::toResponse),
            )
        }
}
