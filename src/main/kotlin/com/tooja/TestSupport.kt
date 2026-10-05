package com.tooja

import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.*
import org.springframework.beans.factory.annotation.Value

@RestController
@Profile("test")
@io.swagger.v3.oas.annotations.Hidden
@RequestMapping("/__test")
class TestSupport(private val db: Database,private val rates: RateLimits,@Value("\${QA_KEY:test-only-local}") private val key: String) {
    private fun verify(given: String) { if(given!=key)throw ApiException(403,"FORBIDDEN","테스트 전용 인증이 필요합니다.") }
    @PostMapping("/reset") fun reset(@RequestHeader("X-Test-Key") given: String): Map<String,Boolean> { verify(given);db.reset();rates.clear();return mapOf("reset" to true) }
    @PostMapping("/expire/{role}") fun expire(@RequestHeader("X-Test-Key") given: String,@PathVariable role: String): Map<String,Boolean> { verify(given);db.write { db.jdbc.update("UPDATE sessions SET expires_at='2000-01-01T00:00:00Z' WHERE role=?",role.uppercase());true };return mapOf("expired" to true) }
}
