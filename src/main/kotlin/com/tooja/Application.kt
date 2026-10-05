package com.tooja

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.annotation.Value
import java.time.Clock
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Base64

@SpringBootApplication
class Application {
    @Bean fun clock(): Clock = Clock.systemUTC()
}
fun main(args: Array<String>) { runApplication<Application>(*args) }

@org.springframework.stereotype.Component
class Secrets(@Value("\${app.pepper}") private val pepper: String) {
    init { require(pepper.length >= 16) { "CODE_PEPPER must contain at least 16 characters" } }
    private val random = SecureRandom()
    fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun hash(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    fun codeHash(code: String): String = hash("$pepper:$code")
}
