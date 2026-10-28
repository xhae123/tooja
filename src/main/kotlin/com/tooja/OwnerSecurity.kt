package com.tooja

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

data class OwnerSession(val csrf: String, val expiresAt: Instant)

/**
 * The owner is not an account: a long shared secret from the environment, hashed in memory,
 * with its own session table and cookie. Writes are limited to login and logout forms.
 */
@Component
class OwnerAuth(private val db: Database, private val secrets: Secrets, private val clock: Clock, private val rates: RateLimits, private val ops: OpsWriter,
    @Value("\${app.owner-token:}") token: String,
    @Value("\${app.origin}") private val origin: String,
    @Value("\${app.secure-cookies}") private val secure: Boolean,
    @Value("\${app.trusted-proxy-hops:1}") private val trustedHops: Int) {
    val enabled = token.isNotBlank()
    private val tokenHash = secrets.hash(token).toByteArray()
    init { require(!enabled || token.length >= 32) { "OWNER_TOKEN must contain at least 32 characters" } }
    companion object { const val SESSION_COOKIE = "owner_session"; const val CONTEXT_COOKIE = "owner_csrf_context"; const val SESSION_SECONDS = 43200L }

    private fun cookie(response: HttpServletResponse, name: String, value: String, seconds: Long) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, value).path("/owner").httpOnly(true).secure(secure).sameSite("Lax").maxAge(seconds).build().toString())
    }
    private fun raw(request: HttpServletRequest, name: String) = request.cookies?.firstOrNull { it.name == name }?.value

    fun session(request: HttpServletRequest): OwnerSession? {
        val raw = raw(request, SESSION_COOKIE) ?: return null
        val row = db.jdbc.queryForList("SELECT csrf,expires_at,revoked_at FROM owner_sessions WHERE token_hash=?", secrets.hash(raw)).firstOrNull() ?: return null
        val expiry = Instant.parse(row["expires_at"].toString())
        if (row["revoked_at"] != null || !clock.instant().isBefore(expiry)) return null
        return OwnerSession(row["csrf"].toString(), expiry)
    }
    /** Login page token: a 10 minute context row, the same double-submit pattern as the API roles. */
    fun csrf(request: HttpServletRequest, response: HttpServletResponse): String {
        session(request)?.let { return it.csrf }
        val raw = secrets.token(); val token = secrets.token(); val expiry = clock.instant().plusSeconds(600)
        db.sideWrite { db.jdbc.update("INSERT INTO contexts VALUES(?,?,?,?)", secrets.hash(raw), "owner", token, expiry.toString()); true }
        cookie(response, CONTEXT_COOKIE, raw, 600)
        return token
    }
    /** Owner pages live on the API host, so only app.origin may post; the Vercel allow-list stays untouched. */
    fun verifyCsrf(request: HttpServletRequest, provided: String?): Boolean {
        if (request.getHeader("Origin") != origin || provided == null) return false
        val expected = session(request)?.csrf ?: raw(request, CONTEXT_COOKIE)?.let { raw ->
            db.jdbc.queryForList("SELECT csrf,expires_at FROM contexts WHERE token_hash=? AND role='owner'", secrets.hash(raw)).firstOrNull()
                ?.takeIf { clock.instant().isBefore(Instant.parse(it["expires_at"].toString())) }?.get("csrf")?.toString()
        } ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())
    }
    sealed interface LoginResult { object Success: LoginResult; object Invalid: LoginResult; data class Limited(val retryAfter: Int): LoginResult }
    fun login(token: String, request: HttpServletRequest, response: HttpServletResponse): LoginResult {
        val ip = ClientIp.resolve(request, trustedHops); val key = "owner-login:$ip"
        try { rates.check(key, 5, record = false) } catch (e: ApiException) { activity(request, "login_failed", 429); return LoginResult.Limited(e.retryAfter ?: 60) }
        if (!enabled || !MessageDigest.isEqual(tokenHash, secrets.hash(token).toByteArray())) {
            try { rates.check(key, 5) } catch (_: ApiException) { }
            activity(request, "login_failed", 401); return LoginResult.Invalid
        }
        val raw = secrets.token(); val csrf = secrets.token(); val now = clock.instant()
        db.sideWrite {
            raw(request, SESSION_COOKIE)?.let { db.jdbc.update("UPDATE owner_sessions SET revoked_at=? WHERE token_hash=?", now.toString(), secrets.hash(it)) }
            raw(request, CONTEXT_COOKIE)?.let { db.jdbc.update("DELETE FROM contexts WHERE token_hash=?", secrets.hash(it)) }
            db.jdbc.update("INSERT INTO owner_sessions(token_hash,csrf,created_at,expires_at,revoked_at,client_ip,user_agent) VALUES(?,?,?,?,NULL,?,?)",
                secrets.hash(raw), csrf, now.toString(), now.plusSeconds(SESSION_SECONDS).toString(), ip, request.getHeader("User-Agent")?.take(512)); true
        }
        cookie(response, SESSION_COOKIE, raw, SESSION_SECONDS); cookie(response, CONTEXT_COOKIE, "", 0)
        activity(request, "login_success", 303)
        return LoginResult.Success
    }
    fun logout(request: HttpServletRequest, response: HttpServletResponse) {
        db.sideWrite {
            raw(request, SESSION_COOKIE)?.let { db.jdbc.update("UPDATE owner_sessions SET revoked_at=? WHERE token_hash=?", clock.instant().toString(), secrets.hash(it)) }
            raw(request, CONTEXT_COOKIE)?.let { db.jdbc.update("DELETE FROM contexts WHERE token_hash=?", secrets.hash(it)) }; true
        }
        cookie(response, SESSION_COOKIE, "", 0); cookie(response, CONTEXT_COOKIE, "", 0)
        activity(request, "logout", 303)
    }
    // Owner pages sit outside /api, so the access filter does not record them; log the auth events explicitly.
    private fun activity(request: HttpServletRequest, action: String, status: Int) {
        ops.offer(ActivityRow(clock.millis(), request.getAttribute("requestId")?.toString() ?: "unknown", request.method, request.requestURI.take(128), status, 0,
            "OWNER", "owner", action, null, null, null, null, request.getHeader("Origin")?.take(255), ClientIp.resolve(request, trustedHops), request.remoteAddr ?: "unknown", request.getHeader("User-Agent")?.take(512)))
    }
}

/** Every owner page needs a live owner session; the login page and static assets are the only exceptions. */
@Component
class OwnerPageInterceptor(private val auth: OwnerAuth): HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (!auth.enabled) { response.status = 404; return false }
        val path = request.requestURI
        if (path == "/owner/login" || path.startsWith("/owner/assets/")) return true
        val session = auth.session(request)
        if (session == null) { response.status = 302; response.setHeader("Location", "/owner/login"); return false }
        request.setAttribute("ownerSession", session)
        return true
    }
}
