package dev.skw.adapter.inbound.rest

import dev.skw.application.accesscontrol.MissingPrincipal
import dev.skw.domain.accesscontrol.PrincipalId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** Temporary integration seam; X-Principal-Id is an identity input, not authentication. */
class PrincipalResolver(
    private val request: HttpServletRequest? = null,
    private val fixed: PrincipalId? = null,
) {
    constructor(principal: PrincipalId) : this(null, principal)

    fun resolve(): PrincipalId {
        val current = request ?: (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        return current?.getHeader("X-Principal-Id")?.let(::PrincipalId) ?: fixed ?: throw MissingPrincipal()
    }
}
