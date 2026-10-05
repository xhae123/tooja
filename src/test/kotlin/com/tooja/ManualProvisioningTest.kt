package com.tooja

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Path

@DisplayName("운영 DB 직접 등록과 재배포 데이터 보존")
class ManualProvisioningTest {
    @TempDir lateinit var directory: Path
    private fun database(mode: String="manual"): Database {
        val source=DriverManagerDataSource("jdbc:sqlite:${directory.resolve("production.db")}")
        source.setDriverClassName("org.sqlite.JDBC")
        val env=MockEnvironment().also { it.setActiveProfiles("production") }
        return Database(JdbcTemplate(source),DataSourceTransactionManager(source),Secrets("manual-test-pepper"),env,mode,"","")
    }
    @Test @DisplayName("수동 등록 모드의 첫 기동은 스키마만 만들고 데모 계정을 넣지 않는다")
    fun emptyStartup() {
        val db=Steps.step("Given: 운영 프로필의 빈 SQLite DB와 수동 등록 모드") { database() }
        Steps.step("When: 서버 DB 초기화를 실행한다") { db.initialize() }
        Steps.step("Then: 팀·계정은 0건이고 스키마와 메타데이터만 준비된다") {
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM teams",Int::class.java)).isZero()
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM accounts",Int::class.java)).isZero()
            assertThat(db.jdbc.queryForObject("SELECT version FROM metadata WHERE id=1",Int::class.java)).isZero()
        }
    }
    @Test @DisplayName("재시작은 직접 넣은 팀 소개를 보존하고 삭제한 데모 행을 재삽입하지 않는다")
    fun restartPreservesManualData() {
        val db=database()
        Steps.step("Given: 관리자가 실제 DB에 팀 소개를 직접 넣는다") {
            db.initialize();db.jdbc.update("INSERT INTO teams VALUES(1,'직접 수정한 이름','직접 작성한 소개')")
        }
        Steps.step("When: 같은 DB를 사용해 서버가 다시 기동한다") { database().initialize() }
        Steps.step("Then: 등록 내용은 그대로이고 나머지 데모 팀·계정은 생기지 않는다") {
            assertThat(db.jdbc.queryForObject("SELECT name FROM teams WHERE id=1",String::class.java)).isEqualTo("직접 수정한 이름")
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM teams",Int::class.java)).isEqualTo(1)
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM accounts",Int::class.java)).isZero()
        }
    }
    @Test @DisplayName("자동 CSV 등록 기본 모드는 빈 DB에서 파일 없으면 시작을 거절한다")
    fun requiredModeStillRequiresInput() {
        val db=Steps.step("Given: CSV 등록 모드인데 초기 명단이 없다") { database("required") }
        Steps.step("When/Then: 초기화를 시도하면 명시적인 설정 오류가 발생한다") {
            assertThatThrownBy { db.initialize() }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("TEAMS_CSV")
        }
    }
}
