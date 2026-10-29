package com.tooja

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Scheduling is a separate switch so test contexts never sample into their databases. */
@Configuration @EnableScheduling
@ConditionalOnProperty(name=["app.ops.scheduling-enabled"], havingValue="true", matchIfMissing=true)
class OpsScheduling(private val sampler: InfraSampler, private val retention: OpsRetention) {
    @Scheduled(fixedDelayString="\${app.ops.sample-interval-ms:60000}", initialDelayString="\${app.ops.sample-interval-ms:60000}")
    fun sample() = sampler.sampleOnce()
    @Scheduled(cron="0 10 3 * * *", zone="UTC")
    fun purge() = retention.purgeOnce()
}

@Component
class InfraSampler(private val ops: OpsWriter, private val db: Database, private val registry: MeterRegistry, private val clock: Clock, private val events: SystemEvents,
    @Value("\${app.ops.sample-interval-ms:60000}") private val intervalMs: Long,
    @Value("\${app.ops.collector-interval-ms:60000}") private val collectorIntervalMs: Long,
    @Value("\${app.ops.heartbeat-gap-factor:3}") private val gapFactor: Int,
    @Value("\${app.ops.disk-path:./data}") private val diskPath: String) {
    private var cpuPrevious: Pair<Long, Long>? = null
    private var gcPrevious: Pair<Double, Double>? = null
    @Volatile private var collectorOffline = false

    fun sampleOnce(): List<SampleRow> {
        val ts = clock.millis()
        val rows = ArrayList<SampleRow>()
        fun put(metric: String, value: Double?) { if (value != null && !value.isNaN()) rows.add(SampleRow(ts, "app", metric, value)) }
        put("app.heartbeat", 1.0)
        put("app.uptime_seconds", Duration.between(events.startedAt, clock.instant()).seconds.toDouble())
        safely { jvm(::put) }; safely { hikari(::put) }; safely { http(ts, ::put) }
        safely { host(::put) }; safely { disk(::put) }
        if (db.mysql) safely { mysql(::put) }
        safely { collectorLiveness(ts) }
        rows.forEach(ops::offer)
        return rows
    }
    private inline fun safely(block: () -> Unit) { try { block() } catch (_: Exception) { /* one failing probe never blocks the others */ } }

    private fun jvm(put: (String, Double?) -> Unit) {
        val runtime = Runtime.getRuntime()
        put("jvm.heap.used", (runtime.totalMemory() - runtime.freeMemory()).toDouble()); put("jvm.heap.max", runtime.maxMemory().toDouble())
        put("jvm.threads", Thread.activeCount().toDouble())
        val timers = registry.find("jvm.gc.pause").timers()
        if (timers.isNotEmpty()) {
            val count = timers.sumOf { it.count().toDouble() }; val total = timers.sumOf { it.totalTime(TimeUnit.MILLISECONDS) }
            gcPrevious?.let { (c, t) -> put("jvm.gc.pause.count", count - c); put("jvm.gc.pause.total_ms", total - t) }
            gcPrevious = count to total
        }
    }
    private fun hikari(put: (String, Double?) -> Unit) {
        for ((name, metric) in listOf("hikaricp.connections.active" to "hikari.active", "hikaricp.connections.idle" to "hikari.idle", "hikaricp.connections.pending" to "hikari.pending"))
            registry.find(name).gauge()?.let { put(metric, it.value()) }
    }
    /** Request rate, error rate and latency come from the activity log so they match what the console lists. */
    private fun http(ts: Long, put: (String, Double?) -> Unit) {
        val since = ts - intervalMs
        val rows = db.read { db.jdbc.queryForList("SELECT status,duration_ms FROM activity_log WHERE ts>=? AND ts<?", since, ts) }
        put("http.requests.count", rows.size.toDouble())
        put("http.errors.count", rows.count { (it["status"] as Number).toInt() >= 500 }.toDouble())
        put("http.rate_per_min", rows.size * 60000.0 / intervalMs)
        if (rows.isNotEmpty()) {
            val durations = rows.map { (it["duration_ms"] as Number).toDouble() }.sorted()
            put("http.p95_ms", durations[((durations.size - 1) * 0.95).toInt()])
        }
    }
    private fun host(put: (String, Double?) -> Unit) {
        val stat = Path.of("/proc/stat"); val meminfo = Path.of("/proc/meminfo")
        if (Files.isReadable(stat)) {
            val cpu = Files.readAllLines(stat).first().split(Regex("\\s+")).drop(1).take(8).map(String::toLong)
            val total = cpu.sum(); val idle = cpu[3] + cpu[4]
            cpuPrevious?.let { (t, i) -> if (total > t) put("host.cpu.percent", 100.0 * (1 - (idle - i).toDouble() / (total - t))) }
            cpuPrevious = total to idle
        }
        if (Files.isReadable(meminfo)) {
            val memory = Files.readAllLines(meminfo).associate { line -> line.substringBefore(':') to line.substringAfter(':').trim().split(' ')[0].toLong() }
            val totalKb = memory["MemTotal"]; val availableKb = memory["MemAvailable"]
            if (totalKb != null && availableKb != null && totalKb > 0) put("host.mem.used_percent", 100.0 * (1 - availableKb.toDouble() / totalKb))
        }
    }
    private fun disk(put: (String, Double?) -> Unit) {
        val path = Path.of(diskPath).let { if (Files.exists(it)) it else Path.of(".") }
        val store = Files.getFileStore(path)
        if (store.totalSpace > 0) put("disk.used_percent", 100.0 * (1 - store.usableSpace.toDouble() / store.totalSpace))
    }
    private fun mysql(put: (String, Double?) -> Unit) {
        val start = System.nanoTime()
        val status = db.read { db.jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Uptime','Slow_queries')") }
            .associate { it["Variable_name"].toString() to it["Value"].toString().toDouble() }
        put("mysql.roundtrip_ms", (System.nanoTime() - start) / 1e6)
        put("mysql.threads_connected", status["Threads_connected"]); put("mysql.threads_running", status["Threads_running"])
        put("mysql.uptime_seconds", status["Uptime"]); put("mysql.slow_queries", status["Slow_queries"])
    }
    private fun collectorLiveness(ts: Long) {
        val last = db.maxTs("SELECT MAX(ts) FROM infra_samples WHERE source='host'") ?: return
        val offline = ts - last > collectorIntervalMs * gapFactor
        if (offline && !collectorOffline) events.record("collector_offline", mapOf("lastSample" to java.time.Instant.ofEpochMilli(last).toString()))
        if (!offline && collectorOffline) events.record("collector_online", mapOf("lastSample" to java.time.Instant.ofEpochMilli(last).toString()))
        collectorOffline = offline
    }
}

@Component
class OpsRetention(private val ops: OpsWriter, private val db: Database, private val clock: Clock,
    @Value("\${app.ops.activity-retention-days:90}") private val activityDays: Long,
    @Value("\${app.ops.samples-retention-days:30}") private val sampleDays: Long,
    @Value("\${app.ops.events-retention-days:365}") private val eventDays: Long) {
    fun purgeOnce() {
        val now = clock.instant()
        ops.offer(PurgeRow(now.minus(Duration.ofDays(activityDays)).toEpochMilli(), now.minus(Duration.ofDays(sampleDays)).toEpochMilli(), now.minus(Duration.ofDays(eventDays)).toEpochMilli()))
        db.sideWrite {
            db.jdbc.update("DELETE FROM owner_sessions WHERE expires_at<? OR revoked_at IS NOT NULL", now.toString())
            db.jdbc.update("DELETE FROM contexts WHERE expires_at<?", now.toString()); true
        }
    }
}
