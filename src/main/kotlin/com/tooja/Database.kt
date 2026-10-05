package com.tooja

import org.springframework.stereotype.Component
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.core.env.Environment
import org.springframework.beans.factory.annotation.Value
import jakarta.annotation.PostConstruct
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
class Database(val jdbc: JdbcTemplate, tm: PlatformTransactionManager, private val secrets: Secrets, private val env: Environment,
    @Value("\${app.provisioning-mode:required}") private val provisioningMode: String,
    @Value("\${app.accounts-csv}") private val accountsFile: String, @Value("\${app.teams-csv}") private val teamsFile: String,
    private val telemetry: Telemetry? = null) {
    private val tx = TransactionTemplate(tm)
    private val writeLock = ReentrantLock(true)
    fun <T:Any> write(block: () -> T): T = measure("write") { writeLock.withLock { tx.execute { block() }!! } }
    fun <T:Any> read(block: () -> T): T = measure("read") { tx.execute { block() }!! }
    private fun <T> measure(operation: String,block: () -> T): T = telemetry?.database(operation,block) ?: block()
    val mysql: Boolean get() = env.activeProfiles.contains("mysql")
    private fun insertIgnore(sql: String): String = if (mysql) sql.replace("INSERT OR IGNORE", "INSERT IGNORE") else sql
    @PostConstruct fun initialize() {
        if (!mysql) {
        jdbc.execute("PRAGMA journal_mode=WAL")
        jdbc.execute("PRAGMA busy_timeout=10000")
        listOf(
            "CREATE TABLE IF NOT EXISTS teams(id INTEGER PRIMARY KEY CHECK(id BETWEEN 1 AND 20), name TEXT NOT NULL, description TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY, code_hash TEXT NOT NULL UNIQUE, role TEXT NOT NULL CHECK(role IN ('INVESTOR','ADMIN')), kind TEXT CHECK(kind IN ('PARTICIPANT','STAFF')), team_id INTEGER REFERENCES teams(id), alias TEXT NOT NULL, activated INTEGER NOT NULL DEFAULT 0 CHECK(activated IN (0,1)), balance INTEGER NOT NULL DEFAULT 0 CHECK(balance BETWEEN 0 AND 1000000), deleted_at TEXT, CHECK((role='ADMIN' AND kind IS NULL AND team_id IS NULL) OR (role='INVESTOR' AND kind IS NOT NULL AND ((kind='PARTICIPANT' AND team_id IS NOT NULL) OR (kind='STAFF' AND team_id IS NULL)))))",
            "CREATE TABLE IF NOT EXISTS grants(account_id TEXT PRIMARY KEY REFERENCES accounts(id), amount INTEGER NOT NULL CHECK(amount=1000000), granted_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY, role TEXT NOT NULL, account_id TEXT REFERENCES accounts(id), csrf TEXT NOT NULL, expires_at TEXT NOT NULL, revoked_at TEXT)",
            "CREATE TABLE IF NOT EXISTS contexts(token_hash TEXT PRIMARY KEY, role TEXT NOT NULL, csrf TEXT NOT NULL, expires_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS investments(id TEXT PRIMARY KEY, account_id TEXT NOT NULL REFERENCES accounts(id), team_id INTEGER NOT NULL REFERENCES teams(id), amount INTEGER NOT NULL CHECK(amount BETWEEN 100000 AND 700000 AND amount%100000=0), confirmed_at TEXT NOT NULL, balance_after INTEGER NOT NULL CHECK(balance_after>=0), version INTEGER NOT NULL, UNIQUE(account_id,team_id))",
            "CREATE TABLE IF NOT EXISTS requests(account_id TEXT NOT NULL REFERENCES accounts(id), request_key TEXT NOT NULL, team_id INTEGER NOT NULL, amount INTEGER NOT NULL, status TEXT NOT NULL, investment_id TEXT REFERENCES investments(id), http_status INTEGER, error_code TEXT, error_message TEXT, PRIMARY KEY(account_id,request_key))",
            "CREATE TABLE IF NOT EXISTS metadata(id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL DEFAULT 0)",
            "INSERT OR IGNORE INTO metadata(id,version) VALUES(1,0)",
            "CREATE TABLE IF NOT EXISTS investment_control(id INTEGER PRIMARY KEY CHECK(id=1), status TEXT NOT NULL CHECK(status IN ('RUNNING','PAUSED')), revision INTEGER NOT NULL DEFAULT 0 CHECK(revision>=0), updated_at TEXT)",
            "INSERT OR IGNORE INTO investment_control(id,status,revision) VALUES(1,'RUNNING',0)"
        ).forEach(jdbc::execute)
        }
        require(provisioningMode in listOf("required","manual")) { "Unknown provisioning mode" }
        if (env.activeProfiles.any { it in listOf("demo","test") }) seedDemo() else if(provisioningMode=="required") importProvisioning()
    }
    fun seedDemo() = write {
        for (id in 1..20) jdbc.update(insertIgnore("INSERT OR IGNORE INTO teams VALUES(?,?,?)"), id,"팀%02d 데모 서비스".format(id),"팀%02d 부스에서 서비스를 체험하고 투자해 보세요.".format(id))
        val fixtures=listOf(listOf("inv_001","0037","INVESTOR","PARTICIPANT",1,"팀01 투자자001"),listOf("inv_002","0038","INVESTOR","PARTICIPANT",3,"팀03 투자자002"),listOf("inv_003","0039","INVESTOR","PARTICIPANT",4,"팀04 투자자003"),listOf("inv_004","0040","INVESTOR","PARTICIPANT",5,"팀05 투자자004"),listOf("staff_001","1000","INVESTOR","STAFF",null,"운영팀 투자자001"),listOf("admin_001","4821","ADMIN",null,null,"관리자"))
        fixtures.forEach { a -> jdbc.update(insertIgnore("INSERT OR IGNORE INTO accounts(id,code_hash,role,kind,team_id,alias) VALUES(?,?,?,?,?,?)"),a[0],secrets.codeHash(a[1] as String),a[2],a[3],a[4],a[5]) }
        true
    }
    private fun importProvisioning() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM teams",Int::class.java)==0) {
            require(teamsFile.isNotBlank() && accountsFile.isNotBlank()) { "Set TEAMS_CSV and ACCOUNTS_CSV before first production startup" }
            write {
                Files.readAllLines(Path.of(teamsFile)).drop(1).filter { it.isNotBlank() }.forEach { line ->
                    val p=line.split(',',limit=3);require(p.size==3);jdbc.update("INSERT INTO teams VALUES(?,?,?)",p[0].toInt(),p[1],p[2])
                }
                require(jdbc.queryForObject("SELECT COUNT(*) FROM teams",Int::class.java)==20)
                Files.readAllLines(Path.of(accountsFile)).drop(1).filter { it.isNotBlank() }.forEach { line ->
                    val p=line.split(',');require(p.size==6 && p[1].matches(Regex("[0-9]{4}")))
                    jdbc.update("INSERT INTO accounts(id,code_hash,role,kind,team_id,alias) VALUES(?,?,?,?,?,?)",p[0],secrets.codeHash(p[1]),p[2],p[3].ifBlank{null},p[4].ifBlank{null}?.toInt(),p[5])
                };true
            }
        }
    }
    fun reset() = write {
        listOf("requests","investments","grants","sessions","contexts","accounts","teams").forEach { jdbc.update("DELETE FROM $it") };jdbc.update("UPDATE metadata SET version=0");jdbc.update("UPDATE investment_control SET status='RUNNING',revision=0,updated_at=NULL WHERE id=1");true
    }.also { seedDemo() }
}
