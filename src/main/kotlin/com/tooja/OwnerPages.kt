package com.tooja

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

/** Read-only operator console rendered on the server. Every handler here runs after OwnerPageInterceptor. */
@Controller
@RequestMapping("/owner")
class OwnerPages(private val auth: OwnerAuth, private val queries: OwnerQueries, private val events: SystemEvents) {
    private val keyMetrics = listOf("app" to "http.rate_per_min", "app" to "http.p95_ms", "app" to "http.errors.count", "host" to "host.cpu.percent", "host" to "host.mem.used_percent", "host" to "host.disk.used_percent",
        "app" to "jvm.heap.used", "app" to "hikari.active", "app" to "mysql.threads_connected", "oci" to "oci.lb.requests", "oci" to "oci.lb.5xx", "oci" to "oci.apm.availability", "oci" to "oci.waf.requests")
    @ModelAttribute fun common(model: Model, req: HttpServletRequest) {
        model.addAttribute("fmt", OwnerFormat); model.addAttribute("ownerSession", req.getAttribute("ownerSession")); model.addAttribute("csrf", (req.getAttribute("ownerSession") as? OwnerSession)?.csrf)
        if (req.getAttribute("ownerSession") != null) model.addAttribute("pulse", queries.pulse())
    }

    @GetMapping("/login") fun loginForm(req: HttpServletRequest, res: HttpServletResponse, model: Model): String {
        if (auth.session(req) != null) return "redirect:/owner"
        model.addAttribute("csrf", auth.csrf(req, res)); return "owner/login"
    }
    @PostMapping("/login") fun login(@RequestParam token: String, @RequestParam("_csrf") csrf: String?, req: HttpServletRequest, res: HttpServletResponse, model: Model): String {
        if (!auth.verifyCsrf(req, csrf)) { res.status = 403; model.addAttribute("error", "화면을 새로 열어 다시 시도해 주세요."); model.addAttribute("csrf", auth.csrf(req, res)); return "owner/login" }
        return when (val result = auth.login(token, req, res)) {
            OwnerAuth.LoginResult.Success -> "redirect:/owner"
            OwnerAuth.LoginResult.Invalid -> { res.status = 401; model.addAttribute("error", "토큰이 올바르지 않습니다."); model.addAttribute("csrf", auth.csrf(req, res)); "owner/login" }
            is OwnerAuth.LoginResult.Limited -> { res.status = 429; res.setHeader("Retry-After", result.retryAfter.toString()); model.addAttribute("error", "시도가 많습니다. ${result.retryAfter}초 후 다시 시도해 주세요."); model.addAttribute("csrf", auth.csrf(req, res)); "owner/login" }
        }
    }
    @PostMapping("/logout") fun logout(@RequestParam("_csrf") csrf: String?, req: HttpServletRequest, res: HttpServletResponse): String {
        if (auth.verifyCsrf(req, csrf)) auth.logout(req, res)
        return "redirect:/owner/login"
    }

    @GetMapping("", "/") fun overview(model: Model): String {
        val o = queries.overview(); model.addAttribute("o", o); model.addAttribute("page", "overview")
        model.addAttribute("latestBySource", bySource(o["latest"] as List<Map<String, Any?>>))
        model.addAttribute("sparks", listOf("app" to "http.rate_per_min", "app" to "http.p95_ms", "app" to "http.errors.count", "host" to "host.cpu.percent", "host" to "host.mem.used_percent", "app" to "hikari.active")
            .associate { (s, m) -> m to queries.chart(s, m, 3600, 110, 34, labels = false) })
        return "owner/overview"
    }
    @GetMapping("/activity") fun activity(@RequestParam filters: Map<String, String>, @RequestParam(required=false) cursor: Long?, @RequestParam(required=false) limit: Int?, model: Model): String {
        val size = (limit ?: 50).coerceIn(1, 200)
        model.addAttribute("f", filters); model.addAttribute("result", queries.activity(filters, cursor, size)); model.addAttribute("actions", queries.actions()); model.addAttribute("limit", size); model.addAttribute("page", "activity")
        model.addAttribute("base", base("/owner/activity", filters)); model.addAttribute("activeFilters", filters.filterKeys { it !in setOf("limit", "cursor") }.filterValues(String::isNotBlank))
        return "owner/activity"
    }
    private fun bySource(rows: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> = linkedMapOf("app" to emptyList<Map<String, Any?>>(), "host" to emptyList(), "oci" to emptyList()).also { m -> rows.groupBy { it["source"].toString() }.forEach { (k, v) -> m[k] = v } }
    /** "More" links keep every filter and append only the cursor. */
    private fun base(path: String, filters: Map<String, String>): String {
        val query = filters.filterKeys { it != "cursor" }.filterValues(String::isNotBlank)
            .map { (k, v) -> java.net.URLEncoder.encode(k, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(v, Charsets.UTF_8) }
        return path + "?" + query.joinToString("&") + (if (query.isEmpty()) "" else "&")
    }
    @GetMapping("/accounts") fun accounts(@RequestParam filters: Map<String, String>, model: Model): String {
        val rows = queries.accounts(filters)
        model.addAttribute("f", filters); model.addAttribute("rows", rows); model.addAttribute("now", System.currentTimeMillis()); model.addAttribute("page", "accounts"); return "owner/accounts"
    }
    @GetMapping("/accounts/{id}") fun account(@PathVariable id: String, model: Model, res: HttpServletResponse): String {
        val detail = queries.account(id)
        if (detail.isNullOrEmpty()) { res.status = 404; model.addAttribute("message", "계정을 찾을 수 없습니다."); return "owner/error" }
        model.addAllAttributes(detail); model.addAttribute("page", "accounts")
        model.addAttribute("rejectedCount", (detail["requests"] as List<Map<String, Any?>>).count { it["status"] == "REJECTED" })
        model.addAttribute("activeSessionCount", (detail["sessions"] as List<Map<String, Any?>>).count { it["revoked_at"] == null }); return "owner/account"
    }
    @GetMapping("/sessions") fun sessions(@RequestParam(required=false) role: String?, @RequestParam(required=false) active: String?, model: Model): String {
        model.addAttribute("rows", queries.sessions(role, active == "1")); model.addAttribute("role", role ?: ""); model.addAttribute("active", active == "1"); model.addAttribute("page", "sessions"); return "owner/sessions"
    }
    @GetMapping("/investments") fun investments(@RequestParam filters: Map<String, String>, model: Model): String {
        model.addAttribute("f", filters); model.addAllAttributes(queries.investments(filters)); model.addAttribute("page", "investments"); return "owner/investments"
    }
    @GetMapping("/infra") fun infra(@RequestParam(required=false) range: String?, @RequestParam(required=false) metric: String?, @RequestParam(required=false) source: String?, model: Model): String {
        val seconds = when (range) { "6h" -> 6 * 3600L; "24h" -> 86400L; "7d" -> 7 * 86400L; else -> 3600L }
        val available = queries.metrics()
        val selected = if (metric != null && source != null) listOf(source to metric) else keyMetrics.filter { (s, m) -> available.any { it["source"] == s && it["metric"] == m } }
        model.addAttribute("range", range ?: "1h"); model.addAttribute("available", available); model.addAttribute("availableBySource", bySource(available)); model.addAttribute("single", metric != null)
        model.addAttribute("charts", selected.map { (s, m) -> queries.chart(s, m, seconds, if (metric != null) 960 else 640, if (metric != null) 280 else 160) })
        model.addAttribute("page", "infra"); return "owner/infra"
    }
    @GetMapping("/events") fun events(@RequestParam(required=false) type: String?, @RequestParam(required=false) cursor: Long?, model: Model): String {
        model.addAttribute("type", type ?: ""); model.addAttribute("result", queries.systemEvents(type, cursor, 100)); model.addAttribute("page", "events")
        model.addAttribute("base", base("/owner/events", mapOf("type" to (type ?: "")))); return "owner/events"
    }
    @GetMapping("/errors") fun errors(@RequestParam filters: Map<String, String>, @RequestParam(required=false) cursor: Long?, model: Model): String {
        model.addAttribute("f", filters); model.addAttribute("result", queries.errors(filters, cursor, 100)); model.addAttribute("page", "errors")
        model.addAttribute("base", base("/owner/errors", filters)); return "owner/errors"
    }
    @ExceptionHandler(Exception::class) fun failure(e: Exception, model: Model, res: HttpServletResponse, req: HttpServletRequest): String {
        res.status = if (e is ApiException) e.status else 500
        model.addAttribute("fmt", OwnerFormat); model.addAttribute("message", if (e is ApiException) e.message else "페이지를 그리는 중 오류가 났습니다. requestId: ${req.getAttribute("requestId")}")
        return "owner/error"
    }
}
