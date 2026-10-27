package com.tooja

import io.qameta.allure.Allure
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerMapping
import java.time.Instant

@DisplayName("단위 테스트 · URL 표기에 의존하지 않는 API 보안 정책")
class ApiSecurityPolicyTest {
    private val auth=mock(AuthService::class.java)
    private val rates=mock(RateLimits::class.java)
    private val security=ApiSecurityInterceptor(auth,rates)

    @Access("admin") class AdminEndpoint { fun handle() {} }
    @PublicAccess("admin") class AdminLoginEndpoint { fun handle() {} }
    class UnclassifiedEndpoint { fun handle() {} }
    @Access("admin") @PublicAccess class ConflictingEndpoint { fun handle() {} }
    @Access("public") class InvalidRoleEndpoint { fun handle() {} }
    @PublicAccess class PublicEndpoint { fun handle() {} }

    private fun handler(bean: Any)=HandlerMethod(bean,bean.javaClass.getMethod("handle"))
    private fun request(uri: String,method: String="GET",route: String="/api/v1/admin/overview") =
        MockHttpServletRequest(method,uri).apply { setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,route) }

    @BeforeEach fun labels() {
        Allure.label("parentSuite","단위 테스트")
        Allure.label("suite","API 권한과 경로 경계")
    }

    @ParameterizedTest(name="Given 원문 경로 {0} When 관리자 핸들러 실행 전 검사 Then 관리자 인증을 반드시 호출")
    @ValueSource(strings=["/api/v1/admin/overview","/api/v1;review/admin/overview","/api/%76%31/admin/overview","/not-an-api-looking-path"])
    fun boundHandlerAlwaysRequiresAuthentication(uri: String) {
        val req=request(uri)
        `when`(auth.require("admin",req)).thenThrow(ApiException(401,"SESSION_EXPIRED","로그인 필요"))
        Steps.step("Given 실제 실행 대상은 관리자 핸들러, 원문 URL은 $uri")
        Steps.step("When 실행 전 보안 검사 / Then URL과 관계없이 401") {
            assertThatThrownBy { security.preHandle(req,MockHttpServletResponse(),handler(AdminEndpoint())) }
                .hasFieldOrPropertyWithValue("code","SESSION_EXPIRED")
            verify(auth).require("admin",req)
            verifyNoInteractions(rates)
        }
    }

    @ParameterizedTest(name="Given 정책 없는 API 경로 {0} When 실행 전 검사 Then 기본 거절")
    @ValueSource(strings=["/api/v1/new-endpoint","/api/v1;review/new-endpoint","/%61pi/v1/new-endpoint"])
    fun missingPolicyFailsClosed(uri: String) {
        Steps.step("Given 권한·공개 여부를 선언하지 않은 새 API")
        Steps.step("When 실행 전 검사 / Then 403 FORBIDDEN") {
            assertThatThrownBy { security.preHandle(request(uri),MockHttpServletResponse(),handler(UnclassifiedEndpoint())) }
                .hasFieldOrPropertyWithValue("code","FORBIDDEN")
            verifyNoInteractions(auth,rates)
        }
    }

    @Test @DisplayName("Given 보안 정책이 필요 없는 일반 화면 When 검사 Then 기존 화면을 통과")
    fun nonApiPage() {
        Steps.step("Given 일반 화면 핸들러")
        val allowed=Steps.step("When 보안 정책 검사") { security.preHandle(request("/login",route="/login"),MockHttpServletResponse(),handler(UnclassifiedEndpoint())) }
        Steps.step("Then 인증 없이 화면 접근, API 권한 정책에는 영향 없음") { assertThat(allowed).isTrue();verifyNoInteractions(auth,rates) }
    }

    @Test @DisplayName("Given 공개·보호 정책이 충돌 When 검사 Then 조용히 공개하지 않고 실행 중단")
    fun conflictingPolicy() {
        Steps.step("Given 공개와 관리자 전용을 동시에 선언")
        Steps.step("When 검사 / Then 잘못된 설정을 명시적으로 거절") {
            assertThatIllegalStateException().isThrownBy { security.preHandle(request("/api/v1/test"),MockHttpServletResponse(),handler(ConflictingEndpoint())) }
            verifyNoInteractions(auth,rates)
        }
    }

    @Test @DisplayName("Given 잘못된 보호 역할 When 검사 Then 인증 생략 없이 실행 중단")
    fun invalidRole() {
        Steps.step("Given 보호 API 역할을 잘못 선언")
        Steps.step("When 검사 / Then 잘못된 설정 거절") {
            assertThatIllegalStateException().isThrownBy { security.preHandle(request("/api/v1/test"),MockHttpServletResponse(),handler(InvalidRoleEndpoint())) }
            verifyNoInteractions(auth,rates)
        }
    }

    @Test @DisplayName("Given 관리자 공개 로그인·다른 원문 경로 When POST 검사 Then 관리자 CSRF 사용")
    fun csrfRoleComesFromPolicy() {
        val req=request("/different/path",method="POST",route="/api/v1/admin/session")
        Steps.step("Given 원문 URL과 무관하게 관리자 로그인으로 매핑된 요청")
        Steps.step("When 공개 로그인 요청의 보안 검사") { assertThat(security.preHandle(req,MockHttpServletResponse(),handler(AdminLoginEndpoint()))).isTrue() }
        Steps.step("Then 세션 인증은 생략하지만 관리자 CSRF 검사는 수행") {
            verify(auth).verifyCsrf("admin",req)
            verifyNoMoreInteractions(auth)
            verifyNoInteractions(rates)
        }
    }

    @Test @DisplayName("Given URL 형태가 다른 투자자 목록 조회 When 필터 검사 Then 실제 매핑의 허용 필터 사용")
    fun queryPolicyComesFromMatchedRoute() {
        val req=request("/api/v1;review/admin/investors",route="/api/v1/admin/investors").apply { addParameter("kind","STAFF") }
        val session=SessionRecord("admin_001","admin","test-csrf",Instant.parse("2027-01-01T00:00:00Z"))
        `when`(auth.require("admin",req)).thenReturn(session)
        Steps.step("Given 관리자 세션과 유효한 kind 필터")
        Steps.step("When 실제 매핑 기준으로 보안 검사") { assertThat(security.preHandle(req,MockHttpServletResponse(),handler(AdminEndpoint()))).isTrue() }
        Steps.step("Then 인증·요청 제한 유지, 정상 필터 허용") {
            assertThat(req.getAttribute("principal")).isSameAs(session)
            verify(rates).check("admin:admin_001",180)
        }
    }
    @Test @DisplayName("Given 공개 API로 매핑된 다른 원문 경로 When 검사 Then 공개 요청 제한 유지")
    fun publicRateLimitComesFromPolicy() {
        val req=request("/different/path",route="/api/v1/public/dashboard").apply { remoteAddr="203.0.113.1" }
        Steps.step("Given 실제 실행 대상은 공개 API")
        Steps.step("When 공개 정책 검사") { assertThat(security.preHandle(req,MockHttpServletResponse(),handler(PublicEndpoint()))).isTrue() }
        Steps.step("Then URL 표기와 무관하게 공개 요청 제한 적용") {
            verify(rates).check("public:203.0.113.1",30000)
            verifyNoInteractions(auth)
        }
    }
}
