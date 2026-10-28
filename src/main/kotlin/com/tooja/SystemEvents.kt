package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Aggregates such as MAX(ts) may be NULL; read helpers require a non-null block result, so go through a list. */
internal fun Database.maxTs(sql: String, vararg args: Any): Long? = read { jdbc.queryForList(sql, Long::class.javaObjectType, *args) }.firstOrNull()

object DowntimeDetector {
    /** A heartbeat older than interval × factor means the app (or its sampler) was not running in between. */
    fun detect(lastHeartbeat: Instant?, now: Instant, interval: Duration, factor: Int): Duration? {
        if (lastHeartbeat == null) return null
        val gap = Duration.between(lastHeartbeat, now)
        return if (gap > interval.multipliedBy(factor.toLong())) gap else null
    }
}

@Component
class SystemEvents(private val ops: OpsWriter, private val db: Database, private val clock: Clock, private val env: Environment, private val mapper: ObjectMapper,
    @Value("\${app.report-root:./reports}") private val reportRoot: String,
    @Value("\${app.ops.sample-interval-ms:60000}") private val intervalMs: Long,
    @Value("\${app.ops.heartbeat-gap-factor:3}") private val gapFactor: Int) {
    private val lastDbEvent = AtomicLong()
    val startedAt: Instant = clock.instant()

    fun record(type: String, detail: Map<String, Any?> = emptyMap()) {
        ops.offer(EventRow(clock.millis(), type, if (detail.isEmpty()) null else mapper.writeValueAsString(detail)))
    }
    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() { onStartup(clock.instant()) }
    fun onStartup(now: Instant) {
        val last = db.maxTs("SELECT MAX(ts) FROM infra_samples WHERE metric='app.heartbeat' AND source='app'")
        DowntimeDetector.detect(last?.let { Instant.ofEpochMilli(it) }, now, Duration.ofMillis(intervalMs), gapFactor)?.let { gap ->
            record("downtime_detected", mapOf("from" to Instant.ofEpochMilli(last!!).toString(), "to" to now.toString(), "gapSeconds" to gap.seconds))
        }
        record("app_started", mapOf("commit" to deployedCommit(), "profiles" to env.activeProfiles.joinToString(","), "java" to System.getProperty("java.version")))
    }
    fun deployedCommit(): String? = try {
        val file = Path.of(reportRoot).resolve("deployment.json")
        if (Files.isRegularFile(file)) mapper.readTree(Files.readString(file)).get("commit")?.asText() else null
    } catch (_: Exception) { null }
    /** One event per minute at most: a flapping database must not flood the event table. */
    fun dbUnreachable(requestId: String?) {
        val now = clock.millis(); val previous = lastDbEvent.get()
        if (now - previous < 60000 || !lastDbEvent.compareAndSet(previous, now)) return
        record("db_unreachable", mapOf("requestId" to requestId))
    }
    @PreDestroy fun onShutdown() { record("app_stopping", mapOf("uptimeSeconds" to Duration.between(startedAt, clock.instant()).seconds)); ops.flush() }
}
