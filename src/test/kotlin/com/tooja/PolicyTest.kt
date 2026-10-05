package com.tooja

import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.assertj.core.api.Assertions.*
import io.qameta.allure.Allure
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MutableClock(var current: Instant=Instant.parse("2026-10-05T00:00:00Z")): Clock() {
    override fun instant()=current
    override fun getZone(): ZoneId=ZoneId.of("UTC")
    override fun withZone(zone: ZoneId): Clock=this
}
@DisplayName("단위 테스트 · 투자 규칙과 경계값")
class PolicyTest {
    @BeforeEach fun labels() { Allure.label("parentSuite","단위 테스트");Allure.label("suite","투자 정책");Allure.label("feature","P03 금액·투자 가능 상태") }
    @ParameterizedTest(name="허용 금액 {0}원은 검증을 통과한다") @ValueSource(ints=[100000,200000,300000,400000,500000,600000,700000])
    fun validAmount(amount: Int) {
        Steps.step("Given 10만~70만원의 10만원 단위 금액 $amount 원")
        Steps.step("When 금액 규칙을 검증한다") { Policy.validateAmount(amount) }
        Steps.step("Then 오류 없이 통과한다")
    }
    @ParameterizedTest(name="잘못된 금액 {0}원은 거절한다") @ValueSource(ints=[-1,0,1,99999,100001,150000,700001,800000,1000000])
    fun invalidAmount(amount: Int) {
        Steps.step("Given 허용 범위 또는 단위를 벗어난 $amount 원")
        Steps.step("When 금액 규칙을 검증한다 / Then 422 VALIDATION_FAILED") {
            assertThatThrownBy { Policy.validateAmount(amount) }.isInstanceOf(ApiException::class.java).hasFieldOrPropertyWithValue("code","VALIDATION_FAILED")
        }
    }
    @ParameterizedTest(name="종류 {0}, 소속 {1}, 대상 {2}, 기존 투자 {3}, 잔액 {4} → {5}")
    @CsvSource("PARTICIPANT,1,1,0,1000000,SELF_TEAM","PARTICIPANT,1,1,700000,0,SELF_TEAM","PARTICIPANT,1,2,700000,0,ALREADY_INVESTED","PARTICIPANT,1,2,0,0,BALANCE_EXHAUSTED","PARTICIPANT,1,2,0,300000,AVAILABLE","STAFF,1,1,0,1000000,AVAILABLE")
    fun states(kind: String,own: Int,team: Int,spent: Int,balance: Int,expected: String) {
        Steps.step("Given 투자자 종류·소속·기존 투자·잔액")
        val actual=Steps.step("When 카드 상태를 판정한다") { Policy.state(kind,own,team,spent,balance) }
        Steps.step("Then $expected 상태를 반환한다") { assertThat(actual).isEqualTo(expected) }
    }
    @Test @DisplayName("잔액 30만원이면 10·20·30만원만 선택 가능하다") fun allowed() {
        Steps.step("Given 투자 가능한 팀과 잔액 30만원")
        val result=Steps.step("When 선택 가능한 금액을 계산한다") { Policy.allowed("AVAILABLE",300000) }
        Steps.step("Then 10·20·30만원만 반환한다") { assertThat(result).containsExactly(100000,200000,300000);assertThat(Policy.allowed("SELF_TEAM",1000000)).isEmpty() }
    }
    @Test @DisplayName("경쟁 순위는 1·1·3이며 동률은 팀 번호순이다") fun rankings() {
        Steps.step("Given 팀02와 팀01이 70만원, 팀03이 30만원")
        val rows=Steps.step("When 순위를 계산한다") { Policy.rank(listOf(Triple(2,"B",700000L),Triple(3,"C",300000L),Triple(1,"A",700000L))) }
        Steps.step("Then 순위 1·1·3, 팀 순서 1·2·3") { assertThat(rows.map{it.rank}).containsExactly(1,1,3);assertThat(rows.map{it.teamId}).containsExactly(1,2,3) }
    }
    @Test @DisplayName("모든 팀이 0원이면 모두 공동 1위다") fun zeroRankings() {
        Steps.step("Given 20개 팀 모두 유치금 0원")
        val rows=Steps.step("When 순위를 계산한다") { Policy.rank((20 downTo 1).map { Triple(it,"팀$it",0L) }) }
        Steps.step("Then 모두 1위이고 팀 번호 오름차순") { assertThat(rows.map{it.rank}).containsOnly(1);assertThat(rows.map{it.teamId}).containsExactlyElementsOf((1..20).toList()) }
    }
    @Test @DisplayName("서버 요청 제한은 역할·계정별이며 60초가 되면 해제된다") fun rateLimits() {
        val clock=MutableClock();val rates=RateLimits(clock)
        Steps.step("Given A계정이 한도 2회에 도달했다") { repeat(2){rates.check("investor:A",2)} }
        Steps.step("When 같은 계정이 재요청 / Then 429와 Retry-After 60초") { assertThatThrownBy{rates.check("investor:A",2)}.hasFieldOrPropertyWithValue("retryAfter",60) }
        Steps.step("Then 다른 계정과 관리자 영역은 허용한다") { rates.check("investor:B",2);rates.check("admin:A",2) }
        Steps.step("When 정확히 60초 후 / Then 기존 제한을 해제한다") { clock.current=clock.current.plusSeconds(60);rates.check("investor:A",2) }
    }
}
