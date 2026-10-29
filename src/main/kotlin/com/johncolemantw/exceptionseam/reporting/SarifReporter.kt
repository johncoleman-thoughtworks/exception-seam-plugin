package com.johncolemantw.exceptionseam.reporting

import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.Issue
import com.johncolemantw.exceptionseam.model.IssueKind
import com.johncolemantw.exceptionseam.model.SeamResult
import com.johncolemantw.exceptionseam.model.Severity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/** SARIF 2.1.0, suitable for GitHub code scanning and other CI viewers. */
class SarifReporter(private val outputFile: File) : Reporter {

    override fun report(result: AnalysisResult) {
        val findings = result.results.flatMap { r -> r.issues.map { r to it } }
        val document = mapOf(
            "\$schema" to "https://json.schemastore.org/sarif-2.1.0.json",
            "version" to "2.1.0",
            "runs" to listOf(
                mapOf(
                    "tool" to mapOf(
                        "driver" to mapOf(
                            "name" to "exception-seam",
                            "version" to (javaClass.`package`?.implementationVersion ?: "dev"),
                            "rules" to IssueKind.entries.map(::rule),
                        ),
                    ),
                    "results" to findings.map { (r, i) -> sarifResult(r, i) },
                ),
            ),
        )
        outputFile.parentFile.mkdirs()
        jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outputFile, document)
    }

    private fun rule(kind: IssueKind) = mapOf(
        "id" to kind.name,
        "shortDescription" to mapOf("text" to DESCRIPTIONS.getValue(kind)),
        "defaultConfiguration" to mapOf("level" to level(DEFAULT_SEVERITY.getValue(kind))),
    )

    private fun sarifResult(r: SeamResult, issue: Issue): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>(
            "ruleId" to issue.kind.name,
            "level" to level(issue.severity),
            "message" to mapOf("text" to "${issue.message}. Suggestion: ${issue.suggestion}"),
            "locations" to listOf(
                mapOf(
                    "physicalLocation" to mapOf(
                        "artifactLocation" to mapOf("uri" to issue.file, "uriBaseId" to "%SRCROOT%"),
                        "region" to mapOf(
                            "startLine" to issue.line,
                            "startColumn" to issue.column,
                            "endLine" to r.seam.endLine,
                        ),
                    ),
                ),
            ),
            "properties" to mapOf(
                "severity" to issue.severity.name,
                "caughtTypes" to issue.caughtTypes,
                "reachable" to issue.reachable,
                "enclosingMethod" to issue.enclosingMethod,
                "suggestedCatch" to issue.suggestedCatch,
                "likelyBugs" to issue.likelyBugs,
            ),
        )
        issue.allowListedBy?.let {
            result["suppressions"] = listOf(mapOf("kind" to "external", "justification" to "allow-list: $it"))
        }
        return result
    }

    private fun level(severity: Severity) = when (severity) {
        Severity.CRITICAL, Severity.HIGH -> "error"
        Severity.MEDIUM -> "warning"
        Severity.LOW -> "note"
    }

    private companion object {
        val DESCRIPTIONS = mapOf(
            IssueKind.BROAD_CATCH to "Catches Exception, RuntimeException or Throwable",
            IssueKind.OVERLY_WIDE_CATCH to "Catches a supertype although only narrower exceptions can reach it",
            IssueKind.DEAD_CATCH to "Catches a project exception that nothing reachable throws",
            IssueKind.SWALLOWED_EXCEPTION to "Catch block swallows the exception: it is empty, or a broad catch that does not always rethrow",
        )
        val DEFAULT_SEVERITY = mapOf(
            IssueKind.BROAD_CATCH to Severity.HIGH,
            IssueKind.OVERLY_WIDE_CATCH to Severity.MEDIUM,
            IssueKind.DEAD_CATCH to Severity.LOW,
            IssueKind.SWALLOWED_EXCEPTION to Severity.CRITICAL,
        )
    }
}
