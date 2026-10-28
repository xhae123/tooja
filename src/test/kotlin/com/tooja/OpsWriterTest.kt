package com.tooja

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path

@DisplayName("단위 테스트 · 운영 기록 writer의 유계 큐·배치·보존")
class OpsWriterTest {
    @TempDir lateinit var directory: Path
    private fun database(): Database {
        val source = DriverManagerDataSource("jdbc:sqlite:${directory.resolve("ops.db")}").apply { setDriverClassName("org.sqlite.JDBC") }
        return Database(JdbcTemplate(source), DataSourceTransactionManager(source), Secrets("ops-test-pepper-0123"), MockEnvironment().also { it.setActiveProfiles("production") }, "manual", "", "").also { it.initialize() }
    }
    private fun row(ts: Long, action: String = "view_me") = ActivityRow(ts, "req_$ts", "GET", "/api/v1/investor/me", 200, 3, "INVESTOR", "inv_001", action, null, null, null, null, "http://localhost:8080", "203.0.113.9", "127.0.0.1", "UA")

    @Test @DisplayName("Given 용량 3 When 5건을 한꺼번에 넣으면 Then 넘치는 만큼 버리고 집계하며 받은 것만 저장된다")
    fun boundedQueue() {
        val db = database(); val registry = SimpleMeterRegistry()
        val writer = OpsWriter(db, registry, 3)
        try {
            // The writer thread may already have taken one row, so between 3 and 4 fit; the invariant is what matters.
            val accepted = (1..5).count { writer.offer(row(it.toLong())) }
            assertThat(accepted).isBetween(3, 4); assertThat(writer.dropped()).isEqualTo(5L - accepted)
            writer.flush()
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM activity_log", Int::class.java)).isEqualTo(accepted)
            assertThat(registry.get("tooja.ops.dropped").counter().count()).isEqualTo((5 - accepted).toDouble())
        } finally { writer.shutdown() }
    }
    @Test @DisplayName("Given 활동·샘플·이벤트가 섞인 큐 When flush Then 각 테이블에 배치로 저장되고 IP·UA가 그대로 남는다")
    fun mixedBatch() {
        val db = database(); val writer = OpsWriter(db, SimpleMeterRegistry(), 100)
        try {
            writer.offer(row(1000, "login_success")); writer.offer(SampleRow(1000, "host", "host.cpu.percent", 12.5, "{\"disk\":\"/\"}")); writer.offer(EventRow(1000, "app_started", "{\"commit\":\"abc\"}"))
            writer.flush()
            val activity = db.jdbc.queryForMap("SELECT * FROM activity_log")
            assertThat(activity["client_ip"]).isEqualTo("203.0.113.9"); assertThat(activity["user_agent"]).isEqualTo("UA"); assertThat(activity["action"]).isEqualTo("login_success")
            assertThat(db.jdbc.queryForMap("SELECT * FROM infra_samples")["sample_value"]).isEqualTo(12.5)
            assertThat(db.jdbc.queryForMap("SELECT * FROM system_events")["type"]).isEqualTo("app_started")
        } finally { writer.shutdown() }
    }
    @Test @DisplayName("Given 오래된 기록과 최근 기록 When 보존 기준으로 purge Then 오래된 것만 지운다")
    fun purge() {
        val db = database(); val writer = OpsWriter(db, SimpleMeterRegistry(), 100)
        try {
            writer.offer(row(1_000)); writer.offer(row(5_000)); writer.offer(SampleRow(1_000, "app", "app.heartbeat", 1.0)); writer.offer(SampleRow(5_000, "app", "app.heartbeat", 1.0))
            writer.offer(EventRow(1_000, "app_started")); writer.flush()
            writer.offer(PurgeRow(activityBefore = 2_000, samplesBefore = 2_000, eventsBefore = 500)); writer.flush()
            assertThat(db.jdbc.queryForList("SELECT ts FROM activity_log", Long::class.java)).containsExactly(5_000)
            assertThat(db.jdbc.queryForList("SELECT ts FROM infra_samples", Long::class.java)).containsExactly(5_000)
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM system_events", Int::class.java)).isEqualTo(1)
        } finally { writer.shutdown() }
    }
}
