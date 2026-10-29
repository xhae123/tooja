package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import io.qameta.allure.Allure
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
abstract class OwnerConsoleContractTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var db: Database
    @Autowired lateinit var ops: OpsWriter
    @Autowired lateinit var rates: RateLimits
    @Autowired lateinit var sampler: InfraSampler
    @Autowired lateinit var events: SystemEvents
    @Autowired lateinit var retention: OpsRetention
    private val cookies = mutableMapOf<String, Cookie>()
    private val tokens = mutableMapOf<String, String>()
    private val ownerToken = "test-owner-token-0123456789abcdef0123456789"
    private val origin = "http://localhost:8080"

    @BeforeEach fun given() {
        ops.flush(); db.reset(); rates.clear(); cookies.clear(); tokens.clear()
        Allure.label("parentSuite", "통합 테스트"); Allure.label("suite", "조물주 콘솔 + 실제 ${if (db.mysql) "MySQL" else "SQLite"}")
        Steps.step("Given 초기화된 ${if (db.mysql) "MySQL" else "SQLite"}, 비어 있는 활동 기록, 데모 계정")
    }
    private fun request(method: String, path: String, body: String? = null, form: Map<String, String>? = null, headers: Map<String, String> = emptyMap(), remote: String = "127.0.0.1"): MockHttpServletResponse {
        val req = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).header("Origin", origin).header("User-Agent", "OwnerConsoleTest/1.0").with { it.remoteAddr = remote; it }
        if (cookies.isNotEmpty()) req.cookie(*cookies.values.toTypedArray())
        headers.forEach { (k, v) -> req.header(k, v) }
        body?.let { req.contentType("application/json").content(it) }
        form?.forEach { (k, v) -> req.param(k, v) }
        val response = mvc.perform(req).andReturn().response
        response.cookies.forEach { if (it.maxAge == 0) cookies.remove(it.name) else cookies[it.name] = it }
        return response
    }
    private fun apiLogin(role: String, code: String) {
        tokens[role] = mapper.readTree(request("GET", "/api/v1/$role/csrf").contentAsString)["csrfToken"].asText()
        val r = request("POST", "/api/v1/$role/session", "{\"code\":\"$code\"}", headers = mapOf("X-CSRF-Token" to tokens[role]!!))
        assertThat(r.status).isEqualTo(200); tokens[role] = mapper.readTree(r.contentAsString)["csrfToken"].asText()
    }
    private fun api(method: String, path: String, body: String? = null, role: String = "investor", extra: Map<String, String> = emptyMap()) =
        request(method, path, body, headers = (tokens[role]?.let { mapOf("X-CSRF-Token" to it) } ?: emptyMap()) + extra)
    private fun loginFormCsrf(): String {
        val page = request("GET", "/owner/login"); assertThat(page.status).isEqualTo(200)
        return Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(page.contentAsString)!!.groupValues[1]
    }
    private fun ownerLogin(token: String = ownerToken, originHeader: String = origin): MockHttpServletResponse {
        val csrf = loginFormCsrf()
        val req = MockMvcRequestBuilders.post("/owner/login").header("Origin", originHeader).header("User-Agent", "OwnerConsoleTest/1.0").param("token", token).param("_csrf", csrf).cookie(*cookies.values.toTypedArray())
        val response = mvc.perform(req).andReturn().response
        response.cookies.forEach { if (it.maxAge == 0) cookies.remove(it.name) else cookies[it.name] = it }
        return response
    }
    private fun activityRows() = db.jdbc.queryForList("SELECT * FROM activity_log ORDER BY id")

    @Test @DisplayName("Given 올바른 토큰 When 폼 로그인·개요·로그아웃 Then 세션 쿠키로만 페이지가 열리고 로그아웃 후 로그인으로 돌아간다")
    fun ownerLoginFlow() {
        Steps.step("Given 미로그인 상태의 개요 요청은 로그인으로 302") { val r = request("GET", "/owner"); assertThat(r.status).isEqualTo(302); assertThat(r.getHeader("Location")).isEqualTo("/owner/login") }
        val login = Steps.step("When 토큰 폼 제출") { ownerLogin() }
        Steps.step("Then 302 /owner, HttpOnly owner_session 쿠키") { assertThat(login.status).isEqualTo(302); assertThat(login.getHeader("Location")).isEqualTo("/owner"); assertThat(login.getHeaders("Set-Cookie").any { it.startsWith("owner_session=") && it.contains("HttpOnly") }).isTrue() }
        Steps.step("Then 모든 페이지가 200이고 보호 헤더가 붙는다") {
            for (path in listOf("/owner", "/owner/activity", "/owner/accounts", "/owner/accounts/inv_001", "/owner/sessions", "/owner/investments", "/owner/infra", "/owner/events", "/owner/errors")) {
                val r = request("GET", path); assertThat(r.status).describedAs(path).isEqualTo(200)
                assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store"); assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY"); assertThat(r.getHeader("X-Robots-Tag")).isEqualTo("noindex")
            }
        }
        Steps.step("When 로그아웃 Then 세션은 종료되고 개요는 다시 302") {
            val csrf = Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(request("GET", "/owner").contentAsString)!!.groupValues[1]
            assertThat(request("POST", "/owner/logout", form = mapOf("_csrf" to csrf)).status).isEqualTo(302)
            assertThat(request("GET", "/owner").status).isEqualTo(302)
        }
        ops.flush()
        Steps.step("Then 조물주 로그인·로그아웃이 OWNER 행위로 기록된다") {
            assertThat(activityRows().filter { it["actor_role"] == "OWNER" }.map { it["action"] }).containsExactly("login_success", "logout")
        }
    }
    @Test @DisplayName("Given Vercel 프론트 Origin When 조물주 로그인 Then 403이고 API 허용 Origin 목록과 무관하다")
    fun ownerRejectsFrontendOrigin() { assertThat(ownerLogin(originHeader = "https://line1thon.vercel.app").status).isEqualTo(403); assertThat(request("GET", "/owner").status).isEqualTo(302) }
    @Test @DisplayName("Given 틀린 토큰 5회 When 6번째 시도 Then 429·Retry-After이며 실패가 기록되고 토큰 원문은 저장되지 않는다")
    fun ownerLoginLimited() {
        repeat(5) { assertThat(ownerLogin("wrong-token-wrong-token-wrong-token-00").status).isEqualTo(401) }
        val limited = ownerLogin("wrong-token-wrong-token-wrong-token-00")
        assertThat(limited.status).isEqualTo(429); assertThat(limited.getHeader("Retry-After")).isNotBlank()
        val correct = ownerLogin(); assertThat(correct.status).describedAs("limit also blocks the right token").isEqualTo(429)
        ops.flush()
        val failures = activityRows().filter { it["action"] == "login_failed" && it["actor_role"] == "OWNER" }
        assertThat(failures).hasSize(7); assertThat(failures.map { it["client_ip"] }).containsOnly("127.0.0.1")
        assertThat(mapper.writeValueAsString(activityRows())).doesNotContain("wrong-token", ownerToken)
    }

    @Test @DisplayName("Given 투자자·관리자 흐름 When 활동 기록을 flush Then 로그인·투자·거절·중지·재개·로그아웃이 행위·대상·IP·UA와 함께 남는다")
    fun businessActivityRecorded() {
        apiLogin("investor", "0037")
        val key = UUID.randomUUID().toString()
        assertThat(api("POST", "/api/v1/investor/investments", "{\"teamId\":2,\"amount\":300000}", extra = mapOf("Idempotency-Key" to key)).status).isEqualTo(201)
        assertThat(api("POST", "/api/v1/investor/investments", "{\"teamId\":2,\"amount\":100000}", extra = mapOf("Idempotency-Key" to UUID.randomUUID().toString())).status).isEqualTo(409)
        apiLogin("admin", "4821")
        assertThat(api("PATCH", "/api/v1/admin/investment-status", "{\"status\":\"PAUSED\"}", role = "admin").status).isEqualTo(200)
        assertThat(api("PATCH", "/api/v1/admin/investment-status", "{\"status\":\"RUNNING\"}", role = "admin").status).isEqualTo(200)
        assertThat(request("GET", "/api/v1/investor/me", headers = mapOf("X-Forwarded-For" to "1.2.3.4, 203.0.113.9")).status).isEqualTo(200)
        assertThat(api("DELETE", "/api/v1/investor/session").status).isEqualTo(204)
        assertThat(request("GET", "/api/v1/admin/overview", remote = "198.51.100.7").status).isEqualTo(200)
        ops.flush()
        val rows = activityRows()
        Steps.step("Then 행위 순서") {
            assertThat(rows.map { it["action"] }).containsSubsequence("csrf", "login_success", "invest_succeeded", "invest_rejected", "csrf", "login_success", "pause", "resume", "view_me", "logout", "view_overview")
        }
        Steps.step("Then 투자 성공 행은 팀·금액·투자 ID, 거절 행은 오류 코드") {
            val ok = rows.first { it["action"] == "invest_succeeded" }; assertThat(ok["team_id"]).isEqualTo(2); assertThat((ok["amount"] as Number).toInt()).isEqualTo(300000); assertThat(ok["investment_id"]).isNotNull(); assertThat(ok["account_id"]).isEqualTo("inv_001"); assertThat(ok["actor_role"]).isEqualTo("INVESTOR"); assertThat(ok["status"]).isEqualTo(201)
            val rejected = rows.first { it["action"] == "invest_rejected" }; assertThat(rejected["error_code"]).isEqualTo("ALREADY_INVESTED"); assertThat(rejected["status"]).isEqualTo(409)
        }
        Steps.step("Then 로그인 성공 행에도 계정이 남고 관리자 중지·재개는 관리자 계정으로 기록된다") {
            assertThat(rows.first { it["action"] == "login_success" }["account_id"]).isEqualTo("inv_001")
            assertThat(rows.first { it["action"] == "pause" }["account_id"]).isEqualTo("admin_001"); assertThat(rows.first { it["action"] == "pause" }["actor_role"]).isEqualTo("ADMIN")
        }
        Steps.step("Then IP는 LB가 덧붙인 오른쪽 값, 소켓 주소·UA·Origin·requestId·소요 시간을 함께 저장") {
            val forwarded = rows.first { it["action"] == "view_me" }; assertThat(forwarded["client_ip"]).isEqualTo("203.0.113.9"); assertThat(forwarded["remote_addr"]).isEqualTo("127.0.0.1")
            val direct = rows.first { it["action"] == "view_overview" }; assertThat(direct["client_ip"]).isEqualTo("198.51.100.7")
            assertThat(direct["user_agent"]).isEqualTo("OwnerConsoleTest/1.0"); assertThat(direct["origin"]).isEqualTo(origin); assertThat(direct["request_id"].toString()).startsWith("req_"); assertThat((direct["duration_ms"] as Number).toInt()).isGreaterThanOrEqualTo(0)
        }
        Steps.step("Then 코드 원문·본문은 어디에도 없다") { assertThat(mapper.writeValueAsString(rows)).doesNotContain("0037", "4821", "\"teamId\"") }
        Steps.step("Then 조물주 페이지가 같은 기록을 보여준다") {
            ownerLogin(); ops.flush()
            val activity = request("GET", "/owner/activity?action=invest_succeeded").contentAsString
            // The action filter lists every known action, so check the row-only error code instead of the action name.
            assertThat(activity).contains("invest_succeeded", "inv_001", "300,000원").doesNotContain("ALREADY_INVESTED")
            val account = request("GET", "/owner/accounts/inv_001").contentAsString
            assertThat(account).contains("팀01 투자자001", "ALREADY_INVESTED", "300,000원")
            val accounts = request("GET", "/owner/accounts").contentAsString
            db.jdbc.queryForList("SELECT code_hash FROM accounts", String::class.java).forEach { assertThat(accounts).doesNotContain(it) }
            assertThat(accounts).doesNotContain("0037", "4821", "0038", "1000\"")
            assertThat(request("GET", "/owner/errors").status).isEqualTo(200)
        }
    }

    @Test @DisplayName("Given 호스트 수집기 When ingest를 호출 Then 주소·토큰이 맞을 때만 202이며 인프라 페이지에 지표가 보인다")
    fun ingestAndInfraPage() {
        val now = Instant.now().toEpochMilli()
        val payload = "{\"collector\":\"tooja-observer\",\"samples\":[{\"ts\":$now,\"source\":\"host\",\"metric\":\"host.cpu.percent\",\"value\":17.5},{\"ts\":$now,\"source\":\"oci\",\"metric\":\"oci.apm.availability\",\"value\":100.0,\"dims\":{\"monitor\":\"seoul\"}}],\"events\":[{\"ts\":$now,\"type\":\"collector_online\",\"detail\":{\"note\":\"test\"}}]}"
        Steps.step("Then 외부 주소·토큰 없음·잘못된 토큰은 404") {
            assertThat(request("POST", "/internal/ingest", payload, headers = mapOf("X-Internal-Token" to "test-ingest-token"), remote = "203.0.113.1").status).isEqualTo(404)
            assertThat(request("POST", "/internal/ingest", payload, remote = "172.19.0.1").status).isEqualTo(404)
            assertThat(request("POST", "/internal/ingest", payload, headers = mapOf("X-Internal-Token" to "nope"), remote = "172.19.0.1").status).isEqualTo(404)
        }
        val accepted = Steps.step("When Docker gateway에서 토큰과 함께 전송") { request("POST", "/internal/ingest", payload, headers = mapOf("X-Internal-Token" to "test-ingest-token"), remote = "172.19.0.1") }
        assertThat(accepted.status).isEqualTo(202); assertThat(mapper.readTree(accepted.contentAsString)["accepted"].asInt()).isEqualTo(3)
        Steps.step("Then 지표 이름·출처 검증 실패는 422") {
            val bad = request("POST", "/internal/ingest", "{\"collector\":\"x\",\"samples\":[{\"ts\":1,\"source\":\"app\",\"metric\":\"Bad Metric\",\"value\":1}]}", headers = mapOf("X-Internal-Token" to "test-ingest-token"), remote = "172.19.0.1")
            assertThat(bad.status).isEqualTo(422)
        }
        ops.flush()
        assertThat(db.jdbc.queryForObject("SELECT sample_value FROM infra_samples WHERE metric='host.cpu.percent'", Double::class.java)).isEqualTo(17.5)
        assertThat(db.jdbc.queryForObject("SELECT dims FROM infra_samples WHERE metric='oci.apm.availability'", String::class.java)).contains("seoul")
        ownerLogin()
        val infra = request("GET", "/owner/infra?source=host&metric=host.cpu.percent&range=1h").contentAsString
        assertThat(infra).contains("host.cpu.percent", "17.5", "<path").doesNotContain("이 범위에 샘플이 없습니다")
        assertThat(request("GET", "/owner/events?type=collector_online").contentAsString).contains("collector_online", "test")
    }

    @Test @DisplayName("Given 앱 자체 샘플러 When 한 번 실행 Then heartbeat·JVM·연결 풀(·MySQL) 지표가 저장되고 개요에 반영된다")
    fun sampleOnce() {
        val rows = sampler.sampleOnce(); ops.flush()
        assertThat(rows.map { it.metric }).contains("app.heartbeat", "jvm.heap.used", "jvm.heap.max", "hikari.active", "http.requests.count", "disk.used_percent")
        if (db.mysql) assertThat(rows.map { it.metric }).contains("mysql.threads_connected", "mysql.uptime_seconds")
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM infra_samples WHERE metric='app.heartbeat' AND source='app'", Int::class.java)).isEqualTo(1)
        ownerLogin()
        assertThat(request("GET", "/owner").contentAsString).contains("app.heartbeat", "jvm.heap.used")
    }
    @Test @DisplayName("Given 4분 전 heartbeat When 기동 처리 Then downtime_detected와 app_started가 기록된다")
    fun downtimeOnStartup() {
        val now = Instant.now()
        ops.offer(SampleRow(now.minusSeconds(240).toEpochMilli(), "app", "app.heartbeat", 1.0)); ops.flush()
        events.onStartup(now); ops.flush()
        val types = db.jdbc.queryForList("SELECT type FROM system_events ORDER BY id", String::class.java)
        assertThat(types).containsExactly("downtime_detected", "app_started")
        assertThat(db.jdbc.queryForObject("SELECT detail FROM system_events WHERE type='downtime_detected'", String::class.java)).contains("\"gapSeconds\":240")
        ownerLogin()
        assertThat(request("GET", "/owner/events").contentAsString).contains("downtime_detected", "gapSeconds")
    }
    @Test @DisplayName("Given 91일 전 활동·31일 전 샘플 When 보존 정리 Then 오래된 행만 삭제된다")
    fun retentionPurge() {
        val now = Instant.now().toEpochMilli(); val day = 86_400_000L
        ops.offer(ActivityRow(now - 91 * day, "req_old", "GET", "/api/v1/investor/me", 200, 1, null, null, "view_me", null, null, null, null, null, "127.0.0.1", "127.0.0.1", null))
        ops.offer(ActivityRow(now - 10 * day, "req_new", "GET", "/api/v1/investor/me", 200, 1, null, null, "view_me", null, null, null, null, null, "127.0.0.1", "127.0.0.1", null))
        ops.offer(SampleRow(now - 31 * day, "app", "app.heartbeat", 1.0)); ops.offer(SampleRow(now - day, "app", "app.heartbeat", 1.0)); ops.flush()
        retention.purgeOnce(); ops.flush()
        assertThat(db.jdbc.queryForList("SELECT request_id FROM activity_log", String::class.java)).containsExactly("req_new")
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM infra_samples", Int::class.java)).isEqualTo(1)
    }
    @Test @DisplayName("Given 두 엔진 When 운영 테이블 컬럼을 조회 Then Flyway V3와 SQLite DDL이 같은 열을 가진다")
    fun schemaParity() {
        fun columns(table: String) = db.jdbc.queryForRowSet("SELECT * FROM $table WHERE 1=0").metaData.let { m -> (1..m.columnCount).map { m.getColumnName(it).lowercase() } }
        assertThat(columns("activity_log")).containsExactly("id", "ts", "request_id", "method", "route", "status", "duration_ms", "actor_role", "account_id", "action", "error_code", "team_id", "investment_id", "amount", "origin", "client_ip", "remote_addr", "user_agent")
        assertThat(columns("infra_samples")).containsExactly("id", "ts", "source", "metric", "sample_value", "dims")
        assertThat(columns("system_events")).containsExactly("id", "ts", "type", "detail")
        assertThat(columns("owner_sessions")).containsExactly("token_hash", "csrf", "created_at", "expires_at", "revoked_at", "client_ip", "user_agent")
    }
    @Test @DisplayName("Given 조물주 쿠키만 When 관리자 API 호출 Then 401이고, 관리자 쿠키로 조물주 페이지는 302")
    fun rolesStaySeparate() {
        ownerLogin(); assertThat(request("GET", "/api/v1/admin/overview").status).isEqualTo(401)
        cookies.clear(); apiLogin("admin", "4821"); assertThat(request("GET", "/owner").status).isEqualTo(302)
    }
}

class OwnerConsoleIntegrationTest: OwnerConsoleContractTest() {
    companion object {
        private val file = Files.createTempFile("tooja-owner-", ".db")
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) { r.add("spring.datasource.url") { "jdbc:sqlite:$file" } }
    }
}
@ActiveProfiles("test", "mysql")
class MySqlOwnerConsoleIntegrationTest: OwnerConsoleContractTest() {
    companion object {
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { System.getenv("TEST_MYSQL_URL") ?: "jdbc:mysql://localhost:13306/tooja_test?sslMode=REQUIRED" }
            r.add("spring.datasource.username") { System.getenv("TEST_MYSQL_USERNAME") ?: "root" }
            r.add("spring.datasource.password") { System.getenv("TEST_MYSQL_PASSWORD") ?: "ci-test-only" }
        }
    }
}
