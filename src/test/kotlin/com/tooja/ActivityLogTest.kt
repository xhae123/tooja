package com.tooja

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration
import java.time.Instant

@DisplayName("단위 테스트 · 활동 기록의 행위 이름·클라이언트 IP·다운타임 판정")
class ActivityLogTest {
    @ParameterizedTest(name="{0} {1} → {3} 이면 {4}")
    @CsvSource(
        "POST,/api/v1/investor/session,,200,login_success",
        "POST,/api/v1/admin/session,,401,login_failed",
        "DELETE,/api/v1/investor/session,,204,logout",
        "GET,/api/v1/admin/csrf,,200,csrf",
        "POST,/api/v1/investor/investments,,201,invest_succeeded",
        "POST,/api/v1/investor/investments,,409,invest_rejected",
        "PATCH,/api/v1/admin/investment-status,pause,200,pause",
        "PATCH,/api/v1/admin/investment-status,,200,patch_investment_status",
        "GET,/api/v1/investor/me,,200,view_me",
        "GET,/api/v1/investor/teams/{teamId},,200,view_team",
        "GET,/api/v1/investor/investment-requests/{requestKey},,404,view_investment_request",
        "GET,/api/v1/admin/investors/{investorId},,200,view_investor",
        "GET,/api/v1/admin/investor-rankings,,200,view_investor_rankings",
        "GET,/api/v1/public/dashboard,,200,view_dashboard",
        "POST,/api/v1/admin/investment-status,,413,body_too_large",
        "GET,unmatched,,404,unmatched")
    fun deriveAction(method: String, route: String, explicit: String?, status: Int, expected: String) {
        assertThat(ActivityActions.derive(method, route, status, explicit)).isEqualTo(expected)
    }

    @Test @DisplayName("Given LB가 덧붙인 X-Forwarded-For When 신뢰 홉 1 Then 오른쪽 값이 클라이언트이고 위조된 앞 값은 무시한다")
    fun forwardedFromTheRight() {
        val req = MockHttpServletRequest().apply { remoteAddr = "10.0.0.5"; addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9") }
        assertThat(ClientIp.resolve(req, 1)).isEqualTo("203.0.113.9")
        assertThat(ClientIp.resolve(req, 2)).isEqualTo("1.2.3.4")
        assertThat(ClientIp.resolve(req, 3)).isEqualTo("10.0.0.5")
    }
    @Test @DisplayName("Given 헤더가 없거나 IP가 아닌 값 When 판별 Then 소켓 주소를 쓴다")
    fun invalidForwarded() {
        assertThat(ClientIp.resolve(MockHttpServletRequest().apply { remoteAddr = "10.0.0.5" }, 1)).isEqualTo("10.0.0.5")
        assertThat(ClientIp.resolve(MockHttpServletRequest().apply { remoteAddr = "10.0.0.5"; addHeader("X-Forwarded-For", "evil.example") }, 1)).isEqualTo("10.0.0.5")
        assertThat(ClientIp.resolve(MockHttpServletRequest().apply { remoteAddr = "10.0.0.5"; addHeader("X-Forwarded-For", "2001:db8::1") }, 1)).isEqualTo("2001:db8::1")
    }

    @Test @DisplayName("Given 마지막 heartbeat When 간격×배수를 넘으면 Then 공백을 다운타임으로 돌려주고 아니면 null")
    fun downtime() {
        val now = Instant.parse("2026-10-28T10:00:00Z"); val interval = Duration.ofSeconds(60)
        assertThat(DowntimeDetector.detect(null, now, interval, 3)).isNull()
        assertThat(DowntimeDetector.detect(now.minusSeconds(120), now, interval, 3)).isNull()
        assertThat(DowntimeDetector.detect(now.minusSeconds(181), now, interval, 3)).isEqualTo(Duration.ofSeconds(181))
    }
}
