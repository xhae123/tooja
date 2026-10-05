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

/** The managed LB replaces nginx; enforce body size in the app for fixed and chunked requests. */
@Component @Order(-50)
class EdgeRequestFilter(private val mapper: ObjectMapper,
    @Value("\${app.enforce-https:false}") private val enforceHttps: Boolean,
    @Value("\${app.origin}") private val origin: String): OncePerRequestFilter() {
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
            if(req.requestURI.startsWith("/api/") && req.method in setOf("POST","PATCH","PUT","DELETE")) {
                if(req.contentLengthLong>MAX_BODY_BYTES) { reject(req,res);return }
                val body=req.inputStream.readNBytes(MAX_BODY_BYTES+1)
                if(body.size>MAX_BODY_BYTES) { reject(req,res);return }
                chain.doFilter(BufferedBody(req,body),res)
            } else chain.doFilter(req,res)
        } finally {
            // No IP, body, query, raw path, headers, cookies or account data.
            log.info(mapper.writeValueAsString(mapOf("event" to "http_access","service" to "tooja",
                "method" to req.method,"route" to (req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()?:"unmatched"),
                "status" to res.status,"requestId" to (req.getAttribute("requestId")?:"unknown"),
                "requestTime" to (System.nanoTime()-start)/1e9)))
        }
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
