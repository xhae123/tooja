package com.tooja

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Rendering helpers exposed to the owner templates as `fmt`. Times display in Seoul; raw ISO stays in title attributes. */
object OwnerFormat {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val short = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.KOREA).withZone(seoul)
    private val full = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA).withZone(seoul)
    fun time(ms: Long?): String = ms?.let { short.format(Instant.ofEpochMilli(it)) } ?: "-"
    fun timeFull(ms: Long?): String = ms?.let { full.format(Instant.ofEpochMilli(it)) } ?: "-"
    fun iso(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).toString() } ?: ""
    fun isoTime(value: Any?): String = value?.toString()?.let { runCatching { full.format(Instant.parse(it)) }.getOrDefault(it) } ?: "-"
    fun won(value: Any?): String = (value as? Number)?.let { "%,d원".format(it.toLong()) } ?: "-"
    fun num(value: Any?): String = when (val n = value as? Number) { null -> "-"; else -> if (n.toDouble() % 1.0 == 0.0) "%,d".format(n.toLong()) else "%,.1f".format(n.toDouble()) }
    fun pct(value: Any?): String = (value as? Number)?.let { "%.1f%%".format(it.toDouble()) } ?: "-"
    fun bytes(value: Any?): String { val b = (value as? Number)?.toDouble() ?: return "-"; return if (b >= 1e9) "%.2f GB".format(b / 1e9) else if (b >= 1e6) "%.1f MB".format(b / 1e6) else "%.0f KB".format(b / 1e3) }
    /** One formatter per metric family so tiles, captions and tables agree. */
    fun metric(name: String, value: Any?): String = when {
        name.endsWith("percent") || name.endsWith("availability") -> pct(value)
        name.contains("heap") || name.contains("bytes") -> bytes(value)
        else -> num(value)
    }
    fun ago(ms: Long?, now: Long): String { ms ?: return "-"; val s = (now - ms) / 1000; return when { s < 60 -> "${s}초 전"; s < 3600 -> "${s / 60}분 전"; s < 86400 -> "${s / 3600}시간 전"; else -> "${s / 86400}일 전" } }
    fun duration(seconds: Long?): String { seconds ?: return "-"; return when { seconds < 60 -> "${seconds}초"; seconds < 3600 -> "${seconds / 60}분 ${seconds % 60}초"; else -> "${seconds / 3600}시간 ${(seconds % 3600) / 60}분" } }
}

data class Page<T>(val items: List<T>, val nextCursor: Long?)
data class ChartPoint(val ts: Long, val avg: Double, val min: Double, val max: Double)
data class Marker(val x: String, val y: String)
data class Chart(val source: String, val metric: String, val points: List<ChartPoint>, val path: String, val area: String, val markers: List<Marker>, val minValue: Double, val maxValue: Double, val latest: Double?, val width: Int, val height: Int, val ticks: List<Pair<Int, String>>)

@Service
class OwnerQueries(private val db: Database, private val clock: Clock, private val ops: OpsWriter, private val events: SystemEvents) {
    private val errorCodes = listOf("SERVICE_UNAVAILABLE", "INTERNAL_ERROR")

    fun overview(): Map<String, Any?> = db.snapshot {
        val now = clock.millis(); val nowIso = clock.instant().toString()
        val m = HashMap<String, Any?>()
        m["accounts"] = db.jdbc.queryForList("SELECT role,kind,COUNT(*) AS n,COALESCE(SUM(balance),0) AS balance FROM accounts WHERE deleted_at IS NULL GROUP BY role,kind ORDER BY role,kind")
        m["teams"] = db.jdbc.queryForObject("SELECT COUNT(*) FROM teams", Int::class.java)
        m["investments"] = db.jdbc.queryForMap("SELECT COUNT(*) AS n,COALESCE(SUM(amount),0) AS amount,COUNT(DISTINCT account_id) AS investors FROM investments")
        m["rejected"] = db.jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE status='REJECTED'", Int::class.java)
        m["control"] = db.jdbc.queryForMap("SELECT status,revision,updated_at FROM investment_control WHERE id=1")
        m["last5m"] = window(now - 300_000, now); m["last1h"] = window(now - 3_600_000, now)
        m["sessions"] = db.jdbc.queryForList("SELECT role,account_id,expires_at,revoked_at FROM sessions").filter { active(it, nowIso) }.groupingBy { it["role"].toString() }.eachCount()
        m["ownerSessions"] = db.jdbc.queryForList("SELECT expires_at,revoked_at FROM owner_sessions").count { active(it, nowIso) }
        m["heartbeat"] = db.jdbc.queryForObject("SELECT MAX(ts) FROM infra_samples WHERE metric='app.heartbeat'", Long::class.javaObjectType)
        m["hostSample"] = db.jdbc.queryForObject("SELECT MAX(ts) FROM infra_samples WHERE source='host'", Long::class.javaObjectType)
        m["ociSample"] = db.jdbc.queryForObject("SELECT MAX(ts) FROM infra_samples WHERE source='oci'", Long::class.javaObjectType)
        m["latest"] = latest()
        m["downtime"] = db.jdbc.queryForList("SELECT ts,detail FROM system_events WHERE type='downtime_detected' ORDER BY id DESC LIMIT 1").firstOrNull()
        m["recentEvents"] = db.jdbc.queryForList("SELECT ts,type,detail FROM system_events ORDER BY id DESC LIMIT 8")
        m["startedAt"] = events.startedAt.toEpochMilli(); m["commit"] = events.deployedCommit()
        m["queue"] = mapOf("size" to ops.queueSize(), "dropped" to ops.dropped(), "failures" to ops.writeFailures())
        m["now"] = now; m["mysql"] = db.mysql
        m
    }
    private fun active(row: Map<String, Any?>, nowIso: String) = row["revoked_at"] == null && row["expires_at"].toString() > nowIso
    private fun window(from: Long, to: Long): Map<String, Any?> {
        val rows = db.jdbc.queryForList("SELECT status,duration_ms,account_id FROM activity_log WHERE ts>=? AND ts<?", from, to)
        val durations = rows.map { (it["duration_ms"] as Number).toInt() }.sorted()
        return mapOf("requests" to rows.size, "errors" to rows.count { (it["status"] as Number).toInt() >= 500 }, "clientErrors" to rows.count { (it["status"] as Number).toInt() in 400..499 },
            "actors" to rows.mapNotNull { it["account_id"] }.distinct().size, "p95" to durations.getOrNull(((durations.size - 1) * 0.95).toInt()),
            "ratePerMin" to if (to > from) rows.size * 60000.0 / (to - from) else 0.0)
    }
    /** Latest value per source+metric: the overview tiles and the chart list both read this. */
    fun latest(): List<Map<String, Any?>> = db.jdbc.queryForList("SELECT s.source,s.metric,s.sample_value,s.ts,s.dims FROM infra_samples s JOIN (SELECT MAX(id) AS id FROM infra_samples GROUP BY source,metric) m ON m.id=s.id ORDER BY s.source,s.metric")

    fun activity(f: Map<String, String?>, cursor: Long?, limit: Int): Page<Map<String, Any?>> = db.read {
        val where = ArrayList<String>(); val args = ArrayList<Any>()
        fun add(sql: String, value: Any?) { if (value != null) { where.add(sql); args.add(value) } }
        add("ts>=?", parseTime(f["from"])); add("ts<=?", parseTime(f["to"]))
        add("action=?", f["action"]?.takeIf(String::isNotBlank)); add("actor_role=?", f["role"]?.takeIf(String::isNotBlank)?.uppercase())
        add("account_id=?", f["accountId"]?.takeIf(String::isNotBlank)); add("status=?", f["status"]?.toIntOrNull())
        add("route LIKE ?", f["route"]?.takeIf(String::isNotBlank)?.let { "$it%" }); add("client_ip=?", f["ip"]?.takeIf(String::isNotBlank))
        add("error_code=?", f["errorCode"]?.takeIf(String::isNotBlank)); add("id<?", cursor)
        page("SELECT * FROM activity_log", where, args, limit)
    }
    fun errors(f: Map<String, String?>, cursor: Long?, limit: Int): Page<Map<String, Any?>> = db.read {
        val where = arrayListOf("(status>=500 OR error_code IN ('SERVICE_UNAVAILABLE','INTERNAL_ERROR'))"); val args = ArrayList<Any>()
        parseTime(f["from"])?.let { where.add("ts>=?"); args.add(it) }; parseTime(f["to"])?.let { where.add("ts<=?"); args.add(it) }
        f["code"]?.takeIf(String::isNotBlank)?.let { where.add("error_code=?"); args.add(it) }; cursor?.let { where.add("id<?"); args.add(it) }
        page("SELECT * FROM activity_log", where, args, limit)
    }
    fun systemEvents(type: String?, cursor: Long?, limit: Int): Page<Map<String, Any?>> = db.read {
        val where = ArrayList<String>(); val args = ArrayList<Any>()
        type?.takeIf(String::isNotBlank)?.let { where.add("type=?"); args.add(it) }; cursor?.let { where.add("id<?"); args.add(it) }
        page("SELECT * FROM system_events", where, args, limit)
    }
    private fun page(select: String, where: List<String>, args: List<Any>, limit: Int): Page<Map<String, Any?>> {
        val sql = select + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY id DESC LIMIT ${limit + 1}"
        val rows = db.jdbc.queryForList(sql, *args.toTypedArray())
        val items = rows.take(limit)
        return Page(items, if (rows.size > limit) (items.last()["id"] as Number).toLong() else null)
    }
    fun actions(): List<String> = db.read { db.jdbc.queryForList("SELECT DISTINCT action FROM activity_log ORDER BY action", String::class.java) }

    fun accounts(f: Map<String, String?>): List<Map<String, Any?>> = db.snapshot {
        val nowIso = clock.instant().toString()
        // Never select code_hash: the console shows who exists, never how they log in.
        val rows = db.jdbc.queryForList("""SELECT a.id,a.alias,a.role,a.kind,a.team_id,t.name AS team_name,a.balance,a.deleted_at,g.granted_at,
            (SELECT COUNT(*) FROM investments i WHERE i.account_id=a.id) AS invest_count,
            (SELECT COALESCE(SUM(i.amount),0) FROM investments i WHERE i.account_id=a.id) AS invested,
            (SELECT COUNT(*) FROM requests r WHERE r.account_id=a.id AND r.status='REJECTED') AS rejected,
            (SELECT MAX(l.ts) FROM activity_log l WHERE l.account_id=a.id AND l.action='login_success') AS last_login,
            (SELECT MAX(l.ts) FROM activity_log l WHERE l.account_id=a.id) AS last_seen,
            (SELECT COUNT(*) FROM activity_log l WHERE l.account_id=a.id) AS requests,
            (SELECT COUNT(*) FROM sessions s WHERE s.account_id=a.id AND s.revoked_at IS NULL AND s.expires_at>?) AS active_sessions
            FROM accounts a LEFT JOIN teams t ON t.id=a.team_id LEFT JOIN grants g ON g.account_id=a.id ORDER BY a.role,a.kind,a.team_id,a.id""", nowIso)
        val q = f["q"]?.trim()?.takeIf(String::isNotEmpty)?.lowercase(); val team = f["teamId"]?.toIntOrNull()
        rows.filter { r ->
            (f["role"].isNullOrBlank() || r["role"] == f["role"]) && (f["kind"].isNullOrBlank() || r["kind"] == f["kind"]) && (team == null || (r["team_id"] as Number?)?.toInt() == team) &&
            (q == null || r["alias"].toString().lowercase().contains(q) || r["id"].toString().lowercase().contains(q)) &&
            (f["spendingStatus"].isNullOrBlank() || (f["spendingStatus"] == "COMPLETED") == ((r["balance"] as Number).toInt() == 0))
        }
    }
    fun account(id: String): Map<String, Any?>? = db.snapshot {
        val row = db.jdbc.queryForList("SELECT a.id,a.alias,a.role,a.kind,a.team_id,t.name AS team_name,a.balance,a.deleted_at,g.granted_at,g.amount AS granted FROM accounts a LEFT JOIN teams t ON t.id=a.team_id LEFT JOIN grants g ON g.account_id=a.id WHERE a.id=?", id).firstOrNull() ?: return@snapshot emptyMap<String, Any?>()
        mapOf("account" to row,
            "sessions" to db.jdbc.queryForList("SELECT token_hash,role,expires_at,revoked_at FROM sessions WHERE account_id=? ORDER BY expires_at DESC LIMIT 50", id),
            "investments" to db.jdbc.queryForList("SELECT i.id,i.team_id,t.name,i.amount,i.confirmed_at,i.balance_after,i.version FROM investments i JOIN teams t ON t.id=i.team_id WHERE i.account_id=? ORDER BY i.confirmed_at DESC", id),
            "requests" to db.jdbc.queryForList("SELECT request_key,team_id,amount,status,investment_id,http_status,error_code FROM requests WHERE account_id=? ORDER BY request_key", id),
            "activity" to db.jdbc.queryForList("SELECT * FROM activity_log WHERE account_id=? ORDER BY id DESC LIMIT 50", id),
            "now" to clock.millis())
    }
    fun sessions(role: String?, activeOnly: Boolean): List<Map<String, Any?>> = db.snapshot {
        val nowIso = clock.instant().toString()
        val rows = db.jdbc.queryForList("SELECT s.token_hash,s.role,s.account_id,a.alias,s.expires_at,s.revoked_at,NULL AS client_ip,NULL AS user_agent,NULL AS created_at FROM sessions s LEFT JOIN accounts a ON a.id=s.account_id") +
            db.jdbc.queryForList("SELECT token_hash,'OWNER' AS role,'owner' AS account_id,'조물주' AS alias,expires_at,revoked_at,client_ip,user_agent,created_at FROM owner_sessions")
        rows.map { it + ("active" to active(it, nowIso)) }
            .filter { (role.isNullOrBlank() || it["role"] == role) && (!activeOnly || it["active"] == true) }
            .sortedByDescending { it["expires_at"].toString() }
    }
    fun investments(f: Map<String, String?>): Map<String, Any?> = db.snapshot {
        val team = f["teamId"]?.toIntOrNull(); val account = f["accountId"]?.takeIf(String::isNotBlank)
        val where = ArrayList<String>(); val args = ArrayList<Any>()
        team?.let { where.add("i.team_id=?"); args.add(it) }; account?.let { where.add("i.account_id=?"); args.add(it) }
        val clause = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val succeeded = if (f["status"] == "REJECTED") emptyList() else db.jdbc.queryForList("SELECT i.id,i.account_id,a.alias,a.kind,i.team_id,t.name,i.amount,i.confirmed_at,i.balance_after,i.version FROM investments i JOIN teams t ON t.id=i.team_id JOIN accounts a ON a.id=i.account_id$clause ORDER BY i.confirmed_at DESC,i.version DESC LIMIT 500", *args.toTypedArray())
        val rejectedClause = clause.replace("i.", "r.") + (if (where.isEmpty()) " WHERE " else " AND ") + "r.status='REJECTED'"
        val rejected = if (f["status"] == "SUCCEEDED") emptyList() else db.jdbc.queryForList("SELECT r.account_id,a.alias,r.team_id,t.name,r.amount,r.http_status,r.error_code,r.error_message,r.request_key FROM requests r JOIN accounts a ON a.id=r.account_id LEFT JOIN teams t ON t.id=r.team_id$rejectedClause ORDER BY r.account_id,r.request_key LIMIT 500", *args.toTypedArray())
        val teams = db.jdbc.queryForList("SELECT t.id,t.name,COALESCE(SUM(i.amount),0) AS amount,COUNT(i.id) AS n FROM teams t LEFT JOIN investments i ON i.team_id=t.id GROUP BY t.id,t.name ORDER BY amount DESC,t.id")
        mapOf("succeeded" to succeeded, "rejected" to rejected, "teams" to teams)
    }

    fun metrics(): List<Map<String, Any?>> = latest()
    /** Server-side bucket aggregate drawn as one SVG path; `ts - ts % step` works on both engines without FLOOR. */
    fun chart(source: String, metric: String, rangeSeconds: Long, width: Int = 640, height: Int = 160): Chart = db.read {
        val step = when { rangeSeconds <= 3600 -> 60L; rangeSeconds <= 6 * 3600 -> 300L; rangeSeconds <= 86400 -> 900L; else -> 3600L } * 1000
        val now = clock.millis(); val from = now - rangeSeconds * 1000
        val rows = db.jdbc.queryForList("SELECT (ts - (ts % ?)) AS bucket,AVG(sample_value) AS avg_value,MIN(sample_value) AS min_value,MAX(sample_value) AS max_value FROM infra_samples WHERE source=? AND metric=? AND ts>=? GROUP BY (ts - (ts % ?)) ORDER BY bucket", step, source, metric, from, step)
        val points = rows.map { ChartPoint((it["bucket"] as Number).toLong(), (it["avg_value"] as Number).toDouble(), (it["min_value"] as Number).toDouble(), (it["max_value"] as Number).toDouble()) }
        val lo = minOf(0.0, points.minOfOrNull { it.min } ?: 0.0); val hi = (points.maxOfOrNull { it.max } ?: 1.0).let { if (it <= lo) lo + 1.0 else it }
        val pad = 8.0; val plotW = width - 2 * pad; val plotH = height - 2 * pad
        fun x(ts: Long) = pad + plotW * (ts - from).toDouble() / (now - from)
        fun y(v: Double) = pad + plotH * (1 - (v - lo) / (hi - lo))
        val path = points.mapIndexed { i, p -> "${if (i == 0) "M" else "L"}${"%.1f".format(x(p.ts))} ${"%.1f".format(y(p.avg))}" }.joinToString(" ")
        val area = if (points.isEmpty()) "" else path + " L${"%.1f".format(x(points.last().ts))} ${"%.1f".format(y(lo))} L${"%.1f".format(x(points.first().ts))} ${"%.1f".format(y(lo))} Z"
        val ticks = (0..4).map { i -> val ts = from + (now - from) * i / 4; (x(ts)).toInt() to OwnerFormat.time(ts).substring(if (rangeSeconds > 86400) 0 else 6).removeSuffix(":00") }
        // Sparse series (a fresh deployment, a short range) would otherwise draw an invisible one-point path.
        val markers = if (points.size <= 40) points.map { Marker("%.1f".format(x(it.ts)), "%.1f".format(y(it.avg))) } else emptyList()
        Chart(source, metric, points, path, area, markers, lo, hi, points.lastOrNull()?.avg, width, height, ticks)
    }

    /** Filters arrive from a datetime-local input (Seoul) or as ISO instants. */
    fun parseTime(value: String?): Long? {
        val v = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(v).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() }.getOrNull()
    }
}
