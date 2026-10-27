package com.tooja

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Access(val role: String)

/** Public means no session; login/logout still require the area's CSRF token. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PublicAccess(val role: String = "public")

@Component
class ApiSecurityInterceptor(private val auth: AuthService, private val rates: RateLimits): HandlerInterceptor {
    override fun preHandle(req: HttpServletRequest, res: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true
        val access = handler.getMethodAnnotation(Access::class.java)
            ?: AnnotatedElementUtils.findMergedAnnotation(handler.beanType, Access::class.java)
        val publicAccess = handler.getMethodAnnotation(PublicAccess::class.java)
            ?: AnnotatedElementUtils.findMergedAnnotation(handler.beanType, PublicAccess::class.java)

        // Authorize the selected controller, regardless of the URL's spelling.
        // Accidentally adding an API without a policy must never make it public.
        if (access == null && publicAccess == null) {
            if (RequestPaths.isVersionedApi(req)) throw ApiException(403,"FORBIDDEN","접근 정책이 지정되지 않은 API입니다.")
            return true
        }
        check(access == null || publicAccess == null) { "An API must have exactly one access policy" }
        val role = access?.role ?: publicAccess!!.role
        check(role in setOf("investor","admin") || (access == null && role == "public")) { "Unknown API access role" }
        if (access != null) {
            val session = auth.require(role,req)
            req.setAttribute("principal",session)
            rates.check("$role:${session.accountId}",180)
        } else if (role == "public") rates.check("public:${req.remoteAddr}",30000)

        // The matched pattern is trusted mapping metadata, not client input.
        val route = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
        val allowed = when (route) {
            "/api/v1/admin/investors" -> setOf("q","teamId","kind","spendingStatus")
            "/api/v1/admin/investor-rankings" -> setOf("group")
            else -> emptySet()
        }
        if (req.parameterMap.keys.any { it !in allowed } || req.parameterMap.values.any { it.size != 1 }) {
            throw ApiException(422,"VALIDATION_FAILED","정의되지 않거나 중복된 쿼리 파라미터입니다.",
                mapOf("fieldErrors" to listOf(mapOf("field" to "query","reason" to "UNKNOWN_OR_DUPLICATE","message" to "허용된 필터를 한 번씩만 보내세요."))))
        }
        if (req.method in listOf("POST","PATCH","DELETE")) {
            if (req.method == "DELETE" && (req.contentLengthLong > 0 || req.getHeader("Transfer-Encoding") != null)) {
                throw ApiException(400,"INVALID_REQUEST","로그아웃에는 본문을 보내지 않습니다.")
            }
            auth.verifyCsrf(role,req)
        }
        return true
    }
}
