package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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
}
