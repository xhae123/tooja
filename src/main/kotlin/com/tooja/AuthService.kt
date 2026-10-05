package com.tooja

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import java.time.Clock
import java.time.Instant

data class SessionRecord(val accountId: String, val role: String, val csrf: String, val expiresAt: Instant)
@Service
class AuthService(private val db: Database, private val secrets: Secrets, private val clock: Clock,
    @Value("\${app.origin}") val origin: String, @Value("\${app.secure-cookies}") private val secure: Boolean) {
    fun session(role: String, request: HttpServletRequest): SessionRecord? {
        val raw=request.cookies?.firstOrNull { it.name=="${role}_session" }?.value ?: return null
        val rows=db.jdbc.queryForList("SELECT s.*,a.deleted_at,a.role AS account_role FROM sessions s JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=?",secrets.hash(raw))
        if(rows.isEmpty()) return null
        val r=rows[0]; val expiry=Instant.parse(r["expires_at"].toString())
        if(r["revoked_at"]!=null || !clock.instant().isBefore(expiry)) return null
        if(r["deleted_at"]!=null || r["account_role"]!=role.uppercase()) throw ApiException(403,"FORBIDDEN","이 계정으로 접근할 수 없습니다.")
        return SessionRecord(r["account_id"].toString(),role,r["csrf"].toString(),expiry)
    }
    fun require(role: String, request: HttpServletRequest): SessionRecord {
        session(role,request)?.let{return it}
        val ownCookie=request.cookies?.any { it.name=="${role}_session" }==true
        if(!ownCookie && session(if(role=="investor") "admin" else "investor",request)!=null) throw ApiException(403,"FORBIDDEN","이 역할의 로그인 권한이 필요합니다.")
        throw ApiException(401,"SESSION_EXPIRED","다시 로그인해 주세요.")
    }
    private fun cookie(response: HttpServletResponse,name: String,value: String,seconds: Long,path: String="/api/v1") {
        response.addHeader("Set-Cookie",ResponseCookie.from(name,value).path(path).httpOnly(true).secure(secure).sameSite("Lax").maxAge(seconds).build().toString())
    }
    fun csrf(role: String,request: HttpServletRequest,response: HttpServletResponse): Csrf {
        session(role,request)?.let { return Csrf(it.csrf) }
        val raw=secrets.token();val token=secrets.token(); val expiry=clock.instant().plusSeconds(600)
        db.write { db.jdbc.update("DELETE FROM contexts WHERE julianday(expires_at)<=julianday(?)",clock.instant().toString()); db.jdbc.update("INSERT INTO contexts VALUES(?,?,?,?)",secrets.hash(raw),role,token,expiry.toString());true }
        cookie(response,"${role}_csrf_context",raw,600,"/api/v1/$role")
        return Csrf(token)
    }
    fun verifyCsrf(role: String, request: HttpServletRequest) {
        if(request.getHeader("Origin")!=origin) throw ApiException(403,"CSRF_INVALID","허용된 화면에서 다시 시도해 주세요.")
        val provided=request.getHeader("X-CSRF-Token") ?: throw ApiException(403,"CSRF_INVALID","인증 준비 정보를 다시 받아 주세요.")
        val current=session(role,request)
        val expected=current?.csrf ?: run {
            val raw=request.cookies?.firstOrNull { it.name=="${role}_csrf_context" }?.value ?: throw ApiException(403,"CSRF_INVALID","인증 준비 정보가 없습니다.")
            db.jdbc.queryForList("SELECT csrf FROM contexts WHERE token_hash=? AND role=? AND julianday(expires_at)>julianday(?)",secrets.hash(raw),role,clock.instant().toString()).firstOrNull()?.get("csrf")?.toString()
        }
        if(expected==null || !java.security.MessageDigest.isEqual(expected.toByteArray(),provided.toByteArray())) throw ApiException(403,"CSRF_INVALID","인증 준비 정보가 만료되었거나 일치하지 않습니다.")
    }
    fun login(role: String,code: String,request: HttpServletRequest,response: HttpServletResponse): SessionRecord {
        val raw=secrets.token();val token=secrets.token();val expiry=clock.instant().plusSeconds(172800)
        val result=db.write {
            val a=db.jdbc.queryForList("SELECT * FROM accounts WHERE code_hash=? AND role=? AND deleted_at IS NULL",secrets.codeHash(code),role.uppercase()).firstOrNull()
                ?: throw ApiException(401,"AUTHENTICATION_FAILED","코드가 올바르지 않습니다.")
            val id=a["id"].toString()
            if(role=="investor" && (a["activated"] as Number).toInt()==0) {
                db.jdbc.update("INSERT INTO grants VALUES(?,1000000,?)",id,clock.instant().toString())
                db.jdbc.update("UPDATE accounts SET activated=1,balance=1000000 WHERE id=?",id)
            }
            request.cookies?.firstOrNull { it.name=="${role}_session" }?.let { db.jdbc.update("UPDATE sessions SET revoked_at=? WHERE token_hash=?",clock.instant().toString(),secrets.hash(it.value)) }
            request.cookies?.firstOrNull { it.name=="${role}_csrf_context" }?.let { db.jdbc.update("DELETE FROM contexts WHERE token_hash=?",secrets.hash(it.value)) }
            db.jdbc.update("INSERT INTO sessions VALUES(?,?,?,?,?,NULL)",secrets.hash(raw),role.uppercase(),id,token,expiry.toString())
            SessionRecord(id,role,token,expiry)
        }
        cookie(response,"${role}_session",raw,172800)
        cookie(response,"${role}_csrf_context","",0,"/api/v1/$role")
        return result
    }
    fun logout(role: String,request: HttpServletRequest,response: HttpServletResponse) {
        db.write {
            request.cookies?.firstOrNull { it.name=="${role}_session" }?.let { db.jdbc.update("UPDATE sessions SET revoked_at=? WHERE token_hash=?",clock.instant().toString(),secrets.hash(it.value)) }
            request.cookies?.firstOrNull { it.name=="${role}_csrf_context" }?.let { db.jdbc.update("DELETE FROM contexts WHERE token_hash=?",secrets.hash(it.value)) };true
        }
        cookie(response,"${role}_session","",0);cookie(response,"${role}_csrf_context","",0,"/api/v1/$role")
    }
}
