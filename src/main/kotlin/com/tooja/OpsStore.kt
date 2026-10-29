package com.tooja

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Operational records are best effort: they never block or fail a business request. */
sealed interface OpsRow
data class ActivityRow(val ts: Long, val requestId: String, val method: String, val route: String, val status: Int, val durationMs: Int,
    val actorRole: String?, val accountId: String?, val action: String, val errorCode: String?,
    val teamId: Int?, val investmentId: String?, val amount: Int?,
    val origin: String?, val clientIp: String, val remoteAddr: String, val userAgent: String?): OpsRow
data class SampleRow(val ts: Long, val source: String, val metric: String, val value: Double, val dims: String? = null): OpsRow
data class EventRow(val ts: Long, val type: String, val detail: String? = null): OpsRow
data class PurgeRow(val activityBefore: Long, val samplesBefore: Long, val eventsBefore: Long): OpsRow

@Component
class OpsWriter(private val db: Database, registry: MeterRegistry,
    @Value("\${app.ops.queue-capacity:10000}") capacity: Int = 10000) {
    private val log = LoggerFactory.getLogger("com.tooja.ops")
    private val queue = LinkedBlockingQueue<OpsRow>(capacity)
    private val mutex = Any()
    private val accepted = AtomicLong(); private val written = AtomicLong(); private val failed = AtomicLong()
    private val droppedCounter = registry.counter("tooja.ops.dropped")
    private val failureCounter = registry.counter("tooja.ops.write_failures")
    private var lastWarning = 0L
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "ops-writer").apply { isDaemon = true; start() }

    fun offer(row: OpsRow): Boolean {
        if (queue.offer(row)) { accepted.incrementAndGet(); return true }
        droppedCounter.increment(); return false
    }
    fun dropped(): Long = droppedCounter.count().toLong()
    fun writeFailures(): Long = failureCounter.count().toLong()
    fun queueSize(): Int = queue.size

    /** Drains on the caller thread and waits for any batch the writer already took. */
    fun flush(timeout: Duration = Duration.ofSeconds(5)) {
        val target = accepted.get()
        synchronized(mutex) { drainAndWrite() }
        val deadline = System.nanoTime() + timeout.toNanos()
        while (written.get() + failed.get() < target && System.nanoTime() < deadline) Thread.sleep(5)
    }

    private fun loop() {
        while (running) {
            try {
                val first = queue.poll(1, TimeUnit.SECONDS) ?: continue
                synchronized(mutex) { write(mutableListOf(first).also { queue.drainTo(it, 498) }) }
            } catch (_: InterruptedException) { return }
            catch (e: Exception) { warn("writer_loop", e) }
        }
    }
    private fun drainAndWrite() {
        while (true) { val batch = ArrayList<OpsRow>(499); queue.drainTo(batch, 499); if (batch.isEmpty()) return; write(batch) }
    }
    private fun write(batch: List<OpsRow>) {
        var delay = 200L
        for (attempt in 1..3) {
            try { db.sideWrite { persist(batch); true }; written.addAndGet(batch.size.toLong()); return }
            catch (e: DataAccessException) { if (attempt == 3) { failureCounter.increment(); failed.addAndGet(batch.size.toLong()); warn("write_failed", e); return }; Thread.sleep(delay); delay *= 10 }
        }
    }
    private fun persist(batch: List<OpsRow>) {
        val jdbc = db.jdbc
        batch.filterIsInstance<ActivityRow>().takeIf { it.isNotEmpty() }?.let { rows ->
            jdbc.batchUpdate("INSERT INTO activity_log(ts,request_id,method,route,status,duration_ms,actor_role,account_id,action,error_code,team_id,investment_id,amount,origin,client_ip,remote_addr,user_agent) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                rows.map { arrayOf<Any?>(it.ts,it.requestId,it.method,it.route,it.status,it.durationMs,it.actorRole,it.accountId,it.action,it.errorCode,it.teamId,it.investmentId,it.amount,it.origin,it.clientIp,it.remoteAddr,it.userAgent) })
        }
        batch.filterIsInstance<SampleRow>().takeIf { it.isNotEmpty() }?.let { rows ->
            jdbc.batchUpdate("INSERT INTO infra_samples(ts,source,metric,sample_value,dims) VALUES(?,?,?,?,?)", rows.map { arrayOf<Any?>(it.ts,it.source,it.metric,it.value,it.dims) })
        }
        batch.filterIsInstance<EventRow>().takeIf { it.isNotEmpty() }?.let { rows ->
            jdbc.batchUpdate("INSERT INTO system_events(ts,type,detail) VALUES(?,?,?)", rows.map { arrayOf<Any?>(it.ts,it.type,it.detail) })
        }
        batch.filterIsInstance<PurgeRow>().forEach { purge ->
            purgeTable("activity_log", purge.activityBefore); purgeTable("infra_samples", purge.samplesBefore); purgeTable("system_events", purge.eventsBefore)
        }
    }
    // MySQL allows LIMIT on DELETE but not inside IN subqueries; SQLite is the reverse.
    private fun purgeTable(table: String, before: Long) {
        val sql = if (db.mysql) "DELETE FROM $table WHERE ts<? LIMIT 5000" else "DELETE FROM $table WHERE id IN (SELECT id FROM $table WHERE ts<? LIMIT 5000)"
        while (db.jdbc.update(sql, before) == 5000) { /* keep deleting in bounded chunks */ }
    }
    private fun warn(event: String, e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastWarning < 60000) return
        lastWarning = now
        // No row contents, SQL or exception messages: only the failure class.
        log.warn("{\"event\":\"ops_$event\",\"exception\":\"${e.javaClass.simpleName}\",\"queue\":${queue.size}}")
    }
    @PreDestroy fun shutdown() {
        running = false
        try { flush(Duration.ofSeconds(3)) } catch (e: Exception) { warn("shutdown", e) }
        thread.interrupt()
    }
}
