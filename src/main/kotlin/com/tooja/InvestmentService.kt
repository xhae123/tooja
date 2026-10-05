package com.tooja

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class InvestmentService(private val db: Database, private val clock: Clock) {
    private fun investorRow(r: Map<String,Any?>): Investor {
        val activated=(r["activated"] as Number).toInt()==1;val balance=(r["balance"] as Number).toInt()
        return Investor(r["id"].toString(),r["alias"].toString(),r["kind"].toString(),(r["team_id"] as Number?)?.toInt(),activated,if(activated)1000000 else 0,if(activated)1000000-balance else 0,balance,if(!activated)"NOT_ACTIVATED" else if(balance==0)"COMPLETED" else "UNSPENT")
    }
    fun investor(id: String): Investor = db.jdbc.queryForList("SELECT * FROM accounts WHERE id=? AND role='INVESTOR' AND deleted_at IS NULL",id).firstOrNull()?.let(::investorRow)
        ?: throw ApiException(404,"RESOURCE_NOT_FOUND","투자자를 찾을 수 없습니다.")
    fun teams(id: String): TeamList = db.read { val i=investor(id); TeamList(i,db.jdbc.queryForList("SELECT * FROM teams ORDER BY id").map { team(i,it) },clock.instant()) }
    private fun team(i: Investor,r: Map<String,Any?>): Team {
        val tid=(r["id"] as Number).toInt()
        val amount=db.jdbc.queryForList("SELECT amount FROM investments WHERE account_id=? AND team_id=?",i.investorId,tid).firstOrNull()?.get("amount")?.let{(it as Number).toInt()}?:0
        val state=Policy.state(i.kind,i.teamId,tid,amount,i.balance)
        return Team(tid,r["name"].toString(),r["description"].toString(),state,amount,Policy.allowed(state,i.balance))
    }
    fun teamDetail(id: String,tid: Int): TeamDetail = db.read {
        val i=investor(id);val r=db.jdbc.queryForList("SELECT * FROM teams WHERE id=?",tid).firstOrNull() ?: throw ApiException(404,"RESOURCE_NOT_FOUND","팀을 찾을 수 없습니다.")
        TeamDetail(i,team(i,r),clock.instant())
    }
    private fun investment(r: Map<String,Any?>) = Investment(r["id"].toString(),(r["team_id"] as Number).toInt(),r["name"].toString(),(r["amount"] as Number).toInt(),Instant.parse(r["confirmed_at"].toString()))
    fun history(id: String): InvestmentHistory = db.read {
        val i=investor(id);val items=db.jdbc.queryForList("SELECT i.*,t.name FROM investments i JOIN teams t ON t.id=i.team_id WHERE account_id=? ORDER BY confirmed_at DESC,i.rowid DESC",id).map(::investment)
        InvestmentHistory(i,items,items.size,clock.instant())
    }
    fun detail(id: String,iid: String): InvestmentDetail = db.read {
        val r=db.jdbc.queryForList("SELECT i.*,t.name FROM investments i JOIN teams t ON t.id=i.team_id WHERE i.id=? AND account_id=?",iid,id).firstOrNull() ?: throw ApiException(404,"RESOURCE_NOT_FOUND","거래를 찾을 수 없습니다.")
        InvestmentDetail(investment(r),investor(id).balance,clock.instant())
    }
    private fun receipt(iid: String): InvestmentReceipt {
        val r=db.jdbc.queryForList("SELECT i.*,t.name FROM investments i JOIN teams t ON t.id=i.team_id WHERE i.id=?",iid).first()
        return InvestmentReceipt(investment(r),(r["balance_after"] as Number).toInt(),(r["version"] as Number).toLong())
    }
    fun create(id: String,key: String,input: InvestmentInput): InvestmentReceipt {
        val outcome=db.write {
            val previous=db.jdbc.queryForList("SELECT * FROM requests WHERE account_id=? AND request_key=?",id,key).firstOrNull()
            if(previous!=null) {
                if((previous["team_id"] as Number).toInt()!=input.teamId || (previous["amount"] as Number).toInt()!=input.amount) return@write ApiException(409,"IDEMPOTENCY_KEY_REUSED","같은 요청 키를 다른 투자에 사용할 수 없습니다.")
                if(previous["status"]=="SUCCEEDED") return@write receipt(previous["investment_id"].toString())
                return@write ApiException((previous["http_status"] as Number).toInt(),previous["error_code"].toString(),previous["error_message"].toString())
            }
            val i=investor(id)
            val failure=when {
                db.jdbc.queryForObject("SELECT COUNT(*) FROM teams WHERE id=?",Int::class.java,input.teamId)==0 -> ApiException(404,"RESOURCE_NOT_FOUND","팀을 찾을 수 없습니다.")
                i.kind=="PARTICIPANT" && i.teamId==input.teamId -> ApiException(403,"SELF_INVESTMENT_FORBIDDEN","내 팀에는 투자할 수 없습니다.")
                db.jdbc.queryForObject("SELECT COUNT(*) FROM investments WHERE account_id=? AND team_id=?",Int::class.java,id,input.teamId)!!>0 -> ApiException(409,"ALREADY_INVESTED","같은 팀에는 한 번만 투자할 수 있습니다.")
                input.amount>i.balance -> ApiException(409,"INSUFFICIENT_BALANCE","남은 투자금이 부족합니다.")
                else -> null
            }
            if(failure!=null) {
                db.jdbc.update("INSERT INTO requests(account_id,request_key,team_id,amount,status,http_status,error_code,error_message) VALUES(?,?,?,?,'REJECTED',?,?,?)",id,key,input.teamId,input.amount,failure.status,failure.code,failure.message)
                return@write failure
            }
            val iid=UUID.randomUUID().toString();val balance=i.balance-input.amount
            check(db.jdbc.update("UPDATE accounts SET balance=balance-? WHERE id=? AND balance>=?",input.amount,id,input.amount)==1)
            db.jdbc.update("UPDATE metadata SET version=version+1 WHERE id=1")
            val version=db.jdbc.queryForObject("SELECT version FROM metadata WHERE id=1",Long::class.java)!!
            db.jdbc.update("INSERT INTO investments VALUES(?,?,?,?,?,?,?)",iid,id,input.teamId,input.amount,clock.instant().toString(),balance,version)
            db.jdbc.update("INSERT INTO requests(account_id,request_key,team_id,amount,status,investment_id) VALUES(?,?,?,?,'SUCCEEDED',?)",id,key,input.teamId,input.amount,iid)
            receipt(iid)
        }
        if(outcome is ApiException) throw outcome
        return outcome as InvestmentReceipt
    }
    fun result(id: String,key: String): RequestResult = db.read {
        val r=db.jdbc.queryForList("SELECT * FROM requests WHERE account_id=? AND request_key=?",id,key).firstOrNull() ?: throw ApiException(404,"REQUEST_RESULT_NOT_FOUND","아직 요청 결과를 찾지 못했습니다. 같은 키로 다시 확인해 주세요.")
        if(r["status"]=="SUCCEEDED") RequestResult("SUCCEEDED",key,result=receipt(r["investment_id"].toString()))
        else RequestResult("REJECTED",key,originalHttpStatus=(r["http_status"] as Number).toInt(),error=ErrorBody(r["error_code"].toString(),r["error_message"].toString()))
    }
    fun list(q: String?,teamId: Int?,kind: String?,status: String?): InvestorList = db.read {
        val items=db.jdbc.queryForList("SELECT * FROM accounts WHERE role='INVESTOR' AND deleted_at IS NULL ORDER BY alias,id").map(::investorRow).filter {
            (q==null || it.alias.contains(q,ignoreCase=true) || it.investorId.contains(q,ignoreCase=true)) && (teamId==null || it.teamId==teamId) && (kind==null || it.kind==kind) && (status==null || it.spendingStatus==status)
        };InvestorList(items,items.size,clock.instant())
    }
    fun updateAlias(id: String,alias: String): Investor = db.write { investor(id);db.jdbc.update("UPDATE accounts SET alias=? WHERE id=?",alias,id);investor(id) }
    private fun metrics(items: List<Investor>): Metrics = Metrics(items.size,items.count { it.hasLoggedIn },items.count { !it.hasLoggedIn },items.count { it.investedAmount>0 },items.sumOf { it.issuedAmount.toLong() },items.sumOf { it.investedAmount.toLong() },items.sumOf { it.balance.toLong() },items.count { it.spendingStatus=="COMPLETED" },items.count { it.spendingStatus=="UNSPENT" })
    fun overview(): Overview = db.read { val all=list(null,null,null,null).items;Overview(metrics(all),metrics(all.filter{it.kind=="PARTICIPANT"}),metrics(all.filter{it.kind=="STAFF"}),clock.instant(),db.jdbc.queryForObject("SELECT version FROM metadata WHERE id=1",Long::class.java)!!) }
    fun dashboard(): Dashboard = db.read {
        val rows=db.jdbc.queryForList("SELECT t.id,t.name,COALESCE(SUM(i.amount),0) AS amount FROM teams t LEFT JOIN investments i ON i.team_id=t.id GROUP BY t.id,t.name").map { Triple((it["id"] as Number).toInt(),it["name"].toString(),(it["amount"] as Number).toLong()) }
        Dashboard(db.jdbc.queryForObject("SELECT version FROM metadata WHERE id=1",Long::class.java)!!,clock.instant(),rows.sumOf{it.third},db.jdbc.queryForObject("SELECT COUNT(DISTINCT account_id) FROM investments",Int::class.java)!!,Policy.rank(rows))
    }
}
