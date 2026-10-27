package com.tooja

import io.qameta.allure.Allure
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.mock.web.MockHttpServletRequest

@DisplayName("단위 테스트 · Spring과 같은 경로 판별")
class RequestPathsPolicyTest {
    @BeforeEach fun labels() {
        Allure.label("parentSuite","단위 테스트")
        Allure.label("suite","Spring 경로 판별")
    }
    @ParameterizedTest(name="Given 경로 {0} When Spring 방식으로 해석 Then API={1}, 비공개지표={2}")
    @CsvSource(
        "/api/v1/admin/overview,true,false",
        "/api/v1;review/admin/overview,true,false",
        "/%61pi/v1/admin/overview,true,false",
        "/api/%76%31/admin/overview,true,false",
        "/actuator/metrics,false,true",
        "/actuator;review/metrics,false,true",
        "/%61ctuator/metrics,false,true",
        "/actuator,false,true",
        "/actuator/,false,true",
        "/actuator/health,false,false",
        "/actuator;review/health,false,false",
        "/actuatorx/metrics,false,false",
        "/login,false,false"
    )
    fun sameSemantics(uri: String,api: Boolean,privateMetrics: Boolean) {
        Steps.step("Given 원문 경로 $uri")
        val request=MockHttpServletRequest("GET",uri)
        val result=Steps.step("When Spring PathPattern으로 판별") { RequestPaths.isApi(request) to RequestPaths.isPrivateActuator(request) }
        Steps.step("Then API=$api, 비공개 Actuator=$privateMetrics") {
            assertThat(result.first).isEqualTo(api)
            assertThat(result.second).isEqualTo(privateMetrics)
        }
    }
    @Test @DisplayName("Given 앱이 context path 아래 있음 When 경로 해석 Then context를 제외한 실제 API 판별")
    fun contextPath() {
        val req=MockHttpServletRequest("GET","/tooja/%61pi/v1;review/admin/overview").apply { contextPath="/tooja" }
        Steps.step("Given /tooja 아래 배치된 API 요청")
        val api=Steps.step("When 앱 내부 경로로 판별") { RequestPaths.isVersionedApi(req) }
        Steps.step("Then API로 인식하여 검사 대상 유지") { assertThat(api).isTrue() }
    }
}
