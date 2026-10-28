package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class IngestSample(val ts: Long, @field:Pattern(regexp="^(host|oci)$") val source: String,
    @field:Pattern(regexp="^[a-z0-9_.]{1,64}$") val metric: String, val value: Double, val dims: Map<String, String>? = null)
data class IngestEvent(val ts: Long, @field:Pattern(regexp="^[a-z_]{1,32}$") val type: String, val detail: Map<String, Any?>? = null)
data class IngestPayload(@field:Size(max=64) val collector: String, val sentAt: String? = null,
    @field:Valid @field:Size(max=500) val samples: List<IngestSample> = emptyList(),
    @field:Valid @field:Size(max=50) val events: List<IngestEvent> = emptyList())
data class IngestResult(val accepted: Int, val dropped: Int)

/** Host collector hand-off. Outside /api/v1 and gated by InternalEndpointsFilter (bridge address and shared token). */
@Hidden @RestController
class IngestController(private val ops: OpsWriter, private val mapper: ObjectMapper) {
    @PostMapping("/internal/ingest", consumes=["application/json"], produces=["application/json"])
    fun ingest(@Valid @RequestBody payload: IngestPayload): ResponseEntity<IngestResult> {
        var accepted = 0; var dropped = 0
        for (s in payload.samples) {
            if (s.value.isNaN() || s.value.isInfinite()) { dropped++; continue }
            val dims = s.dims?.takeIf { it.isNotEmpty() }?.let(mapper::writeValueAsString)
            if (dims != null && dims.length > 255) { dropped++; continue }
            if (ops.offer(SampleRow(s.ts, s.source, s.metric, s.value, dims))) accepted++ else dropped++
        }
        for (e in payload.events) {
            val detail = e.detail?.takeIf { it.isNotEmpty() }?.let(mapper::writeValueAsString)?.take(4000)
            if (ops.offer(EventRow(e.ts, e.type, detail))) accepted++ else dropped++
        }
        return ResponseEntity.accepted().body(IngestResult(accepted, dropped))
    }
}
