package com.tooja

import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.mock.web.MockHttpServletResponse
import jakarta.servlet.http.Cookie
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.*
import io.qameta.allure.Allure
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@DisplayName("통합 테스트 · 실제 SQLite와 HTTP 컨트롤러")
class ApiIntegrationTest {
    companion object {
        private val file=Files.createTempFile("tooja-integration-",".db")
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) { r.add("spring.datasource.url") { "jdbc:sqlite:$file" } }
    }
    @TestConfiguration class TimeConfig { @Bean @Primary fun controlledClock(): Clock=MutableClock() }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var db: Database
    @Autowired lateinit var service: InvestmentService
    @Autowired lateinit var rates: RateLimits
    @Autowired lateinit var clock: Clock
    private val cookies=mutableMapOf<String,Cookie>()
    private val tokens=mutableMapOf<String,String>()
    @BeforeEach fun given() { db.reset();rates.clear();(clock as MutableClock).current=Instant.parse("2026-10-05T00:00:00Z");cookies.clear();tokens.clear();Allure.label("parentSuite","통합 테스트");Allure.label("suite","컨트롤러 + 실제 SQLite");Steps.step("Given 초기화된 SQLite에 투자자 5명과 관리자 1명을 사전 등록") }
    fun call(method: String,path: String,body: String?=null,key: String?=null,csrf: Boolean=true,origin: String="http://localhost:8080"): MockHttpServletResponse {
        val req=MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path).header("Origin",origin)
        if(cookies.isNotEmpty())req.cookie(*cookies.values.toTypedArray())
        if(body!=null)req.contentType("application/json").content(body)
        val role=if(path.startsWith("/api/v1/admin"))"admin" else "investor"
        if(csrf)tokens[role]?.let { req.header("X-CSRF-Token",it) }
        key?.let { req.header("Idempotency-Key",it) }
        val response=mvc.perform(req).andReturn().response
        response.cookies.forEach { if(it.maxAge==0)cookies.remove(it.name) else cookies[it.name]=it }
        return response
    }
    fun json(r: MockHttpServletResponse)=mapper.readTree(r.contentAsString)
    fun login(role: String="investor",code: String="0037"): JsonNode {
        tokens[role]=json(call("GET","/api/v1/$role/csrf"))["csrfToken"].asText()
        val r=call("POST","/api/v1/$role/session","{\"code\":\"$code\"}");assertThat(r.status).isEqualTo(200);val j=json(r);tokens[role]=j["csrfToken"].asText();return j
    }
    fun invest(team: Int,amount: Int,key: String=UUID.randomUUID().toString())=call("POST","/api/v1/investor/investments","{\"teamId\":$team,\"amount\":$amount}",key)
    fun failure(r: MockHttpServletResponse,status: Int,code: String) { Steps.step("Then HTTP $status · $code") { assertThat(r.status).isEqualTo(status);assertThat(json(r)["error"]["code"].asText()).isEqualTo(code);assertThat(r.getHeader("X-Request-ID")).isEqualTo(json(r)["requestId"].asText()) } }
    @Test @DisplayName("최초 로그인 전 미지급이고 최초 성공 때 정확히 100만원 지급한다") fun firstGrant() {
        assertThat(service.investor("inv_001").spendingStatus).isEqualTo("NOT_ACTIVATED")
        val result=Steps.step("When 0037 코드로 최초 로그인") { login() }
        Steps.step("Then 100만원 지급, 지급 원장은 한 건, 코드는 응답에 노출하지 않음") { assertThat(result["investor"]["balance"].asInt()).isEqualTo(1000000);assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM grants",Int::class.java)).isEqualTo(1);assertThat(result.toString()).doesNotContain("0037") }
    }
    @Test @DisplayName("재로그인해도 지급하지 않고 기존 잔액·내역을 복구한다") fun noRegrant() {
        login();invest(2,700000);Steps.step("When 로그아웃하고 같은 코드로 재로그인") { assertThat(call("DELETE","/api/v1/investor/session").status).isEqualTo(204);login() }
        Steps.step("Then 잔액 30만원, 지급 1건, 거래 1건") { assertThat(service.investor("inv_001").balance).isEqualTo(300000);assertThat(service.history("inv_001").count).isEqualTo(1);assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM grants",Int::class.java)).isEqualTo(1) }
    }
    @Test @DisplayName("관리자 로그인은 투자금을 지급하지 않는다") fun noAdminGrant() {
        Steps.step("When 관리자 코드로 로그인") { login("admin","4821") }
        Steps.step("Then 지급 원장 0건, 등록 투자자 5명 유지") { assertThat(service.overview().total.totalIssuedAmount).isZero();assertThat(service.overview().total.registeredInvestorCount).isEqualTo(5) }
    }
    @ParameterizedTest(name="존재하지 않거나 반대 역할 코드 {0}은 같은 인증 실패") @ValueSource(strings=["9999","4821"])
    fun wrongCode(code: String) { tokens["investor"]=json(call("GET","/api/v1/investor/csrf"))["csrfToken"].asText();val r=Steps.step("When 투자자 로그인에 $code 전송") { call("POST","/api/v1/investor/session","{\"code\":\"$code\"}") };failure(r,401,"AUTHENTICATION_FAILED");assertThat(service.overview().total.totalIssuedAmount).isZero() }
    @Test @DisplayName("두 역할 동시 로그인 후 투자자만 로그아웃하면 관리자 세션은 유지된다") fun independent() {
        login();login("admin","4821");Steps.step("When 투자자만 로그아웃") { assertThat(call("DELETE","/api/v1/investor/session").status).isEqualTo(204) }
        Steps.step("Then 관리자 조회 200, 투자자 조회는 반대 역할만 있어 403") { assertThat(call("GET","/api/v1/admin/overview").status).isEqualTo(200);failure(call("GET","/api/v1/investor/me"),403,"FORBIDDEN") }
    }
    @Test @DisplayName("관리자 로그아웃도 투자자 세션을 유지한다") fun adminLogout() { login();login("admin","4821");Steps.step("When 관리자만 로그아웃"){call("DELETE","/api/v1/admin/session")};Steps.step("Then 투자자 조회 200"){assertThat(call("GET","/api/v1/investor/me").status).isEqualTo(200)} }
    @Test @DisplayName("미로그인은 401이고 투자자만 로그인해 관리자를 호출하면 403이다") fun permissions() { failure(call("GET","/api/v1/admin/overview"),401,"SESSION_EXPIRED");login();failure(call("GET","/api/v1/admin/overview"),403,"FORBIDDEN") }
    @Test @DisplayName("운영팀 투자자도 관리자 권한은 없다") fun staffPermissions() { login(code="1000");failure(call("GET","/api/v1/admin/overview"),403,"FORBIDDEN");Steps.step("Then 운영팀은 20개 팀 모두 투자 가능") { assertThat(service.teams("staff_001").teams).allMatch{it.investmentState=="AVAILABLE"};assertThat(invest(1,100000).status).isEqualTo(201) } }
    @Test @DisplayName("세션은 발급 후 정확히 48시간에 만료된다") fun expiration() { val result=login();assertThat(Instant.parse(result["expiresAt"].asText())).isEqualTo(clock.instant().plusSeconds(172800));(clock as MutableClock).current=clock.instant().plusSeconds(172799);assertThat(call("GET","/api/v1/investor/me").status).isEqualTo(200);Steps.step("When 발급 후 정확히 48시간"){(clock as MutableClock).current=clock.instant().plusSeconds(1)};failure(call("GET","/api/v1/investor/me"),401,"SESSION_EXPIRED") }
    @Test @DisplayName("비활성 코드와 없는 코드는 같은 로그인 오류다") fun disabled() { db.jdbc.update("UPDATE accounts SET deleted_at=? WHERE id='inv_001'",clock.instant().toString());tokens["investor"]=json(call("GET","/api/v1/investor/csrf"))["csrfToken"].asText();failure(call("POST","/api/v1/investor/session","{\"code\":\"0037\"}"),401,"AUTHENTICATION_FAILED") }
    @Test @DisplayName("유효 세션이라도 연결 계정이 비활성화되면 접근을 거절한다") fun disabledSession() { login();db.jdbc.update("UPDATE accounts SET deleted_at=? WHERE id='inv_001'",clock.instant().toString());failure(call("GET","/api/v1/investor/me"),403,"FORBIDDEN") }
    @Test @DisplayName("이미 로그아웃한 상태도 CSRF 준비 후 204로 처리한다") fun idempotentLogout() { tokens["investor"]=json(call("GET","/api/v1/investor/csrf"))["csrfToken"].asText();Steps.step("When 미로그인 상태에서 로그아웃") { assertThat(call("DELETE","/api/v1/investor/session").status).isEqualTo(204) } }
    @Test @DisplayName("CSRF 토큰 누락·다른 Origin은 투자를 변경하지 않는다") fun csrfRejected() { login();failure(call("POST","/api/v1/investor/investments","{\"teamId\":2,\"amount\":100000}",UUID.randomUUID().toString(),csrf=false),403,"CSRF_INVALID");failure(call("POST","/api/v1/investor/investments","{\"teamId\":2,\"amount\":100000}",UUID.randomUUID().toString(),origin="https://other.example"),403,"CSRF_INVALID");assertThat(service.investor("inv_001").balance).isEqualTo(1000000) }
    @Test @DisplayName("로그인 전 토큰은 로그인 성공 후 교체되어 재사용할 수 없다") fun csrfRotation() { tokens["investor"]=json(call("GET","/api/v1/investor/csrf"))["csrfToken"].asText();val old=tokens["investor"]!!;val r=call("POST","/api/v1/investor/session","{\"code\":\"0037\"}");assertThat(json(r)["csrfToken"].asText()).isNotEqualTo(old);failure(invest(2,100000),403,"CSRF_INVALID") }
    @Test @DisplayName("로그인 준비 컨텍스트는 10분 만료 후 사용할 수 없다") fun preauthExpires() { tokens["investor"]=json(call("GET","/api/v1/investor/csrf"))["csrfToken"].asText();(clock as MutableClock).current=clock.instant().plusSeconds(600);failure(call("POST","/api/v1/investor/session","{\"code\":\"0037\"}"),403,"CSRF_INVALID") }
    @ParameterizedTest(name="잘못된 로그인 본문 {0}은 인증에 사용하지 않는다") @ValueSource(strings=["{","{}","[]","null","{\"code\":37}","{\"code\":\"0037\",\"teamId\":1}"])
    fun badJson(body: String) { tokens["investor"]=json(call("GET","/api/v1/investor/csrf"))["csrfToken"].asText();failure(call("POST","/api/v1/investor/session",body),400,"INVALID_REQUEST");assertThat(service.overview().total.totalIssuedAmount).isZero() }
    @ParameterizedTest(name="잘못된 코드 형식 {0}은 422") @ValueSource(strings=["037","00037"," 037","００３７","abcd"])
    fun invalidCode(code: String) { tokens["investor"]=json(call("GET","/api/v1/investor/csrf"))["csrfToken"].asText();failure(call("POST","/api/v1/investor/session","{\"code\":\"$code\"}"),422,"VALIDATION_FAILED") }
    @ParameterizedTest(name="정상 금액 {0}원 투자 → 201 및 동일 금액 차감") @ValueSource(ints=[100000,200000,300000,400000,500000,600000,700000])
    fun allAmounts(amount: Int) { login();val r=Steps.step("When 팀02에 $amount 원 투자"){invest(2,amount)};Steps.step("Then 영수증·잔액·유치금·버전이 함께 반영"){assertThat(r.status).isEqualTo(201);assertThat(service.investor("inv_001").balance).isEqualTo(1000000-amount);assertThat(service.dashboard().totalInvestedAmount).isEqualTo(amount.toLong());assertThat(service.dashboard().snapshotVersion).isEqualTo(1)} }
    @ParameterizedTest(name="투자 금액 {0}원 거절 후 잔액과 집계 불변") @ValueSource(ints=[0,99999,100001,150000,700001,800000])
    fun rejectedAmounts(amount: Int) { login();failure(invest(2,amount),422,"VALIDATION_FAILED");assertThat(service.investor("inv_001").balance).isEqualTo(1000000);assertThat(service.dashboard().snapshotVersion).isZero() }
    @Test @DisplayName("소수·문자열·boolean 금액은 정수로 강제 변환하지 않는다") fun strictAmount() { login();for(value in listOf("100000.5","\"100000\"","true")){failure(call("POST","/api/v1/investor/investments","{\"teamId\":2,\"amount\":$value}",UUID.randomUUID().toString()),400,"INVALID_REQUEST")} }
    @Test @DisplayName("본인 팀 직접 API 투자는 403이고 돈은 그대로다") fun selfInvestment() { login();failure(invest(1,700000),403,"SELF_INVESTMENT_FORBIDDEN");assertThat(service.investor("inv_001").balance).isEqualTo(1000000);assertThat(service.history("inv_001").items).isEmpty() }
    @Test @DisplayName("같은 팀 재투자는 409이고 원 거래 한 건만 유지한다") fun duplicateTeam() { login();invest(2,100000);failure(invest(2,200000),409,"ALREADY_INVESTED");assertThat(service.investor("inv_001").balance).isEqualTo(900000);assertThat(service.dashboard().totalInvestedAmount).isEqualTo(100000) }
    @Test @DisplayName("70만원 투자 후 잔액 초과는 거절하고 30만원 투자로 전액 소진한다") fun exhaustion() { login();invest(2,700000);failure(invest(3,400000),409,"INSUFFICIENT_BALANCE");assertThat(invest(3,300000).status).isEqualTo(201);assertThat(service.investor("inv_001").spendingStatus).isEqualTo("COMPLETED");assertThat(service.history("inv_001").items.map{it.teamId}).containsExactly(3,2);assertThat(service.teams("inv_001").teams.first{it.teamId==4}.investmentState).isEqualTo("BALANCE_EXHAUSTED") }
    @Test @DisplayName("잔액이 0이어도 소개·내역은 조회할 수 있다") fun readableAfterZero() { login();invest(2,700000);invest(3,300000);assertThat(call("GET","/api/v1/investor/teams/4").status).isEqualTo(200);assertThat(call("GET","/api/v1/investor/investments").status).isEqualTo(200);failure(invest(4,100000),409,"INSUFFICIENT_BALANCE") }
    @Test @DisplayName("같은 요청 키 재전송은 현재 잔액 0이어도 동일 영수증을 반환한다") fun idempotency() { login();val key=UUID.randomUUID().toString();val first=invest(2,700000,key);invest(3,300000);val second=Steps.step("When 잔액 0에서 첫 요청을 그대로 재전송"){invest(2,700000,key)};assertThat(second.status).isEqualTo(201);assertThat(json(second)).isEqualTo(json(first));assertThat(service.history("inv_001").count).isEqualTo(2) }
    @Test @DisplayName("같은 키에 팀이나 금액을 바꾸면 409이고 추가 차감하지 않는다") fun reusedKey() { login();val key=UUID.randomUUID().toString();invest(2,100000,key);failure(invest(3,100000,key),409,"IDEMPOTENCY_KEY_REUSED");failure(invest(2,200000,key),409,"IDEMPOTENCY_KEY_REUSED");assertThat(service.investor("inv_001").balance).isEqualTo(900000) }
    @Test @DisplayName("확정 거절도 같은 키로 조회·재전송하면 같은 결과다") fun rejectionRecovery() { login();val key=UUID.randomUUID().toString();invest(1,100000,key);val r=call("GET","/api/v1/investor/investment-requests/$key");assertThat(r.status).isEqualTo(200);assertThat(json(r)["status"].asText()).isEqualTo("REJECTED");failure(invest(1,100000,key),403,"SELF_INVESTMENT_FORBIDDEN") }
    @Test @DisplayName("입력·CSRF 오류는 요청 키를 소비하지 않는다") fun prevalidationDoesNotConsume() { login();val key=UUID.randomUUID().toString();failure(invest(2,150000,key),422,"VALIDATION_FAILED");assertThat(invest(2,100000,key).status).isEqualTo(201) }
    @Test @DisplayName("타인 요청 키와 없는 요청 키는 모두 404로 조회된다") fun privateKeys() { login();val key=UUID.randomUUID().toString();invest(2,100000,key);login(code="0038");failure(call("GET","/api/v1/investor/investment-requests/$key"),404,"REQUEST_RESULT_NOT_FOUND") }
    @Test @DisplayName("타인 거래 ID와 없는 거래 ID는 모두 404다") fun privateTransactions() { login();val id=json(invest(2,100000))["investment"]["investmentId"].asText();login(code="0038");failure(call("GET","/api/v1/investor/investments/$id"),404,"RESOURCE_NOT_FOUND");failure(call("GET","/api/v1/investor/investments/not-there"),404,"RESOURCE_NOT_FOUND") }
    @Test @DisplayName("완료 상세는 과거 영수증 잔액이 아니라 현재 잔액을 반환한다") fun currentBalance() { login();val first=json(invest(2,700000));invest(3,300000);val d=json(call("GET","/api/v1/investor/investments/"+first["investment"]["investmentId"].asText()));assertThat(first["balanceAfterInvestment"].asInt()).isEqualTo(300000);assertThat(d["currentBalance"].asInt()).isZero() }
    @Test @DisplayName("선택 필터 생략은 성공하고 미지급·미소진·완료는 별도 조회된다") fun filters() { login();invest(2,700000);login("admin","4821");assertThat(json(call("GET","/api/v1/admin/investors"))["count"].asInt()).isEqualTo(5);assertThat(json(call("GET","/api/v1/admin/investors?spendingStatus=UNSPENT"))["count"].asInt()).isEqualTo(1);assertThat(json(call("GET","/api/v1/admin/investors?spendingStatus=NOT_ACTIVATED"))["count"].asInt()).isEqualTo(4);assertThat(json(call("GET","/api/v1/admin/investors?kind=STAFF&teamId=1"))["count"].asInt()).isZero() }
    @ParameterizedTest(name="잘못되거나 중복된 선택 필터 {0}은 422") @ValueSource(strings=["kind=BAD","kind=","spendingStatus=BAD","teamId=0","teamId=21","teamId=abc","kind=STAFF&kind=PARTICIPANT","unknown=true"])
    fun badFilters(query: String) { login("admin","4821");failure(call("GET","/api/v1/admin/investors?$query"),422,"VALIDATION_FAILED") }
    @Test @DisplayName("별칭·ID 검색과 역할·소속 필터는 AND로 조합한다") fun search() { login("admin","4821");val d=json(call("GET","/api/v1/admin/investors?q=inv_001&kind=PARTICIPANT&teamId=1"));assertThat(d["count"].asInt()).isEqualTo(1);assertThat(d["items"][0]["investorId"].asText()).isEqualTo("inv_001") }
    @Test @DisplayName("관리자는 미로그인 투자자의 별칭도 수정하고 돈은 바꾸지 않는다") fun alias() { login("admin","4821");val r=call("PATCH","/api/v1/admin/investors/inv_001","{\"alias\":\"파란 명찰\"}");assertThat(r.status).isEqualTo(200);assertThat(service.investor("inv_001").alias).isEqualTo("파란 명찰");assertThat(service.investor("inv_001").spendingStatus).isEqualTo("NOT_ACTIVATED");failure(call("PATCH","/api/v1/admin/investors/inv_001","{\"alias\":\" 바꾼 이름\"}"),422,"VALIDATION_FAILED") }
    @Test @DisplayName("별칭 수정에 잔액 필드를 끼워 보내면 거절한다") fun noMoneyEdits() { login("admin","4821");failure(call("PATCH","/api/v1/admin/investors/inv_001","{\"alias\":\"이름\",\"balance\":1000000}"),400,"INVALID_REQUEST");assertThat(service.investor("inv_001").balance).isZero() }
    @Test @DisplayName("공개 대시보드는 미로그인도 볼 수 있고 개인 정보는 없다") fun publicPrivacy() { val r=call("GET","/api/v1/public/dashboard");assertThat(r.status).isEqualTo(200);val d=json(r);assertThat(d["teams"].size()).isEqualTo(20);assertThat(d.toString()).doesNotContain("investorId","alias","balance","csrf","code");assertThat(d["teams"].map{it["rank"].asInt()}).containsOnly(1) }
    @Test @DisplayName("20개의 동시 최초 로그인에도 100만원 지급은 한 번뿐이다") fun concurrentGrant() {
        val pool=Executors.newFixedThreadPool(8);val gate=CountDownLatch(1)
        try { val futures=(1..20).map { pool.submit<Int> { gate.await();val prep=mvc.perform(MockMvcRequestBuilders.get("/api/v1/investor/csrf")).andReturn().response;val token=json(prep)["csrfToken"].asText();mvc.perform(MockMvcRequestBuilders.post("/api/v1/investor/session").cookie(*prep.cookies).header("Origin","http://localhost:8080").header("X-CSRF-Token",token).contentType("application/json").content("{\"code\":\"0037\"}")).andReturn().response.status } };Steps.step("When 20개 기기가 동시에 최초 로그인"){gate.countDown()};Steps.step("Then 모두 성공하지만 지급 1건·세션 20개"){assertThat(futures.map{it.get(20,TimeUnit.SECONDS)}).containsOnly(200);assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM grants",Int::class.java)).isEqualTo(1);assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sessions",Int::class.java)).isEqualTo(20);assertThat(service.investor("inv_001").balance).isEqualTo(1000000)} } finally { pool.shutdownNow() }
    }
    @Test @DisplayName("다른 요청 키로 같은 팀 동시 투자 20회 → 확정 거래는 한 건") fun concurrentSameTeam() { login();val results=concurrent((1..20).map{2 to 100000});assertThat(results.count{it=="SUCCESS"}).isEqualTo(1);assertThat(results.count{it=="ALREADY_INVESTED"}).isEqualTo(19);assertThat(service.investor("inv_001").balance).isEqualTo(900000);assertThat(service.history("inv_001").count).isEqualTo(1) }
    @Test @DisplayName("서로 다른 팀에 70만원 동시 투자 → 한 건 성공·한 건 잔액 부족") fun concurrentBalance() { login();val results=concurrent(listOf(2 to 700000,3 to 700000));assertThat(results).containsExactlyInAnyOrder("SUCCESS","INSUFFICIENT_BALANCE");assertThat(service.investor("inv_001").balance).isEqualTo(300000);assertThat(service.dashboard().totalInvestedAmount).isEqualTo(700000) }
    private fun concurrent(inputs: List<Pair<Int,Int>>): List<String> { val pool=Executors.newFixedThreadPool(8);val gate=CountDownLatch(1);try { val fs=inputs.map { (team,amount)->pool.submit<String>{gate.await();try{service.create("inv_001",UUID.randomUUID().toString(),InvestmentInput(team,amount));"SUCCESS"}catch(e: ApiException){e.code}} };Steps.step("When 독립 스레드에서 동시에 투자 요청"){gate.countDown()};return fs.map{it.get(20,TimeUnit.SECONDS)} } finally {pool.shutdownNow()} }
    @Test @DisplayName("같은 요청 키 동시 전송 20회 → 같은 영수증과 한 건 차감") fun concurrentSameKey() { login();val key=UUID.randomUUID().toString();val pool=Executors.newFixedThreadPool(8);try { val fs=(1..20).map { pool.submit<InvestmentReceipt>{service.create("inv_001",key,InvestmentInput(2,700000))} };val receipts=fs.map{it.get(20,TimeUnit.SECONDS)};assertThat(receipts.map{it.investment.investmentId}.distinct()).hasSize(1);assertThat(service.investor("inv_001").balance).isEqualTo(300000);assertThat(service.history("inv_001").count).isEqualTo(1) } finally {pool.shutdownNow()} }
    @Test @DisplayName("거래 저장 중 실패하면 차감·거래·버전·요청 결과가 모두 롤백된다") fun atomicRollback() {
        login();db.jdbc.execute("CREATE TRIGGER fail_investment BEFORE INSERT ON investments BEGIN SELECT RAISE(ABORT,'test fault'); END")
        try { val key=UUID.randomUUID().toString();Steps.step("When 차감 이후 거래 INSERT를 강제로 실패시킨다"){failure(invest(2,700000,key),503,"SERVICE_UNAVAILABLE")};Steps.step("Then 잔액 100만원·거래 0·버전 0·결과 기록 0"){assertThat(service.investor("inv_001").balance).isEqualTo(1000000);assertThat(service.history("inv_001").count).isZero();assertThat(service.dashboard().snapshotVersion).isZero();assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM requests",Int::class.java)).isZero()} } finally {db.jdbc.execute("DROP TRIGGER fail_investment")}
    }
    @Test @DisplayName("DB 유니크 제약이 서비스 우회 중복 거래도 차단한다") fun databaseConstraint() { login();invest(2,100000);assertThatThrownBy { db.jdbc.update("INSERT INTO investments VALUES('duplicate','inv_001',2,100000,?,800000,2)",clock.instant().toString()) }.isInstanceOf(org.springframework.dao.DataAccessException::class.java);assertThat(service.history("inv_001").count).isEqualTo(1) }
    @Test @DisplayName("서버 요청 제한은 로그인에 적용하지 않고 보호 조회에 429를 반환한다") fun rateContract() { login();repeat(180){assertThat(call("GET","/api/v1/investor/me").status).isEqualTo(200)};val r=call("GET","/api/v1/investor/me");failure(r,429,"RATE_LIMITED");assertThat(r.getHeader("Retry-After")).isEqualTo("60");assertThat(call("GET","/api/v1/investor/csrf").status).isEqualTo(200) }
    @Test @DisplayName("본문 없는 로그아웃 계약을 위반하면 400이고 세션은 유지된다") fun logoutBody() { login();failure(call("DELETE","/api/v1/investor/session","{}"),400,"INVALID_REQUEST");assertThat(call("GET","/api/v1/investor/me").status).isEqualTo(200) }
    @Test @DisplayName("투자 요청 키 누락·잘못된 UUID는 입력 오류이고 잔액 불변이다") fun badRequestKey() { login();failure(call("POST","/api/v1/investor/investments","{\"teamId\":2,\"amount\":100000}"),422,"VALIDATION_FAILED");failure(invest(2,100000,"not-a-uuid"),422,"VALIDATION_FAILED");assertThat(service.investor("inv_001").balance).isEqualTo(1000000) }
    @Test @DisplayName("없는 경로는 404, 허용하지 않는 메서드는 405다") fun routingErrors() { failure(call("GET","/api/v1/not-found"),404,"RESOURCE_NOT_FOUND");failure(call("PUT","/api/v1/investor/session","{}"),405,"METHOD_NOT_ALLOWED") }
    @Test @DisplayName("존재하지 않는 투자자 상세·별칭 수정은 404다") fun unknownInvestor() { login("admin","4821");failure(call("GET","/api/v1/admin/investors/missing"),404,"RESOURCE_NOT_FOUND");failure(call("PATCH","/api/v1/admin/investors/missing","{\"alias\":\"별칭\"}"),404,"RESOURCE_NOT_FOUND") }
    @Test @DisplayName("컨트롤러에서 생성된 OpenAPI는 합의된 20개 작업과 오류를 담는다") fun generatedSpec() { val r=call("GET","/v3/api-docs");assertThat(r.status).isEqualTo(200);val d=json(r);val paths=d["paths"];assertThat(paths.fields().asSequence().sumOf{it.value.size()}).isEqualTo(20);assertThat(paths["/api/v1/investor/investments"]["post"]["responses"]["409"]["content"]["application/json"]["examples"]["ALREADY_INVESTED"]).isNotNull();assertThat(d["components"]["schemas"]["ApiError"]).isNotNull();assertThat(paths.toString()).doesNotContain("/__test","/event");paths.fields().forEachRemaining { path -> path.value.fields().forEachRemaining { operation -> assertThat(operation.value["description"].asText()).contains("**사용 시점**", "**성공 결과").hasSizeGreaterThan(200) } } }
}
