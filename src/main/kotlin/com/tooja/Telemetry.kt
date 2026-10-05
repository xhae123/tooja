package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.sql.SQLException

/** Metrics are scraped from the host over the private Docker bridge, never through public nginx. */
@Component
@org.springframework.core.annotation.Order(-90)
class InternalMetricsFilter(@Value("\${app.metrics-host-address:172.19.0.1}") private val hostAddress: String = "172.19.0.1"): OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest,res: HttpServletResponse,chain: FilterChain) {
        if ((req.requestURI == "/actuator" || req.requestURI.startsWith("/actuator/metrics")) &&
            req.remoteAddr !in setOf("127.0.0.1","::1",hostAddress)) {
            res.status = 404
            return
        }
        chain.doFilter(req,res)
    }
}

@Component
class Telemetry(private val registry: MeterRegistry, private val mapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger("com.tooja.telemetry")
    fun error(e: Exception,req: HttpServletRequest,status: Int,code: String) {
        registry.counter("tooja.server.errors","status",status.toString(),"code",code).increment()
        val sql = generateSequence<Throwable>(e) { it.cause }.take(16).filterIsInstance<SQLException>().firstOrNull()
        // Never log exception messages/SQL/parameters/cookies/codes or raw request paths.
        val event = linkedMapOf<String,Any>("event" to "server_error", "status" to status,
            "code" to code, "requestId" to (req.getAttribute("requestId")?.toString() ?: "unknown"),
            "method" to req.method, "route" to (req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: "unmatched"),
            "exception" to e.javaClass.simpleName)
        sql?.let { event["sqlErrorCode"] = it.errorCode }
        log.error(mapper.writeValueAsString(event))
    }
    fun <T> database(operation: String,block: () -> T): T {
        val sample = Timer.start(registry)
        var outcome = "success"
        try { return block() }
        catch (e: Exception) { outcome="failure"; throw e }
        finally { sample.stop(registry.timer("tooja.database.operations","operation",operation,"outcome",outcome)) }
    }
}
