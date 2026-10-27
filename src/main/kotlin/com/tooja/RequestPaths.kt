package com.tooja

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer
import org.springframework.web.util.ServletRequestPathUtils
import org.springframework.web.util.pattern.PathPatternParser

/** Filters use Spring MVC path semantics, never ad hoc URL decoding. */
internal object RequestPaths {
    private val parser = PathPatternParser.defaultInstance
    private val api = parser.parse("/api/**")
    private val versionedApi = parser.parse("/api/v1/**")
    private val actuator = parser.parse("/actuator/**")
    private val health = parser.parse("/actuator/health")

    private fun path(request: HttpServletRequest): PathContainer {
        if (!ServletRequestPathUtils.hasParsedRequestPath(request)) ServletRequestPathUtils.parseAndCache(request)
        return ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication()
    }

    fun isApi(request: HttpServletRequest) = api.matches(path(request))
    fun isVersionedApi(request: HttpServletRequest) = versionedApi.matches(path(request))
    fun isPrivateActuator(request: HttpServletRequest): Boolean {
        val path = path(request)
        return actuator.matches(path) && !health.matches(path)
    }
}
