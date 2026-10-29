package com.johncolemantw.exceptionseam.reporting

import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.LikelyBugs

class ConsoleReporter(private val print: (String) -> Unit) : Reporter {
    override fun report(result: AnalysisResult) {
        val issues = result.issues
        if (issues.isEmpty()) {
            print("Exception Seam Analysis: no issues in ${result.results.size} catch seam(s)" + allowNote(result))
            return
        }
        val text = buildString {
            appendLine("Exception Seam Analysis found ${issues.size} issue(s) in ${result.results.size} catch seam(s):")
            issues.forEach { i ->
                appendLine()
                appendLine("${i.file}:${i.line}")
                appendLine("  [${i.severity}] ${i.message}")
                if (i.reachable.isNotEmpty()) {
                    appendLine("  Reachable: ${i.reachable.joinToString { it.substringAfterLast('.') }}")
                }
                if (i.likelyBugs.isNotEmpty()) {
                    appendLine("  Likely bugs: ${LikelyBugs.describe(i.likelyBugs)} - fix, or let them fail; do not swallow")
                }
                appendLine("  Suggestion: ${i.suggestion}")
                i.suggestedCatch?.let { appendLine("  Suggested:  $it") }
            }
            appendLine()
            append(result.countBySeverity.entries.joinToString(", ") { "${it.value} ${it.key}" })
            append(allowNote(result))
        }
        print(text)
    }

    private fun allowNote(result: AnalysisResult) =
        result.allowListed.size.takeIf { it > 0 }?.let { " ($it allow-listed)" }.orEmpty()
}
