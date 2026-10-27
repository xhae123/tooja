package com.tooja

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.qameta.allure.Allure
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class InvestorRankingApiContractTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var db: Database
    @Autowired lateinit var investments: InvestmentService
    @Autowired lateinit var rankings: InvestorRankingService
    @Autowired lateinit var rates: RateLimits
    @MockitoSpyBean lateinit var jdbc: JdbcTemplate
    private val cookies = mutableMapOf<String,Cookie>()
    private val path = "/api/v1/admin/investor-rankings"

    @BeforeEach fun given() {
        db.reset()
        rates.clear()
        cookies.clear()
        Allure.label("parentSuite","통합 테스트")
        Allure.label("suite","성과 순위 API + 실제 ${if(db.mysql) "MySQL" else "SQLite"}")
        Steps.step("Given 참가자 4명·운영팀 투자자 1명·관리자 1명이 등록된 실제 DB")
    }

    private fun json(r: MockHttpServletResponse): JsonNode = mapper.readTree(r.contentAsString)
    private fun getRanking(query: String=""): MockHttpServletResponse = mvc.perform(get(path+query).apply {
        if(cookies.isNotEmpty()) cookie(*cookies.values.toTypedArray())
    }).andReturn().response
    private fun signIn(role: String="admin", code: String="4821") {
        val prep = mvc.perform(get("/api/v1/$role/csrf")).andReturn().response
        val session = mvc.perform(post("/api/v1/$role/session").cookie(*prep.cookies)
            .header("Origin","http://localhost:8080").header("X-CSRF-Token",json(prep)["csrfToken"].asText())
            .contentType("application/json").content("{\"code\":\"$code\"}")).andReturn().response
        assertThat(session.status).isEqualTo(200)
        session.cookies.forEach { cookies[it.name] = it }
    }
    private fun invest(id: String, team: Int, amount: Int) = investments.create(id,UUID.randomUUID().toString(),InvestmentInput(team,amount))
    private fun error(response: MockHttpServletResponse, status: Int, code: String) {
        assertThat(response.status).isEqualTo(status)
        assertThat(json(response)["error"]["code"].asText()).isEqualTo(code)
        assertThat(response.getHeader("X-Request-ID")).isEqualTo(json(response)["requestId"].asText())
    }
    private fun item(response: JsonNode,id: String): JsonNode = response["items"].first { it["investorId"].asText()==id }
    private fun completeFixture() {
        invest("inv_001",2,700000);invest("inv_001",3,300000)
        invest("staff_001",2,700000);invest("staff_001",4,300000)
    }

    @Test @DisplayName("Given 관리자·미투자 명단 When 필터 없이 조회 Then 전체 5명·0점·순위 null")
    fun defaultAll() {
        signIn()
        val response = Steps.step("When group 없이 GET 요청, CSRF·Origin 헤더도 보내지 않음") { getRanking() }
        Steps.step("Then 전체 5명이며 ALL 명시 요청과 같은 결과, 개인정보·잔액은 제외") {
            assertThat(response.status).isEqualTo(200)
            val result=json(response)
            assertThat(result["group"].asText()).isEqualTo("ALL")
            assertThat(result["count"].asInt()).isEqualTo(5)
            assertThat(result["snapshotVersion"].asLong()).isZero()
            assertThat(Instant.parse(result["asOf"].asText())).isNotNull()
            result["items"].forEach { row ->
                assertThat(row["rank"].isNull).isTrue()
                assertThat(row["score"].asInt()).isZero()
                assertThat(row["contributions"].size()).isZero()
                assertThat(row.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("rank","investorId","alias","kind","teamId","score","contributions")
            }
            assertThat(result.toString()).doesNotContain("balance","issuedAmount","spendingStatus","code","csrf")
            assertThat(json(getRanking("?group=ALL"))["items"]).isEqualTo(result["items"])
        }
    }

    @ParameterizedTest(name="Given 전체 투자 내역 When group={0} Then 그룹만 변경·점수 유지·순위 재계산")
    @ValueSource(strings=["ALL","PARTICIPANT","STAFF"])
    fun groupFiltering(group: String) {
        completeFixture()
        // The staff investor's extra 30만원 makes team4 rank2 and team3 rank3.
        invest("inv_002",4,100000)
        signIn()
        val all=json(getRanking())
        val result=Steps.step("When group=$group 으로 조회") { json(getRanking("?group=$group")) }
        Steps.step("Then 그룹 인원과 순위만 바뀌고 점수·계산 근거는 전체 조회와 동일") {
            assertThat(result["group"].asText()).isEqualTo(group)
            assertThat(result["count"].asInt()).isEqualTo(when(group) { "PARTICIPANT" -> 4; "STAFF" -> 1; else -> 5 })
            result["items"].forEach { row ->
                val original=item(all,row["investorId"].asText())
                assertThat(row["score"]).isEqualTo(original["score"])
                assertThat(row["contributions"]).isEqualTo(original["contributions"])
                if(group!="ALL") assertThat(row["kind"].asText()).isEqualTo(group)
            }
            assertThat(item(all,"inv_001")["rank"].asInt()).isEqualTo(2)
            assertThat(item(all,"inv_002")["rank"].asInt()).isEqualTo(3)
            if(group=="PARTICIPANT") {
                assertThat(item(result,"inv_001")["rank"].asInt()).isEqualTo(1)
                assertThat(item(result,"inv_002")["rank"].asInt()).isEqualTo(2)
            }
            if(group=="STAFF") {
                assertThat(result["items"][0]["teamId"].isNull).isTrue()
                assertThat(result["items"][0]["rank"].asInt()).isEqualTo(1)
            }
        }
    }

    @Test @DisplayName("Given 전액 음수·일부 투자 양수·미투자 When 조회 Then 투자자는 점수순, 미투자만 null")
    fun partialAndFullInvestorsRankTogether() {
        // Direct fixtures represent a valid committed ledger across 20 teams, while
        // keeping setup independent of the scoring implementation.
        seedLedgerForNegativeScore()
        signIn()
        val result=Steps.step("When 참가자의 현재 성과 순위를 조회") { json(getRanking("?group=PARTICIPANT")) }
        Steps.step("Then 일부 투자자가 1등, 전액 투자자는 음수여도 2등, 미투자자만 null") {
            val completed=item(result,"inv_001")
            val partial=item(result,"inv_002")
            assertThat(completed["score"].asInt()).isNegative()
            assertThat(completed["rank"].asInt()).isEqualTo(2)
            assertThat(partial["score"].asInt()).isPositive()
            assertThat(partial["rank"].asInt()).isEqualTo(1)
            assertThat(result["items"].take(2).map { it["investorId"].asText() }).containsExactly("inv_002","inv_001")
            assertThat(result["items"].drop(2).all { it["rank"].isNull && it["contributions"].isEmpty }).isTrue()
        }
    }

    @Test @DisplayName("Given 미투자자 When 첫 10만원 투자 후 성과 조회 Then 잔액 90만원이어도 1등")
    fun firstInvestmentCreatesRank() {
        signIn()
        val before=json(getRanking())
        Steps.step("Given 투자 전에는 점수 0·순위 null·내역 없음") {
            assertThat(item(before,"inv_001")["rank"].isNull).isTrue()
            assertThat(item(before,"inv_001")["contributions"].isEmpty).isTrue()
        }
        val result=Steps.step("When 10만원을 처음 투자하고 성과 API를 다시 조회") {
            invest("inv_001",2,100000)
            json(getRanking())
        }
        Steps.step("Then 10점·1등으로 바뀌고 나머지 미투자자는 null, 잔액·소진 상태는 응답에서 제외") {
            val row=item(result,"inv_001")
            assertThat(row["rank"].asInt()).isEqualTo(1)
            assertThat(row["score"].asInt()).isEqualTo(10)
            assertThat(row["contributions"].single()["investedAmount"].asInt()).isEqualTo(100000)
            assertThat(result["items"].drop(1).all { it["rank"].isNull }).isTrue()
            assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id='inv_001'",Int::class.java)).isEqualTo(900000)
            assertThat(result.toString()).doesNotContain("balance","spendingStatus")
        }
    }

    private fun seedLedgerForNegativeScore() {
        db.write {
            // Add one funded fixture investor per team to establish descending
            // popularity. Each investment is <=70만원 and balances match the ledger.
            var version=0
            fun account(id: String) {
                jdbc.update("INSERT INTO accounts(id,code_hash,role,kind,team_id,alias,balance) VALUES(?,?,'INVESTOR','STAFF',NULL,?,1000000)",id,"test-hash-$id",id)
                jdbc.update("INSERT INTO grants VALUES(?,1000000,?)",id,Instant.now().toString())
            }
            fun transaction(id: String,team: Int,amount: Int) {
                jdbc.update("UPDATE accounts SET balance=balance-? WHERE id=?",amount,id)
                val balance=jdbc.queryForObject("SELECT balance FROM accounts WHERE id=?",Int::class.java,id)!!
                jdbc.update("INSERT INTO investments VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,team,amount,Instant.now().toString(),balance,++version)
            }
            for(team in 1..18) repeat(3) { n -> val id="fixture-$team-$n";account(id);transaction(id,team,700000) }
            transaction("inv_001",19,700000);transaction("inv_001",20,300000)
            transaction("inv_002",1,100000)
            jdbc.update("UPDATE metadata SET version=? WHERE id=1",version)
            true
        }
    }

    @Test @DisplayName("Given 전액 투자 3명 중 2명 동점 When 조회 Then 개인 순위 1·1·3")
    fun tiedInvestors() {
        invest("inv_001",2,700000);invest("inv_001",6,300000)
        invest("inv_002",2,700000);invest("inv_002",6,300000)
        invest("inv_003",7,700000);invest("inv_003",8,300000)
        signIn()
        val result=Steps.step("When 참가자 순위를 조회") { json(getRanking("?group=PARTICIPANT")) }
        Steps.step("Then 전액 투자자 순위는 1·1·3, 미투자자는 null") {
            assertThat(result["items"].take(3).map { it["rank"].asInt() }).containsExactly(1,1,3)
            assertThat(result["items"][3]["rank"].isNull).isTrue()
        }
    }

    @Test @DisplayName("Given 다른 사람의 추가 투자 When 다시 조회 Then 내 투자 없이도 점수 변경·버전 증가")
    fun recalculatesAfterInvestment() {
        invest("inv_001",2,700000);invest("inv_001",6,300000)
        signIn()
        val before=json(getRanking())
        Steps.step("When 운영팀 투자자가 팀6에 70만원 추가 투자") { invest("staff_001",6,700000) }
        val after=json(getRanking())
        Steps.step("Then 내 점수 97에서 93으로 변경, 투자 버전 2→3") {
            assertThat(item(before,"inv_001")["score"].asInt()).isEqualTo(97)
            assertThat(item(after,"inv_001")["score"].asInt()).isEqualTo(93)
            assertThat(after["snapshotVersion"].asLong()).isEqualTo(before["snapshotVersion"].asLong()+1)
            assertThat(investments.history("inv_001").count).isEqualTo(2)
        }
    }

    @Test @DisplayName("Given 팀 공동 1등 When 개인 점수 조회 Then 두 팀 모두 10점 배점")
    fun tiedTeams() {
        invest("inv_001",2,500000);invest("inv_001",6,500000)
        signIn()
        val row=Steps.step("When 현재 점수 조회") { item(json(getRanking()),"inv_001") }
        Steps.step("Then 100점, 두 팀 모두 공동 1등·10점") {
            assertThat(row["score"].asInt()).isEqualTo(100)
            assertThat(row["contributions"].map { it["teamRank"].asInt() }).containsOnly(1)
            assertThat(row["contributions"].map { it["pointsPerUnit"].asInt() }).containsOnly(10)
        }
    }

    @ParameterizedTest(name="Given 잘못된 필터 {0} When 조회 Then 422 VALIDATION_FAILED")
    @ValueSource(strings=["?group=","?group=all","?group=GROUP_A","?group=%20ALL","?group=ALL%20","?group=null","?group=ALL&group=STAFF","?group=ALL&group=ALL","?teamId=1","?kind=STAFF","?unexpected=1"])
    fun invalidQueries(query: String) {
        signIn()
        val before=jdbc.queryForObject("SELECT version FROM metadata WHERE id=1",Long::class.java)
        val response=Steps.step("When 허용하지 않는 필터로 조회") { getRanking(query) }
        Steps.step("Then 입력 오류이며 투자 버전 불변") {
            error(response,422,"VALIDATION_FAILED")
            assertThat(jdbc.queryForObject("SELECT version FROM metadata WHERE id=1",Long::class.java)).isEqualTo(before)
        }
    }

    @Test @DisplayName("Given 로그인하지 않음 When 순위 조회 Then 401, 잘못된 필터보다 인증 먼저")
    fun unauthenticated() {
        Steps.step("Given 관리자·투자자 로그인 없음")
        val response=Steps.step("When 잘못된 필터와 함께 조회") { getRanking("?group=invalid") }
        Steps.step("Then 필터 오류 대신 SESSION_EXPIRED") { error(response,401,"SESSION_EXPIRED") }
    }

    @Test @DisplayName("Given 투자자 세션만 있음 When 관리자 순위 조회 Then 403")
    fun investorForbidden() {
        signIn("investor","0037")
        val response=Steps.step("When 관리자 성과 API 호출") { getRanking() }
        Steps.step("Then FORBIDDEN, 개인 목록 비공개") { error(response,403,"FORBIDDEN") }
    }

    @Test @DisplayName("Given 관리자 세션 만료 When 조회 Then 401")
    fun expiredSession() {
        signIn()
        jdbc.update("UPDATE sessions SET expires_at='2000-01-01T00:00:00Z' WHERE role='ADMIN'")
        val response=Steps.step("When 만료된 세션으로 조회") { getRanking() }
        Steps.step("Then SESSION_EXPIRED") { error(response,401,"SESSION_EXPIRED") }
    }

    @Test @DisplayName("Given 비활성 관리자 When 조회 Then 403")
    fun deletedAdmin() {
        signIn()
        jdbc.update("UPDATE accounts SET deleted_at=? WHERE id='admin_001'",Instant.now().toString())
        val response=Steps.step("When 비활성 관리자 세션으로 조회") { getRanking() }
        Steps.step("Then FORBIDDEN") { error(response,403,"FORBIDDEN") }
    }

    @Test @DisplayName("Given 관리자·삭제 투자자 When 조회 Then 두 계정은 목록에서 제외")
    fun deletedInvestorExcluded() {
        invest("inv_001",2,700000)
        jdbc.update("UPDATE accounts SET deleted_at=? WHERE id='inv_001'",Instant.now().toString())
        signIn()
        val result=Steps.step("When 전체 순위 조회") { json(getRanking()) }
        Steps.step("Then 4명 반환, 관리자·삭제 투자자는 제외, 저장된 투자 원장은 보존") {
            assertThat(result["count"].asInt()).isEqualTo(4)
            assertThat(result["items"].map { it["investorId"].asText() }).doesNotContain("admin_001","inv_001")
            assertThat(investments.dashboard().totalInvestedAmount).isEqualTo(700000)
        }
    }

    @Test @DisplayName("Given 대상 그룹에 투자자 없음 When 조회 Then 200·빈 목록")
    fun emptyGroup() {
        jdbc.update("UPDATE accounts SET deleted_at=? WHERE kind='STAFF'",Instant.now().toString())
        signIn()
        val response=Steps.step("When 운영팀 그룹 조회") { getRanking("?group=STAFF") }
        Steps.step("Then 404 대신 빈 성공 결과") {
            assertThat(response.status).isEqualTo(200)
            assertThat(json(response)["count"].asInt()).isZero()
            assertThat(json(response)["items"].size()).isZero()
        }
    }

    @Test @DisplayName("Given 투자 중지 When 반복 조회 Then 조회 성공·no-store·업무 데이터 불변")
    fun pausedAndReadOnly() {
        completeFixture()
        investments.updateInvestmentStatus(InvestmentMode.PAUSED)
        signIn()
        val tables=listOf("accounts","grants","investments","requests","metadata","investment_control","sessions","contexts")
        val before=tables.associateWith { jdbc.queryForList("SELECT * FROM $it") }
        val responses=Steps.step("When 중지 중 3회 조회") { (1..3).map { getRanking() } }
        Steps.step("Then 모두 200·no-store, 돈·거래·접수 상태·세션 모두 불변") {
            responses.forEach { assertThat(it.status).isEqualTo(200);assertThat(it.getHeader("Cache-Control")).isEqualTo("no-store") }
            tables.forEach { assertThat(jdbc.queryForList("SELECT * FROM $it")).isEqualTo(before[it]) }
        }
    }

    @Test @DisplayName("Given 계정당 조회 한도 180회 소진 When 조회 Then 429·Retry-After")
    fun rateLimited() {
        signIn()
        repeat(180) { assertThat(getRanking().status).isEqualTo(200) }
        val response=Steps.step("When 60초 안에 181번째 조회") { getRanking() }
        Steps.step("Then RATE_LIMITED와 재시도 시간") { error(response,429,"RATE_LIMITED");assertThat(response.getHeader("Retry-After")?.toInt()).isPositive() }
    }

    @Test @DisplayName("Given DB 읽기 실패 When 조회 Then 503, 빈 결과로 위장하지 않음")
    fun databaseFailure() {
        signIn()
        doThrow(org.springframework.dao.DataAccessResourceFailureException("test read failure"))
            .`when`(jdbc).queryForList("SELECT id,name FROM teams ORDER BY id")
        val response=Steps.step("When 저장소를 읽을 수 없음") { getRanking() }
        Steps.step("Then SERVICE_UNAVAILABLE·Retry-After 5") { error(response,503,"SERVICE_UNAVAILABLE");assertThat(response.getHeader("Retry-After")).isEqualTo("5") }
    }

    @Test @DisplayName("Given 예상하지 못한 실행 오류 When 조회 Then 500, 0점·빈 결과로 위장하지 않음")
    fun unexpectedFailure() {
        signIn()
        doThrow(IllegalStateException("test unexpected failure"))
            .`when`(jdbc).queryForList("SELECT id,name FROM teams ORDER BY id")
        val response=Steps.step("When 예상하지 못한 실행 오류 발생") { getRanking() }
        Steps.step("Then INTERNAL_ERROR, 내부 예외 메시지는 노출하지 않음") {
            error(response,500,"INTERNAL_ERROR")
            assertThat(response.contentAsString).doesNotContain("test unexpected failure")
        }
    }

    @ParameterizedTest(name="Given 기존 투자 있음={0} When 읽기 중 투자 커밋 Then 점수·내역·순위 자격·버전 모두 같은 스냅샷")
    @ValueSource(booleans=[true,false])
    fun concurrentInvestmentSnapshot(alreadyInvested: Boolean) {
        if(alreadyInvested) invest("inv_001",2,700000)
        signIn()
        val captured=CountDownLatch(1)
        val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        doAnswer { invocation ->
            val rows=invocation.callRealMethod()
            captured.countDown()
            check(release.await(15,TimeUnit.SECONDS))
            rows
        }.`when`(jdbc).queryForList("SELECT id,name FROM teams ORDER BY id")
        try {
            val reading=pool.submit<MockHttpServletResponse> { getRanking() }
            check(captured.await(15,TimeUnit.SECONDS))
            Steps.step("When 순위 읽기가 열린 상태에서 추가 투자를 커밋") {
                pool.submit<InvestmentReceipt> {
                    if(alreadyInvested) invest("inv_001",6,300000) else invest("inv_001",2,100000)
                }.get(10,TimeUnit.SECONDS)
            }
            release.countDown()
            val old=json(reading.get(10,TimeUnit.SECONDS))
            val current=json(getRanking())
            Steps.step("Then 새 투자 전후가 섞이지 않으며 쓰기는 읽기 종료 전에 완료") {
                assertThat(old["snapshotVersion"].asLong()).isEqualTo(if(alreadyInvested)1 else 0)
                assertThat(item(old,"inv_001")["score"].asInt()).isEqualTo(if(alreadyInvested)70 else 0)
                assertThat(current["snapshotVersion"].asLong()).isEqualTo(if(alreadyInvested)2 else 1)
                assertThat(item(current,"inv_001")["score"].asInt()).isEqualTo(if(alreadyInvested)97 else 10)
                assertThat(item(old,"inv_001")["rank"].isNull).isEqualTo(!alreadyInvested)
                assertThat(item(current,"inv_001")["rank"].asInt()).isEqualTo(1)
                assertThat(item(old,"inv_001")["contributions"].size()).isEqualTo(if(alreadyInvested)1 else 0)
                assertThat(item(current,"inv_001")["contributions"].size()).isEqualTo(if(alreadyInvested)2 else 1)
            }
        } finally { release.countDown();pool.shutdownNow() }
    }

    @Test @DisplayName("Given 140명·투자 1400건 When 계산 Then 투자자 수와 무관하게 SELECT 4회·전원 10개 근거")
    fun eventSizedBulkReads() {
        seedFullEvent()
        clearInvocations(jdbc)
        val start=System.nanoTime()
        val result=Steps.step("When 실제 행사 규모의 전체 성과를 계산") { rankings.rankings(RankingGroup.ALL) }
        val elapsed=(System.nanoTime()-start)/1000000.0
        val selects=mockingDetails(jdbc).invocations.filter {
            (it.method.name=="queryForList" && it.arguments.size==1) ||
                (it.method.name=="queryForObject" && it.arguments.size==2 && it.arguments[1] is Class<*>)
        }
        Allure.addAttachment("로컬 계산 시간 (운영 성능 보장 아님)","text/plain","DB=${if(db.mysql) "MySQL" else "SQLite"}, investors=140, investments=1400, bulkSELECTs=${selects.size}, elapsedMs=$elapsed",".txt")
        Steps.step("Then 140명 모두 전액 투자·점수 근거 10개, 총 SELECT는 4회") {
            assertThat(result.count).isEqualTo(140)
            assertThat(result.snapshotVersion).isEqualTo(1400)
            assertThat(result.items.all { it.rank != null && it.contributions.size==10 }).isTrue()
            assertThat(result.items.map { it.score }).containsOnly(100)
            assertThat(result.items.map { it.rank }).containsOnly(1)
            assertThat(selects).hasSize(4)
        }
        signIn()
        val response=getRanking()
        assertThat(response.status).isEqualTo(200)
        assertThat(json(response)["items"].size()).isEqualTo(140)
    }

    private fun seedFullEvent() {
        db.write {
            jdbc.update("DELETE FROM grants")
            jdbc.update("DELETE FROM accounts WHERE role='INVESTOR'")
            var version=0
            for(n in 1..140) {
                val id="load-$n"
                jdbc.update("INSERT INTO accounts(id,code_hash,role,kind,team_id,alias,balance) VALUES(?,?,'INVESTOR','PARTICIPANT',1,?,0)",id,"test-$id",id)
                jdbc.update("INSERT INTO grants VALUES(?,1000000,?)",id,Instant.now().toString())
                for(team in 2..11) jdbc.update("INSERT INTO investments VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),id,team,100000,Instant.now().toString(),1000000-(team-1)*100000,++version)
            }
            jdbc.update("UPDATE metadata SET version=? WHERE id=1",version)
            true
        }
    }

    @Test @DisplayName("Given 컨트롤러 문서 When OpenAPI 생성 Then 그룹·null 순위·오류 조건·예시 제공")
    fun openApiContract() {
        val response=Steps.step("When 실제 컨트롤러의 OpenAPI 조회") { mvc.perform(get("/v3/api-docs")).andReturn().response }
        val spec=json(response)
        val operation=spec["paths"][path]["get"]
        Steps.step("Then 관리자 인증·기본 ALL·모든 오류와 성공 예시·잔액 없는 DTO") {
            assertThat(operation["operationId"].asText()).isEqualTo("getInvestorRankings")
            assertThat(operation["security"].toString()).contains("AdminSession")
            val group=operation["parameters"].single { it["name"].asText()=="group" }
            assertThat(group["schema"]["default"].asText()).isEqualTo("ALL")
            assertThat(group["schema"]["enum"].map { it.asText() }).containsExactly("ALL","PARTICIPANT","STAFF")
            assertThat(operation["responses"].fieldNames().asSequence().toSet()).contains("200","401","403","422","429","500","503")
            assertThat(operation["responses"]["422"]["description"].asText()).contains("group","빈")
            assertThat(operation["responses"]["200"]["content"]["application/json"]["examples"]["ranking"]).isNotNull()
            val example=operation["responses"]["200"]["content"]["application/json"]["examples"]["ranking"]["value"]["items"][1]
            assertThat(example.has("rank") && example["rank"].isNull).isTrue()
            assertThat(example.has("teamId") && example["teamId"].isNull).isTrue()
            assertThat(spec["components"]["schemas"]["InvestorRanking"]["properties"].has("balance")).isFalse()
            assertThat(spec["components"]["schemas"]["InvestorRanking"]["properties"]["rank"]["description"].asText()).contains("null")
            assertThat(operation["description"].asText()).contains("1회 이상", "0점이나 음수", "투자 내역이 전혀 없는 사람만 rank=null")
            val firstExample=operation["responses"]["200"]["content"]["application/json"]["examples"]["ranking"]["value"]["items"][0]
            assertThat(firstExample["rank"].asInt()).isEqualTo(1)
            assertThat(firstExample["contributions"].single()["investedAmount"].asInt()).isEqualTo(100000)
            val rowSchema=spec["components"]["schemas"]["InvestorRanking"]
            assertThat(rowSchema["properties"]["rank"]["type"].map { it.asText() }).containsExactlyInAnyOrder("integer","null")
            assertThat(rowSchema["properties"]["teamId"]["type"].map { it.asText() }).containsExactlyInAnyOrder("integer","null")
            assertThat(rowSchema["required"].map { it.asText() }).contains("rank","teamId")
        }
        if(!db.mysql) {
            Files.createDirectories(java.nio.file.Path.of("build/generated"))
            Files.writeString(java.nio.file.Path.of("build/generated/openapi.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec))
        }
    }
}

@DisplayName("API 테스트 · 투자자 성과 순위 / SQLite")
class InvestorRankingApiTest: InvestorRankingApiContractTest() {
    companion object {
        private val file=Files.createTempFile("tooja-ranking-", ".db")
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { "jdbc:sqlite:$file" }
        }
    }
}

@ActiveProfiles("test","mysql")
@DisplayName("API 테스트 · 투자자 성과 순위 / MySQL")
class MySqlInvestorRankingApiTest: InvestorRankingApiContractTest() {
    companion object {
        @JvmStatic @DynamicPropertySource fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { System.getenv("TEST_MYSQL_URL") ?: "jdbc:mysql://localhost:13306/tooja_test?sslMode=REQUIRED" }
            r.add("spring.datasource.username") { System.getenv("TEST_MYSQL_USERNAME") ?: "root" }
            r.add("spring.datasource.password") { System.getenv("TEST_MYSQL_PASSWORD") ?: "ci-test-only" }
        }
    }
}
