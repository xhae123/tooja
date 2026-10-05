package com.tooja

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*
import java.time.Instant

@Schema(description="참가자 또는 운영팀 투자자. 관리자는 투자금 지급 대상이 아닙니다.")
data class Investor(val investorId: String, val alias: String, val kind: String, val teamId: Int?, val hasLoggedIn: Boolean,
    val issuedAmount: Int, val investedAmount: Int, val balance: Int, val spendingStatus: String)
data class Admin(val adminId: String, val role: String = "ADMIN")
data class Csrf(val csrfToken: String)
data class InvestorSession(val investor: Investor, val csrfToken: String, val expiresAt: Instant)
data class AdminSession(val admin: Admin, val csrfToken: String, val expiresAt: Instant)
data class LoginInput(@field:Pattern(regexp="^[0-9]{4}$",message="ASCII 숫자 4자리 문자열을 입력하세요.") @field:Schema(example="0037",description="재사용하는 사전 발급 코드. 앞자리 0을 유지합니다.") val code: String)
data class InvestmentInput(@field:Min(1) @field:Max(20) val teamId: Int, @field:Min(100000) @field:Max(700000) val amount: Int)
data class AliasInput(@field:Size(min=1,max=40) @field:Pattern(regexp="^(?![\\s\\S]*[\\r\\n])\\S(?:.*\\S)?$") val alias: String)
data class Team(val teamId: Int, val serviceName: String, val description: String, val investmentState: String, val myInvestmentAmount: Int, val allowedAmounts: List<Int>)
data class TeamList(val investor: Investor, val teams: List<Team>, val asOf: Instant)
data class TeamDetail(val investor: Investor, val team: Team, val asOf: Instant)
data class Investment(val investmentId: String, val teamId: Int, val serviceName: String, val amount: Int, val confirmedAt: Instant)
data class InvestmentReceipt(val investment: Investment, val balanceAfterInvestment: Int, val snapshotVersion: Long)
data class InvestmentDetail(val investment: Investment, val currentBalance: Int, val asOf: Instant)
data class InvestmentHistory(val investor: Investor, val items: List<Investment>, val count: Int, val asOf: Instant)
data class Pending(val status: String = "PROCESSING", val requestKey: String)
data class RequestResult(val status: String, val requestKey: String, val result: InvestmentReceipt? = null, val originalHttpStatus: Int? = null, val error: ErrorBody? = null)
data class ErrorBody(val code: String, val message: String, val details: Map<String,Any> = emptyMap())
data class ApiError(val error: ErrorBody, val requestId: String)
data class Metrics(val registeredInvestorCount: Int, val activatedInvestorCount: Int, val notActivatedCount: Int, val investedInvestorCount: Int,
    val totalIssuedAmount: Long, val totalInvestedAmount: Long, val totalBalance: Long, val completedCount: Int, val unspentCount: Int)
data class Overview(val total: Metrics, val participants: Metrics, val staff: Metrics, val asOf: Instant, val snapshotVersion: Long)
data class InvestorList(val items: List<Investor>, val count: Int, val asOf: Instant)
data class InvestorDetail(val investor: Investor, val investments: List<Investment>, val asOf: Instant)
data class RankingRow(val rank: Int, val teamId: Int, val serviceName: String, val raisedAmount: Long)
data class Dashboard(val snapshotVersion: Long, val asOf: Instant, val totalInvestedAmount: Long, val investedInvestorCount: Int, val teams: List<RankingRow>)

class ApiException(val status: Int, val code: String, override val message: String, val details: Map<String,Any> = emptyMap(), val retryAfter: Int? = null): RuntimeException(message)
object Policy {
    fun validateAmount(amount: Int) { if (amount !in 100000..700000 || amount % 100000 != 0) throw ApiException(422,"VALIDATION_FAILED","금액은 10만~70만원의 10만원 단위입니다.", mapOf("fieldErrors" to listOf(mapOf("field" to "amount","reason" to "INVALID_AMOUNT","message" to "10만원 단위로 선택하세요.")))) }
    fun state(kind: String, ownTeam: Int?, team: Int, invested: Int, balance: Int): String = when {
        kind == "PARTICIPANT" && ownTeam == team -> "SELF_TEAM"
        invested > 0 -> "ALREADY_INVESTED"
        balance == 0 -> "BALANCE_EXHAUSTED"
        else -> "AVAILABLE"
    }
    fun allowed(state: String, balance: Int) = if (state=="AVAILABLE") (100000..700000 step 100000).filter { it<=balance } else emptyList()
    fun rank(rows: List<Triple<Int,String,Long>>): List<RankingRow> {
        val sorted=rows.sortedWith(compareByDescending<Triple<Int,String,Long>> { it.third }.thenBy { it.first })
        var rank=0; var previous: Long?=null
        return sorted.mapIndexed { i,t -> if(t.third!=previous) rank=i+1; previous=t.third; RankingRow(rank,t.first,t.second,t.third) }
    }
}
