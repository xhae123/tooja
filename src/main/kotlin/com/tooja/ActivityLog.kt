package com.tooja

import jakarta.servlet.http.HttpServletRequest

/** Request attributes that controllers and services set so the access filter can name the business action. */
object ActivityAttributes {
    const val ACTION = "activity.action"
    const val ERROR_CODE = "activity.errorCode"
    const val TEAM_ID = "activity.teamId"
    const val AMOUNT = "activity.amount"
    const val INVESTMENT_ID = "activity.investmentId"
}

object ActivityActions {
    private val prefix = Regex("^/api/v1/(investor|admin|owner|public)/")
    fun derive(method: String, route: String, status: Int, explicit: String?): String {
        explicit?.let { return it }
        if (status == 413) return "body_too_large"
        if (route == "unmatched") return "unmatched"
        val name = name(route)
        return when {
            name == "session" && method == "POST" -> if (status < 300) "login_success" else "login_failed"
            name == "session" && method == "DELETE" -> "logout"
            name == "csrf" -> "csrf"
            route == "/api/v1/investor/investments" && method == "POST" -> if (status == 201) "invest_succeeded" else "invest_rejected"
            method == "GET" -> "view_$name"
            else -> "${method.lowercase()}_$name"
        }
    }
    // "/api/v1/admin/investors/{investorId}" -> "investor"; "/api/v1/investor/investment-requests/{key}" -> "investment_request"
    private fun name(route: String): String {
        val parts = ArrayList<String>()
        for (segment in route.replace(prefix, "").split('/').filter { it.isNotEmpty() }) {
            if (segment.startsWith("{")) { if (parts.isNotEmpty()) parts[parts.lastIndex] = parts.last().removeSuffix("s") }
            else parts.add(segment.replace('-', '_'))
        }
        return parts.joinToString("_").ifEmpty { "root" }
    }
}

object ClientIp {
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6 = Regex("^[0-9a-fA-F:]{2,45}$")
    /** The LB appends the client to X-Forwarded-For, so count trusted hops from the right; anything else is client data. */
    fun resolve(request: HttpServletRequest, trustedHops: Int): String {
        val forwarded = request.getHeader("X-Forwarded-For")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()
        if (trustedHops >= 1 && forwarded.size >= trustedHops) {
            val candidate = forwarded[forwarded.size - trustedHops]
            if (ipv4.matches(candidate) || (candidate.contains(':') && ipv6.matches(candidate))) return candidate
        }
        return request.remoteAddr ?: "unknown"
    }
}
