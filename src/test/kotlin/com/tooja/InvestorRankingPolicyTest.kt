package com.tooja

import io.qameta.allure.Allure
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("단위 테스트 · 투자자 성과 점수와 순위")
class InvestorRankingPolicyTest {
    @BeforeEach fun labels() {
        Allure.label("parentSuite", "단위 테스트")
        Allure.label("suite", "투자자 성과 순위")
    }

    private fun person(id: String, kind: String="PARTICIPANT", name: String=id) =
        RankingInvestor(id, name, kind, if (kind == "PARTICIPANT") 1 else null)
    private fun team(id: Int, rank: Int) = RankingRow(rank, id, "팀$id", 100000L)

    @ParameterizedTest(name="Given 팀 {0}등 When 10만원당 배점 계산 Then {1}점")
    @CsvSource("1,10", "2,9", "3,8", "4,7", "5,6", "6,5", "7,4", "8,3", "9,2", "10,1",
        "11,-1", "12,-2", "13,-3", "14,-4", "15,-5", "16,-6", "17,-7", "18,-8", "19,-9", "20,-10")
    fun everyRank(rank: Int, expected: Int) {
        Steps.step("Given 팀 순위 $rank 등")
        val actual = Steps.step("When 투자금 10만원당 배점을 계산") { InvestorRankingPolicy.points(rank) }
        Steps.step("Then $expected 점이며 10등과 11등 사이에 0점 배점은 없음") { assertThat(actual).isEqualTo(expected) }
    }

    @ParameterizedTest(name="Given 잘못된 팀 순위 {0} When 배점 계산 Then 조용히 잘못된 점수를 만들지 않음")
    @ValueSource(ints=[0,21])
    fun invalidRank(rank: Int) {
        Steps.step("Given 범위를 벗어난 팀 순위 $rank")
        Steps.step("When 계산 / Then 명시적 실패") { assertThatIllegalArgumentException().isThrownBy { InvestorRankingPolicy.points(rank) } }
    }

    @Test @DisplayName("Given 2등에 60만원·15등에 40만원 When 합산 Then 54-20=34점")
    fun weightedSum() {
        Steps.step("Given 2등 팀에 60만원, 15등 팀에 40만원 투자")
        val result = Steps.step("When 개인 성과 계산") {
            InvestorRankingPolicy.calculate(RankingGroup.ALL, listOf(person("A")), listOf(team(2,2),team(15,15)),
                listOf(RankingInvestment("A",15,400000),RankingInvestment("A",2,600000))).single()
        }
        Steps.step("Then 총점 34, 근거 +54/-20, 팀 번호순") {
            assertThat(result.score).isEqualTo(34)
            assertThat(result.contributions.map { it.score }).containsExactly(54,-20)
            assertThat(result.contributions.map { it.teamId }).containsExactly(2,15)
        }
    }

    @Test @DisplayName("Given 전액 투자자 100·100·94점 When 정렬 Then 순위 1·1·3")
    fun competitionRanks() {
        Steps.step("Given 전액 투자자 3명이 100·100·94점")
        val rows = Steps.step("When 순위를 매김") {
            InvestorRankingPolicy.calculate(RankingGroup.ALL,listOf(person("C"),person("B"),person("A")),
                listOf(team(2,1),team(3,1),team(4,3)),
                listOf(RankingInvestment("A",2,700000),RankingInvestment("A",3,300000),
                    RankingInvestment("B",2,700000),RankingInvestment("B",3,300000),
                    RankingInvestment("C",2,700000),RankingInvestment("C",4,300000)))
        }
        Steps.step("Then 1·1·3, 공동 순위는 ID로 안정 정렬") {
            assertThat(rows.map { it.rank }).containsExactly(1,1,3)
            assertThat(rows.map { it.investorId }).containsExactly("A","B","C")
        }
    }

    @Test @DisplayName("Given 일부 투자 70점·전액 -97점·미투자 0점 When 정렬 Then 투자자는 1·2등, 미투자만 null")
    fun eligibilityAndNegativeScores() {
        Steps.step("Given 70만원 투자자 70점·100만원 투자자 -97점·미투자자 0점")
        val rows = Steps.step("When 순위 계산") {
            InvestorRankingPolicy.calculate(RankingGroup.ALL,listOf(person("partial"),person("negative"),person("none")),
                listOf(team(2,1),team(3,20),team(4,19)), listOf(RankingInvestment("partial",2,700000),RankingInvestment("negative",3,700000),RankingInvestment("negative",4,300000)))
        }
        Steps.step("Then 투자자는 점수순 1·2등, 미투자자는 음수 점수 투자자보다 뒤에 null") {
            assertThat(rows.map { it.investorId }).containsExactly("partial","negative","none")
            assertThat(rows.map { it.rank }).containsExactly(1,2,null)
            assertThat(rows.map { it.score }).containsExactly(70,-97,0)
            assertThat(rows.last().contributions).isEmpty()
        }
    }

    @Test @DisplayName("Given 10만원을 한 번 투자 When 계산 Then 잔액이 남아도 순위 부여")
    fun firstInvestmentIsRanked() {
        Steps.step("Given A는 1등 팀에 10만원을 한 번 투자하고 B는 미투자")
        val rows = Steps.step("When 성과 순위를 계산") {
            InvestorRankingPolicy.calculate(RankingGroup.ALL, listOf(person("B"),person("A")),
                listOf(team(2,1)),listOf(RankingInvestment("A",2,100000)))
        }
        Steps.step("Then A는 10점·1등, B는 0점·순위 null") {
            assertThat(rows.map { it.investorId }).containsExactly("A","B")
            assertThat(rows.map { it.rank }).containsExactly(1,null)
            assertThat(rows.map { it.score }).containsExactly(10,0)
        }
    }

    @Test @DisplayName("Given 일부 투자자의 합계 0점·음수 점수와 미투자자 When 계산 Then 점수 부호와 무관하게 투자자만 순위 부여")
    fun zeroAndNegativePartialScoresAreRanked() {
        Steps.step("Given A는 1등·20등에 각각 10만원, B는 20등에 10만원, C는 미투자")
        val rows = Steps.step("When 성과 순위를 계산") {
            InvestorRankingPolicy.calculate(RankingGroup.ALL,listOf(person("C"),person("B"),person("A")),
                listOf(team(2,1),team(3,20)),listOf(RankingInvestment("A",2,100000),RankingInvestment("A",3,100000),RankingInvestment("B",3,100000)))
        }
        Steps.step("Then A 0점·1등, B -10점·2등, C 0점·순위 null") {
            assertThat(rows.map { it.investorId }).containsExactly("A","B","C")
            assertThat(rows.map { it.rank }).containsExactly(1,2,null)
            assertThat(rows.map { it.score }).containsExactly(0,-10,0)
        }
    }

    @Test @DisplayName("Given 일부 투자자와 전액 투자자가 동점 When 계산 Then 투자액과 무관하게 공동 순위 1·1·3")
    fun partialAndFullInvestorsShareCompetitionRanks() {
        Steps.step("Given A는 10만원 투자·10점, B는 100만원 투자·10점, C는 10만원 투자·9점")
        val rows = Steps.step("When 개인 순위를 계산") {
            InvestorRankingPolicy.calculate(RankingGroup.ALL,listOf(person("C"),person("B"),person("A")),
                listOf(team(2,1),team(3,10),team(4,2),team(5,10)),listOf(RankingInvestment("A",2,100000),
                    RankingInvestment("B",3,700000),RankingInvestment("B",5,300000),RankingInvestment("C",4,100000)))
        }
        Steps.step("Then A와 B는 공동 1등, C는 3등") {
            assertThat(rows.map { it.rank }).containsExactly(1,1,3)
            assertThat(rows.map { it.score }).containsExactly(10,10,9)
        }
    }

    @Test @DisplayName("Given 동점이며 이름 중복 When 정렬 Then 이름·ID순이며 순위는 동일")
    fun stableOrder() {
        Steps.step("Given 이름이 나·가·가이고 점수가 같은 전액 투자자")
        val rows = Steps.step("When 정렬") {
            InvestorRankingPolicy.calculate(RankingGroup.ALL,listOf(person("C",name="나"),person("B",name="가"),person("A",name="가")),listOf(team(2,1),team(3,2)),
                listOf("A","B","C").flatMap { listOf(RankingInvestment(it,2,700000),RankingInvestment(it,3,300000)) })
        }
        Steps.step("Then A·B·C 순서이며 모두 공동 1등") {
            assertThat(rows.map { it.investorId }).containsExactly("A","B","C")
            assertThat(rows.map { it.rank }).containsOnly(1)
        }
    }

    @Test @DisplayName("Given 참가자 94점·운영팀 100점 When 참가자만 조회 Then 점수 94 유지·그룹 1등")
    fun groupRankIsLocalScoreIsGlobal() {
        val people = listOf(person("participant"),person("staff",kind="STAFF"))
        val teams = listOf(team(2,1),team(3,1),team(4,3))
        val investments = listOf(RankingInvestment("participant",2,700000),RankingInvestment("participant",4,300000),RankingInvestment("staff",2,700000),RankingInvestment("staff",3,300000))
        Steps.step("Given 전체에서는 운영팀이 1등, 참가자가 2등")
        val all = InvestorRankingPolicy.calculate(RankingGroup.ALL,people,teams,investments)
        val filtered = Steps.step("When 참가자만 조회") { InvestorRankingPolicy.calculate(RankingGroup.PARTICIPANT,people,teams,investments) }
        Steps.step("Then 참가자 점수는 94 그대로, 순위만 2등에서 1등") {
            assertThat(all.first { it.investorId=="participant" }.rank).isEqualTo(2)
            assertThat(filtered.single().score).isEqualTo(94)
            assertThat(filtered.single().rank).isEqualTo(1)
        }
    }

    @Test @DisplayName("Given 팀 공동 1등 두 개 When 팀 배점 계산 Then 둘 다 10점, 다음 팀은 3등 8점")
    fun tiedTeams() {
        Steps.step("Given 팀1·2가 70만원, 팀3은 30만원, 나머지는 0원")
        val teams = Steps.step("When 팀 순위·배점 계산") {
            InvestorRankingPolicy.teamRanks((1..20).map { Triple(it,"팀$it",when(it) { 1,2 -> 700000L;3 -> 300000L;else -> 0L }) })
        }
        Steps.step("Then 공동 1·1·3 배점 10·10·8, 0원 팀은 공동 20등") {
            assertThat(teams.take(3).map { it.rank }).containsExactly(1,1,3)
            assertThat(teams.take(3).map { InvestorRankingPolicy.points(it.rank) }).containsExactly(10,10,8)
            assertThat(teams.drop(3).map { it.rank }).containsOnly(20)
        }
    }

    @Test @DisplayName("Given 모든 팀 0원 When 성과용 팀 순위 계산 Then 모두 공동 20등")
    fun allTeamsZero() {
        Steps.step("Given 20개 팀의 유치액이 모두 0원")
        val teams = Steps.step("When 성과 계산용 순위 조회") { InvestorRankingPolicy.teamRanks((1..20).map { Triple(it,"팀$it",0L) }) }
        Steps.step("Then 모두 20등, 배점 -10점") { assertThat(teams.map { it.rank }).containsOnly(20);assertThat(teams.map { InvestorRankingPolicy.points(it.rank) }).containsOnly(-10) }
    }

    @Test @DisplayName("Given 해당 그룹에 투자자 없음 When 계산 Then 빈 목록")
    fun emptyGroup() {
        Steps.step("Given 참가자만 등록")
        val rows = Steps.step("When 운영팀 그룹 계산") { InvestorRankingPolicy.calculate(RankingGroup.STAFF,listOf(person("A")),emptyList(),emptyList()) }
        Steps.step("Then 빈 목록") { assertThat(rows).isEmpty() }
    }
}
