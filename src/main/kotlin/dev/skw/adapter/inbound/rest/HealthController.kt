package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.api.SystemApi
import dev.skw.adapter.inbound.rest.generated.model.HealthResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController : SystemApi {

    override fun getHealth(): ResponseEntity<HealthResponse> =
        ResponseEntity.ok(HealthResponse(status = HealthResponse.Status.UP))
}
