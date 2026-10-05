package com.tooja

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping
import java.sql.SQLException

@DisplayName("단위 테스트 · 모니터링 개인정보 보호와 실패 집계")
class TelemetryTest {
    @Test @DisplayName("Given SQL과 코드가 담긴 예외 When 오류를 기록 Then 민감한 원문은 빼고 추적 ID와 SQLite 오류 번호만 남긴다")
    fun privateError() {
        val registry=SimpleMeterRegistry();val telemetry=Telemetry(registry,ObjectMapper())
        val logger=LoggerFactory.getLogger("com.tooja.telemetry") as Logger
        val appender=ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender)
        try {
            val req=MockHttpServletRequest("POST","/api/private-user?code=secret-code").apply {
                setAttribute("requestId","req_test");setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/{id}")
                addHeader("Cookie","session=secret-cookie")
            }
            telemetry.error(IllegalStateException("SQL secret-code",SQLException("secret-cookie",null,5)),req,500,"INTERNAL_ERROR")
            val event=appender.list.single()
            assertThat(event.formattedMessage).contains("req_test","/api/{id}","sqliteErrorCode",":5")
                .doesNotContain("secret-code","secret-cookie","private-user")
            assertThat(event.throwableProxy).isNull()
            assertThat(registry.get("tooja.server.errors").tag("status","500").counter().count()).isEqualTo(1.0)
        } finally { logger.detachAppender(appender);appender.stop() }
    }
    @Test @DisplayName("Given DB 쓰기 실패 When 계측한다 Then 예외를 유지하고 실패 시간을 한 건 집계한다")
    fun failedDatabase() {
        val registry=SimpleMeterRegistry();val telemetry=Telemetry(registry,ObjectMapper());val original=IllegalStateException("failed")
        assertThatThrownBy { telemetry.database("write") { throw original } }.isSameAs(original)
        assertThat(registry.get("tooja.database.operations").tags("operation","write","outcome","failure").timer().count()).isEqualTo(1)
    }
    @Test @DisplayName("Given 외부 또는 nginx 요청 When 내부 metrics를 조회 Then 전달 헤더를 위조해도 404다")
    fun privateEndpoint() {
        for(address in listOf("203.0.113.1","172.19.0.2")) {
            val req=MockHttpServletRequest("GET","/actuator/metrics/jvm.memory.used").apply { remoteAddr=address;addHeader("X-Forwarded-For","172.19.0.1") }
            val res=MockHttpServletResponse();var passed=false
            InternalMetricsFilter().doFilter(req,res) { _,_->passed=true }
            assertThat(res.status).isEqualTo(404);assertThat(passed).isFalse()
        }
    }
    @Test @DisplayName("Given 인스턴스의 Docker gateway When 내부 metrics를 조회 Then 수집 요청을 허용한다")
    fun hostEndpoint() {
        val req=MockHttpServletRequest("GET","/actuator/metrics").apply { remoteAddr="172.19.0.1" }
        var passed=false;InternalMetricsFilter().doFilter(req,MockHttpServletResponse()) { _,_->passed=true }
        assertThat(passed).isTrue()
    }
}
