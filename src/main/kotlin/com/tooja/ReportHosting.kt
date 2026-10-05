package com.tooja

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Path

@Configuration
@Profile("demo", "test")
class ReportHosting(@Value("\${app.report-root:./reports}") private val reportRoot: String) : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val directory = Path.of(reportRoot).resolve("allure").toAbsolutePath().toUri().toString().trimEnd('/') + "/"
        registry.addResourceHandler("/reports/allure/**")
            .addResourceLocations(directory)
            .setCachePeriod(0)
    }

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/reports/allure", "/reports/allure/index.html")
        registry.addRedirectViewController("/reports/allure/", "/reports/allure/index.html")
    }
}
