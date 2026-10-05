package com.tooja

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Path

@Hidden
@RestController
@ConditionalOnProperty(name=["app.reports-enabled"],havingValue="true")
class DeploymentMetadataController(@Value("\${app.report-root:./reports}") private val reportRoot: String) {
    @GetMapping("/deployment.json")
    fun metadata(): ResponseEntity<Resource> {
        val file=FileSystemResource(Path.of(reportRoot).resolve("deployment.json"))
        return if(file.isFile && file.exists()) ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(file)
        else ResponseEntity.notFound().build()
    }
}
