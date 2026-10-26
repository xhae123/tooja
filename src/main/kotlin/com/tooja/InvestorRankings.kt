package com.tooja

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

enum class RankingGroup { ALL, PARTICIPANT, STAFF }

data class InvestorRankings(
    val group: RankingGroup,
    val asOf: Instant,
    val snapshotVersion: Long,
    val count: Int,
    val items: List<InvestorRanking>
)

data class InvestorRanking(
    @field:Schema(description="선택한 그룹의 전액 투자자끼리 매긴 경쟁 순위(1·1·3). 미소진자는 명시적으로 null입니다.", nullable=true, requiredMode=Schema.RequiredMode.REQUIRED)
    val rank: Int?,
    val investorId: String,
    val alias: String,
    val kind: String,
    val teamId: Int?,
    val score: Int,
    val contributions: List<ScoreContribution>
)

data class ScoreContribution(
    val teamId: Int,
    val serviceName: String,
    val teamRank: Int,
    val investedAmount: Int,
    val pointsPerUnit: Int,
    val score: Int
)

// These inputs are internal. In particular, the balance is used for eligibility only,
// and never serialized as part of the ranking response.
internal data class RankingInvestor(val id: String, val alias: String, val kind: String, val teamId: Int?, val balance: Int)
internal data class RankingInvestment(val investorId: String, val teamId: Int, val amount: Int)

internal object InvestorRankingPolicy {
    fun points(rank: Int): Int {
        require(rank in 1..20)
        return if (rank <= 10) 11 - rank else 10 - rank
    }

    fun teamRanks(teams: List<Triple<Int, String, Long>>): List<RankingRow> =
        Policy.rank(teams).map { if (it.raisedAmount == 0L) it.copy(rank=20) else it }

    fun calculate(
        group: RankingGroup,
        investors: List<RankingInvestor>,
        teams: List<RankingRow>,
        investments: List<RankingInvestment>
    ): List<InvestorRanking> {
        val teamsById = teams.associateBy { it.teamId }
        val investmentsByInvestor = investments.groupBy { it.investorId }
        val selected = investors.filter { group == RankingGroup.ALL || it.kind == group.name }
        val scores = selected.associate { investor ->
            investor.id to investmentsByInvestor[investor.id].orEmpty().sortedBy { it.teamId }.map { investment ->
                val team = teamsById.getValue(investment.teamId)
                val points = points(team.rank)
                ScoreContribution(team.teamId, team.serviceName, team.rank, investment.amount, points,
                    investment.amount / 100000 * points)
            }
        }
        val sorted = selected.sortedWith(
            compareBy<RankingInvestor> { it.balance != 0 }
                .thenByDescending { scores.getValue(it.id).sumOf { score -> score.score } }
                .thenBy { it.alias }
                .thenBy { it.id }
        )
        var completedCount = 0
        var previousScore: Int? = null
        var rank = 0
        return sorted.map { investor ->
            val contributions = scores.getValue(investor.id)
            val score = contributions.sumOf { it.score }
            val investorRank = if (investor.balance == 0) {
                completedCount++
                if (previousScore != score) rank = completedCount
                previousScore = score
                rank
            } else null
            InvestorRanking(investorRank, investor.id, investor.alias, investor.kind, investor.teamId, score, contributions)
        }
    }
}

@Service
class InvestorRankingService(private val db: Database, private val clock: Clock) {
    private data class Snapshot(
        val version: Long,
        val asOf: Instant,
        val teams: List<Triple<Int, String, Long>>,
        val investors: List<RankingInvestor>,
        val investments: List<RankingInvestment>
    )

    fun rankings(group: RankingGroup): InvestorRankings {
        // Four bulk SELECTs regardless of investor count. Plain snapshot reads do
        // not acquire the application's investment write lock or SELECT FOR UPDATE.
        val snapshot = db.snapshot {
            val version = db.jdbc.queryForObject("SELECT version FROM metadata WHERE id=1", Long::class.java)!!
            val asOf = clock.instant()
            val teams = db.jdbc.queryForList("SELECT id,name FROM teams ORDER BY id")
            val investors = db.jdbc.queryForList("SELECT id,alias,kind,team_id,balance FROM accounts WHERE role='INVESTOR' AND deleted_at IS NULL").map {
                RankingInvestor(it["id"].toString(), it["alias"].toString(), it["kind"].toString(),
                    (it["team_id"] as Number?)?.toInt(), (it["balance"] as Number).toInt())
            }
            val investments = db.jdbc.queryForList("SELECT account_id,team_id,amount FROM investments").map {
                RankingInvestment(it["account_id"].toString(), (it["team_id"] as Number).toInt(), (it["amount"] as Number).toInt())
            }
            // All committed investments contribute to team popularity, even when
            // the requested output group excludes the investor who made them.
            val raised = investments.groupingBy { it.teamId }.fold(0L) { total, it -> total + it.amount }
            Snapshot(version, asOf, teams.map { Triple((it["id"] as Number).toInt(), it["name"].toString(), raised[(it["id"] as Number).toInt()] ?: 0L) }, investors, investments)
        }
        // Release the DB connection before scoring and sorting the investors.
        val items = InvestorRankingPolicy.calculate(group, snapshot.investors,
            InvestorRankingPolicy.teamRanks(snapshot.teams), snapshot.investments)
        return InvestorRankings(group, snapshot.asOf, snapshot.version, items.size, items)
    }
}
