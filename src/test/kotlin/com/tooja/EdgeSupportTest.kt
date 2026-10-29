package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.http.HttpServletRequest

@DisplayName("단위 테스트 · LB 전환 후 요청 크기·HTTPS 보호")
class EdgeSupportTest {
    private val filter=EdgeRequestFilter(ObjectMapper(),true,"https://api.example.com")
    @Test @DisplayName("Given 1MiB보다 큰 Content-Length When API에 요청 Then 본문을 읽지 않고 413으로 거절한다")
    fun knownOversize() {
        val req=MockHttpServletRequest("POST","/api/v1/investor/session").apply { setContent(ByteArray(EdgeRequestFilter.MAX_BODY_BYTES+1));setAttribute("requestId","req_size") }
        val res=MockHttpServletResponse();var passed=false;filter.doFilter(req,res) { _,_->passed=true }
        assertThat(res.status).isEqualTo(413);assertThat(res.contentAsString).contains("REQUEST_BODY_TOO_LARGE","req_size");assertThat(passed).isFalse()
    }
    @Test @DisplayName("Given 길이를 숨긴 chunked 본문 When 실제 읽은 본문이 1MiB를 넘으면 Then 413으로 거절한다")
    fun chunkedOversize() {
        val req=object: MockHttpServletRequest("POST","/api/v1/investor/session") { override fun getContentLengthLong()=-1L }.apply {setContent(ByteArray(EdgeRequestFilter.MAX_BODY_BYTES+1));addHeader("Transfer-Encoding","chunked")}
        val res=MockHttpServletResponse();var passed=false;filter.doFilter(req,res) { _,_->passed=true }
        assertThat(res.status).isEqualTo(413);assertThat(passed).isFalse()
    }
    @Test @DisplayName("Given 허용 크기의 UTF-8 JSON When 필터를 통과하면 Then 컨트롤러가 같은 본문을 읽는다")
    fun unchangedBody() {
        val body="{\"alias\":\"별빛 개발자\"}".toByteArray()
        val req=MockHttpServletRequest("PATCH","/api/v1/admin/investors/id/alias").apply {setContent(body)}
        filter.doFilter(req,MockHttpServletResponse()) { request,_-> assertThat((request as HttpServletRequest).inputStream.readAllBytes()).isEqualTo(body) }
    }
    @Test @DisplayName("Given LB가 HTTP로 받은 요청 When 앱에 도착하면 Then 같은 경로·쿼리의 고정 HTTPS 출처로 308 이동한다")
    fun canonicalRedirect() {
        val req=MockHttpServletRequest("GET","/api-docs").apply {addHeader("X-Forwarded-Proto","http");addHeader("Host","attacker.example");queryString="tab=1"}
        val res=MockHttpServletResponse();var passed=false;filter.doFilter(req,res) { _,_->passed=true }
        assertThat(res.status).isEqualTo(308);assertThat(res.getHeader("Location")).isEqualTo("https://api.example.com/api-docs?tab=1");assertThat(passed).isFalse()
    }
    @Test @DisplayName("Given 해외 ACME 인증서 검증 When HTTP challenge를 조회하면 Then 인증서 갱신용 파일은 HTTPS 이동 없이 제공할 수 있다")
    fun acmeException() {
        val req=MockHttpServletRequest("GET","/.well-known/acme-challenge/public-token").apply {addHeader("X-Forwarded-Proto","http")}
        var passed=false;filter.doFilter(req,MockHttpServletResponse()) { _,_->passed=true };assertThat(passed).isTrue()
    }
    @Test @DisplayName("Given API 요청이 끝나면 When 활동 기록기가 있으면 Then 경로·상태·행위·클라이언트 IP·UA가 한 줄 저장되고 본문은 저장되지 않는다")
    fun recordsActivity(@org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path) {
        val source=org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:${directory.resolve("edge.db")}").apply { setDriverClassName("org.sqlite.JDBC") }
        val db=Database(org.springframework.jdbc.core.JdbcTemplate(source),org.springframework.jdbc.datasource.DataSourceTransactionManager(source),Secrets("edge-test-pepper-0123"),org.springframework.mock.env.MockEnvironment().also { it.setActiveProfiles("production") },"manual","","").also { it.initialize() }
        val ops=OpsWriter(db,io.micrometer.core.instrument.simple.SimpleMeterRegistry(),10)
        try {
            val logging=EdgeRequestFilter(ObjectMapper(),false,"https://api.example.com",ops,java.time.Clock.systemUTC(),1)
            val req=MockHttpServletRequest("POST","/api/v1/investor/session").apply { setContent("{\"code\":\"0037\"}".toByteArray());remoteAddr="10.0.0.2";addHeader("X-Forwarded-For","9.9.9.9, 203.0.113.5");addHeader("User-Agent","EdgeTest/1");setAttribute("requestId","req_edge") }
            val res=MockHttpServletResponse()
            logging.doFilter(req,res) { request,response-> request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/v1/investor/session");(response as jakarta.servlet.http.HttpServletResponse).status=401 }
            logging.doFilter(MockHttpServletRequest("GET","/owner/login"),MockHttpServletResponse()) { _,_-> }
            ops.flush()
            val rows=db.jdbc.queryForList("SELECT * FROM activity_log")
            assertThat(rows).hasSize(1)
            val row=rows.single()
            assertThat(row["action"]).isEqualTo("login_failed");assertThat(row["route"]).isEqualTo("/api/v1/investor/session");assertThat(row["status"]).isEqualTo(401)
            assertThat(row["client_ip"]).isEqualTo("203.0.113.5");assertThat(row["remote_addr"]).isEqualTo("10.0.0.2");assertThat(row["user_agent"]).isEqualTo("EdgeTest/1");assertThat(row["request_id"]).isEqualTo("req_edge")
            assertThat(row.toString()).doesNotContain("0037")
        } finally { ops.shutdown() }
    }
    @Test @DisplayName("Given 1MiB 초과 본문 When 수집기 ingest 경로에 전송 Then API와 같은 413")
    fun internalBodyLimit() {
        val req=MockHttpServletRequest("POST","/internal/ingest").apply { setContent(ByteArray(EdgeRequestFilter.MAX_BODY_BYTES+1)) }
        val res=MockHttpServletResponse();var passed=false;filter.doFilter(req,res) { _,_->passed=true }
        assertThat(res.status).isEqualTo(413);assertThat(passed).isFalse()
    }
    @ParameterizedTest(name="Given 1MiB 초과 본문 When {0} API에 전송 Then 경로 표기와 관계없이 413")
    @ValueSource(strings=["/api/v1/admin/investment-status","/%61pi/v1/admin/investment-status","/api;review/v1/admin/investment-status","/api/%76%31/admin/investment-status"])
    fun pathBodyLimit(uri: String) {
        Steps.step("Given 1MiB보다 큰 PATCH 본문")
        val req=MockHttpServletRequest("PATCH",uri).apply { setContent(ByteArray(EdgeRequestFilter.MAX_BODY_BYTES+1)) }
        val res=MockHttpServletResponse()
        var passed=false
        Steps.step("When API 본문 제한 필터 실행") { filter.doFilter(req,res) { _,_->passed=true } }
        Steps.step("Then 컨트롤러로 전달하지 않고 413") {
            assertThat(res.status).isEqualTo(413)
            assertThat(passed).isFalse()
            assertThat(res.contentAsString).contains("REQUEST_BODY_TOO_LARGE")
        }
    }
}
