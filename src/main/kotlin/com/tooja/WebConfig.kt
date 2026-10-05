package com.tooja

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.method.HandlerMethod
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.dao.DataAccessException
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.stereotype.Component
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Target(AnnotationTarget.FUNCTION,AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Access(val role: String)

@Component
class RequestIds: OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest,res: HttpServletResponse,chain: FilterChain) {
        val id="req_${UUID.randomUUID()}";req.setAttribute("requestId",id);res.setHeader("X-Request-ID",id);chain.doFilter(req,res)
    }
}
@Component
class RateLimits(private val clock: Clock) {
    private val buckets=ConcurrentHashMap<String,ArrayDeque<Long>>()
    fun check(key: String,limit: Int) {
        val now=clock.millis()
        if(buckets.size>2000) buckets.entries.removeIf { synchronized(it.value) { it.value.isEmpty() || it.value.last()<=now-60000 } }
        val q=buckets.computeIfAbsent(key){ArrayDeque()}
        synchronized(q) {
            while(q.isNotEmpty() && q.first()<=now-60000) q.removeFirst()
            if(q.size>=limit) throw ApiException(429,"RATE_LIMITED","요청이 많습니다. 잠시 후 다시 시도해 주세요.",retryAfter=(((q.first()+60000-now)+999)/1000).toInt().coerceAtLeast(1))
            q.addLast(now)
        }
    }
    fun clear()=buckets.clear()
}
@Configuration
class WebConfig(private val auth: AuthService,private val rates: RateLimits): WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object: HandlerInterceptor {
            override fun preHandle(req: HttpServletRequest,res: HttpServletResponse,handler: Any): Boolean {
                if(!req.requestURI.startsWith("/api/v1/") || handler !is HandlerMethod) return true
                val access=handler.getMethodAnnotation(Access::class.java) ?: handler.beanType.getAnnotation(Access::class.java)
                val role=if(req.requestURI.startsWith("/api/v1/admin/"))"admin" else "investor"
                if(access!=null) { val session=auth.require(access.role,req);req.setAttribute("principal",session);rates.check("${access.role}:${session.accountId}",180) }
                else if(req.requestURI.startsWith("/api/v1/public/")) rates.check("public:${req.remoteAddr}",300)
                val allowed=if(req.requestURI=="/api/v1/admin/investors") setOf("q","teamId","kind","spendingStatus") else emptySet()
                if(req.parameterMap.keys.any { it !in allowed } || req.parameterMap.values.any { it.size!=1 }) throw ApiException(422,"VALIDATION_FAILED","정의되지 않거나 중복된 쿼리 파라미터입니다.",mapOf("fieldErrors" to listOf(mapOf("field" to "query","reason" to "UNKNOWN_OR_DUPLICATE","message" to "허용된 필터를 한 번씩만 보내세요."))))
                if(req.method in listOf("POST","PATCH","DELETE")) {
                    if(req.method=="DELETE" && (req.contentLengthLong>0 || req.getHeader("Transfer-Encoding")!=null)) throw ApiException(400,"INVALID_REQUEST","로그아웃에는 본문을 보내지 않습니다.")
                    auth.verifyCsrf(role,req)
                }
                return true
            }
        }).addPathPatterns("/api/v1/**")
    }
}
@RestControllerAdvice
class Errors(private val telemetry: Telemetry) {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException,req: HttpServletRequest): ResponseEntity<ApiError> {
        val builder=ResponseEntity.status(e.status); e.retryAfter?.let { builder.header("Retry-After",it.toString()) }
        return builder.body(ApiError(ErrorBody(e.code,e.message,e.details),req.getAttribute("requestId")?.toString()?:"unknown"))
    }
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException,req: HttpServletRequest): ResponseEntity<ApiError> = api(ApiException(422,"VALIDATION_FAILED","입력값을 확인해 주세요.",mapOf("fieldErrors" to e.bindingResult.fieldErrors.map { mapOf("field" to it.field,"reason" to (it.code?:"INVALID"),"message" to (it.defaultMessage?:"잘못된 값입니다.")) })),req)
    @ExceptionHandler(HttpMessageNotReadableException::class,org.springframework.web.HttpMediaTypeNotSupportedException::class)
    fun malformed(e: Exception,req: HttpServletRequest): ResponseEntity<ApiError> = api(ApiException(400,"INVALID_REQUEST","JSON 본문·타입·필드 구성을 확인해 주세요."),req)
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,org.springframework.web.bind.MissingRequestHeaderException::class)
    fun type(e: Exception,req: HttpServletRequest): ResponseEntity<ApiError> = api(ApiException(422,"VALIDATION_FAILED","경로·헤더·필터 입력값을 확인해 주세요.",mapOf("fieldErrors" to listOf(mapOf("field" to "request","reason" to "INVALID_TYPE","message" to "필수 입력값과 타입을 확인하세요.")))),req)
    @ExceptionHandler(DataAccessException::class)
    fun database(e: DataAccessException,req: HttpServletRequest): ResponseEntity<ApiError> {
        telemetry.error(e,req,503,"SERVICE_UNAVAILABLE")
        return api(ApiException(503,"SERVICE_UNAVAILABLE","저장소에 연결할 수 없습니다. 결과를 다시 확인해 주세요.",retryAfter=5),req)
    }
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException::class)
    fun notFound(e: Exception,req: HttpServletRequest): ResponseEntity<ApiError> = api(ApiException(404,"RESOURCE_NOT_FOUND","요청한 경로를 찾을 수 없습니다."),req)
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun wrongMethod(e: org.springframework.web.HttpRequestMethodNotSupportedException,req: HttpServletRequest): ResponseEntity<ApiError> {
        val result=api(ApiException(405,"METHOD_NOT_ALLOWED","이 경로에서 허용하지 않는 HTTP 메서드입니다."),req)
        return ResponseEntity.status(405).headers(result.headers).header("Allow",e.supportedMethods?.joinToString(", ")?:"").body(result.body)
    }
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception,req: HttpServletRequest): ResponseEntity<ApiError> {
        telemetry.error(e,req,500,"INTERNAL_ERROR")
        return api(ApiException(500,"INTERNAL_ERROR","처리 중 오류가 발생했습니다. 결과를 다시 확인해 주세요."),req)
    }
}
