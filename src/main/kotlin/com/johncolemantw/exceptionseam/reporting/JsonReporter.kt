package com.johncolemantw.exceptionseam.reporting

import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

class JsonReporter(private val outputFile: File) : Reporter {
    override fun report(result: AnalysisResult) {
        val document = mapOf(
            "summary" to mapOf(
                "seams" to result.results.size,
                "issues" to result.issues.size,
                "allowListed" to result.allowListed.size,
                "bySeverity" to result.countBySeverity.mapKeys { it.key.name },
            ),
            "issues" to result.issues,
            "seams" to result.results,
        )
        outputFile.parentFile.mkdirs()
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outputFile, document)
    }
}
