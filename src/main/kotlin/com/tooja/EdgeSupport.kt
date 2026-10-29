package com.tooja

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Clock

/** The managed LB replaces nginx; enforce body size in the app for fixed and chunked requests. */
@Component @Order(-50)
class EdgeRequestFilter(private val mapper: ObjectMapper,
    @Value("\${app.enforce-https:false}") private val enforceHttps: Boolean,
    @Value("\${app.origin}") private val origin: String,
    private val ops: OpsWriter? = null,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${app.trusted-proxy-hops:1}") private val trustedHops: Int = 1): OncePerRequestFilter() {
    private val log=LoggerFactory.getLogger("com.tooja.access")
    companion object { const val MAX_BODY_BYTES=1048576 }
    override fun doFilterInternal(req: HttpServletRequest,res: HttpServletResponse,chain: FilterChain) {
        val start=System.nanoTime()
        try {
            // This header is overwritten by the trusted LB. Backend ports accept LB traffic only.
            if(enforceHttps && req.getHeader("X-Forwarded-Proto")=="http" &&
                !req.requestURI.startsWith("/.well-known/acme-challenge/")) {
                res.status=308;res.setHeader("Location",origin.trimEnd('/')+req.requestURI+(req.queryString?.let { "?$it" }?:""));return
            }
            if(RequestPaths.isOwner(req)) {
                // same-origin, not no-referrer: Chrome derives the form POST Origin header from this policy and would send "null".
                res.setHeader("Cache-Control","no-store");res.setHeader("X-Frame-Options","DENY")
                res.setHeader("Referrer-Policy","same-origin");res.setHeader("X-Robots-Tag","noindex")
            }
            if((RequestPaths.isApi(req) || RequestPaths.isInternal(req)) && req.method in setOf("POST","PATCH","PUT","DELETE")) {
                if(req.contentLengthLong>MAX_BODY_BYTES) { reject(req,res);return }
                val body=req.inputStream.readNBytes(MAX_BODY_BYTES+1)
                if(body.size>MAX_BODY_BYTES) { reject(req,res);return }
                chain.doFilter(BufferedBody(req,body),res)
            } else chain.doFilter(req,res)
        } finally {
            val route=req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()?:"unmatched"
            val elapsed=System.nanoTime()-start
            // Stdout stays free of IP, body, query, raw path, headers, cookies or account data.
            log.info(mapper.writeValueAsString(mapOf("event" to "http_access","service" to "tooja",
                "method" to req.method,"route" to route,
                "status" to res.status,"requestId" to (req.getAttribute("requestId")?:"unknown"),
                "requestTime" to elapsed/1e9)))
            // The owner console's activity log deliberately records the client address and agent (ADR 0036).
            if(ops!=null && RequestPaths.isApi(req)) ops.offer(activity(req,res,route,elapsed))
        }
    }
    private fun activity(req: HttpServletRequest,res: HttpServletResponse,route: String,elapsed: Long): ActivityRow {
        val principal=req.getAttribute("principal") as? SessionRecord
        return ActivityRow(clock.millis(),req.getAttribute("requestId")?.toString()?:"unknown",req.method,route.take(128),res.status,(elapsed/1_000_000).toInt(),
            principal?.role?.uppercase(),principal?.accountId,
            ActivityActions.derive(req.method,route,res.status,req.getAttribute(ActivityAttributes.ACTION)?.toString()),
            req.getAttribute(ActivityAttributes.ERROR_CODE)?.toString(),
            req.getAttribute(ActivityAttributes.TEAM_ID) as? Int,req.getAttribute(ActivityAttributes.INVESTMENT_ID)?.toString(),req.getAttribute(ActivityAttributes.AMOUNT) as? Int,
            req.getHeader("Origin")?.take(255),ClientIp.resolve(req,trustedHops),req.remoteAddr?:"unknown",req.getHeader("User-Agent")?.take(512))
    }
    private fun reject(req: HttpServletRequest,res: HttpServletResponse) {
        res.status=413;res.contentType="application/json";res.characterEncoding="UTF-8"
        mapper.writeValue(res.writer,ApiError(ErrorBody("REQUEST_BODY_TOO_LARGE","요청 본문은 1MiB 이하여야 합니다.",mapOf("maxBytes" to MAX_BODY_BYTES)),req.getAttribute("requestId")?.toString()?:"unknown"))
    }
    private class BufferedBody(req: HttpServletRequest,private val body: ByteArray): HttpServletRequestWrapper(req) {
        override fun getContentLength()=body.size
        override fun getContentLengthLong()=body.size.toLong()
        override fun getInputStream(): ServletInputStream {
            val input=ByteArrayInputStream(body)
            return object: ServletInputStream() {
                override fun read()=input.read()
                override fun read(bytes: ByteArray,offset: Int,length: Int)=input.read(bytes,offset,length)
                override fun isFinished()=input.available()==0
                override fun isReady()=true
                override fun setReadListener(listener: ReadListener) { throw IllegalStateException("Blocking request body") }
            }
        }
        override fun getReader()=BufferedReader(InputStreamReader(inputStream,characterEncoding?:"UTF-8"))
    }
}
