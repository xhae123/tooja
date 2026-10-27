package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import io.qameta.allure.Allure
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties=["server.address=127.0.0.1"])
@ActiveProfiles("test")
@DisplayName("HTTP 통합 테스트 · 실제 Tomcat의 인증 경계")
class HttpPathSecurityIntegrationTest {
    @LocalServerPort var port: Int=0
    @Autowired lateinit var db: Database
    @Autowired lateinit var service: InvestmentService
    @Autowired lateinit var rates: RateLimits
    @Autowired lateinit var mapper: ObjectMapper
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    companion object {
        private val file=Files.createTempFile("tooja-http-security-", ".db")
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { "jdbc:sqlite:$file" }
        }
    }

    @BeforeEach fun given() {
        db.reset();rates.clear()
        Allure.label("parentSuite","통합 테스트")
        Allure.label("suite","실제 HTTP + Tomcat + SQLite")
        Steps.step("Given 로컬 실제 Tomcat과 독립 DB, 인증 쿠키 없는 HTTP 클라이언트")
    }
    private fun send(path: String,body: ByteArray?=null): HttpResponse<String> {
        val request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
        if(body==null) request.GET() else request.header("Content-Type","application/json").method("PATCH",HttpRequest.BodyPublishers.ofByteArray(body))
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString())
    }

    @ParameterizedTest(name="Given 실제 HTTP·미로그인 When {0} 조회·중지 Then 401·상태 유지")
    @ValueSource(strings=["/api/v1","/api/v1;review","/api;review/v1","/%61pi/v1","/api/%76%31"])
    fun realRequestCannotSkipAuthentication(prefix: String) {
        val responses=Steps.step("When 변형된 요청 경로를 실제 소켓으로 전송") {
            listOf(send("$prefix/admin/overview"),send("$prefix/admin/investor-rankings"),
                send("$prefix/admin/investment-status","{\"status\":\"PAUSED\"}".toByteArray()))
        }
        Steps.step("Then 쿠키·Origin·CSRF 없는 모든 요청은 401, 중지되지 않음") {
            responses.forEach {
                assertThat(it.statusCode()).isEqualTo(401)
                assertThat(mapper.readTree(it.body())["error"]["code"].asText()).isEqualTo("SESSION_EXPIRED")
            }
            assertThat(service.investmentStatus().status).isEqualTo(InvestmentMode.RUNNING)
            assertThat(service.dashboard().totalInvestedAmount).isZero()
        }
    }

    @Test @DisplayName("Given 실제 HTTP·인코딩 경로·초과 본문 When 전송 Then 인증 전에 413·상태 유지")
    fun realEncodedRequestRetainsBodyLimit() {
        val response=Steps.step("When 1MiB 초과 본문을 인코딩된 API 경로로 전송") {
            send("/%61pi/v1/admin/investment-status",ByteArray(EdgeRequestFilter.MAX_BODY_BYTES+1) { ' '.code.toByte() })
        }
        Steps.step("Then 413 REQUEST_BODY_TOO_LARGE, 접수는 RUNNING") {
            assertThat(response.statusCode()).isEqualTo(413)
            assertThat(mapper.readTree(response.body())["error"]["code"].asText()).isEqualTo("REQUEST_BODY_TOO_LARGE")
            assertThat(service.investmentStatus().status).isEqualTo(InvestmentMode.RUNNING)
        }
    }

    @Test @DisplayName("Given 실제 HTTP When 공개 health·Swagger 조회 Then 기존 진입점 정상")
    fun publicRoutesStillWork() {
        Steps.step("When 외부 점검용 health와 공개 API 문서 조회") {
            assertThat(send("/actuator/health").statusCode()).isEqualTo(200)
            val spec=send("/v3/api-docs")
            assertThat(spec.statusCode()).isEqualTo(200)
            Steps.step("Then 컨트롤러 작업 22개 유지") {
                assertThat(mapper.readTree(spec.body())["paths"].properties().sumOf { it.value.size() }).isEqualTo(22)
            }
        }
    }
}
