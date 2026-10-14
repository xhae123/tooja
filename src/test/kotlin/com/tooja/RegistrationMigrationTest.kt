package com.tooja

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

@DisplayName("등록 지급 정책 · 실제 MySQL 스키마 전환")
class RegistrationMigrationTest {
    @Test @DisplayName("Given 기존 지급·미지급 계정 When V2 적용 Then 기존 거래·잔액 보존 및 미지급 등록자 배정, 활성화 열 제거")
    fun migrateExistingRegistrations() {
        val url=System.getenv("TEST_MYSQL_URL") ?: "jdbc:mysql://localhost:13306/tooja_test?sslMode=REQUIRED"
        val user=System.getenv("TEST_MYSQL_USERNAME") ?: "root"
        val password=System.getenv("TEST_MYSQL_PASSWORD") ?: "ci-test-only"
        val admin=JdbcTemplate(DriverManagerDataSource(url,user,password))
        val schema="tooja_migration_"+UUID.randomUUID().toString().replace("-","")
        admin.execute("CREATE DATABASE $schema")
        val isolatedUrl=url.replace(Regex("(?<=/)[^/?]+(?=\\?|$)"),schema)
        val source=DriverManagerDataSource(isolatedUrl,user,password)
        val db=JdbcTemplate(source)
        try {
            Steps.step("Given V1 스키마에 미지급·기지급 투자자와 관리자, 70만원 거래") {
                Flyway.configure().dataSource(source).locations("classpath:db/mysql").target("1").load().migrate()
                db.update("INSERT INTO teams VALUES(1,'팀','소개')")
                db.update("INSERT INTO accounts(id,code_hash,role,kind,team_id,alias,activated,balance) VALUES('unpaid','hash1','INVESTOR','PARTICIPANT',1,'미지급',0,0),('paid','hash2','INVESTOR','STAFF',NULL,'기지급',1,300000),('admin','hash3','ADMIN',NULL,NULL,'관리자',0,0)")
                db.update("INSERT INTO grants VALUES('paid',1000000,'2026-10-05T00:00:00Z')")
                db.update("INSERT INTO investments VALUES('trade','paid',1,700000,'2026-10-05T01:00:00Z',300000,1)")
            }
            val oldGrant=db.queryForMap("SELECT * FROM grants WHERE account_id='paid'")
            val oldTrade=db.queryForMap("SELECT * FROM investments WHERE id='trade'")
            val flyway=Flyway.configure().dataSource(source).locations("classpath:db/mysql").load()
            Steps.step("When 등록 지급 정책 마이그레이션을 적용하고 다시 실행") { flyway.migrate();flyway.migrate();flyway.validate() }
            Steps.step("Then 미지급 등록자는 100만원, 기지급자는 30만원·원래 지급시각·거래 유지, 관리자 지급 없음") {
                assertThat(db.queryForObject("SELECT balance FROM accounts WHERE id='unpaid'",Int::class.java)).isEqualTo(1000000)
                assertThat(db.queryForObject("SELECT balance FROM accounts WHERE id='paid'",Int::class.java)).isEqualTo(300000)
                assertThat(db.queryForObject("SELECT COUNT(*) FROM grants",Int::class.java)).isEqualTo(2)
                assertThat(db.queryForMap("SELECT * FROM grants WHERE account_id='paid'")).isEqualTo(oldGrant)
                assertThat(db.queryForMap("SELECT * FROM investments WHERE id='trade'")).isEqualTo(oldTrade)
                assertThat(db.queryForObject("SELECT balance FROM accounts WHERE id='admin'",Int::class.java)).isZero()
                assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name='accounts' AND column_name='activated'",Int::class.java,schema)).isZero()
            }
        } finally { admin.execute("DROP DATABASE $schema") }
    }
}
