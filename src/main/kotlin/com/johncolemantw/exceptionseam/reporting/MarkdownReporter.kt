package com.johncolemantw.exceptionseam.reporting

import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.Issue
import com.johncolemantw.exceptionseam.model.LikelyBugs
import com.johncolemantw.exceptionseam.model.SeamResult
import java.io.File

/** GitHub-flavoured Markdown: good for PR comments, job summaries, tickets and pasting into an AI assistant. */
class MarkdownReporter(
    private val outputFile: File,
    /** Directory that issue file paths are relative to; used to show code context. */
    private val baseDir: File,
) : Reporter {

    override fun report(result: AnalysisResult) {
        val findings = result.results.flatMap { r -> r.issues.map { r to it } }
        val active = findings.filter { it.second.allowListedBy == null }
            .sortedWith(compareByDescending<Pair<SeamResult, Issue>> { it.second.severity }
                .thenBy { it.second.file }.thenBy { it.second.line })
        val text = buildString {
            appendLine("# Exception Seam Report")
            appendLine()
            appendLine("${result.results.size} catch seams analysed, ${result.issues.size} issue(s), " +
                "${result.allowListed.size} allow-listed.")
            appendLine()
            if (active.isEmpty()) {
                appendLine("No issues found.")
            } else {
                appendLine("| Severity | Count |")
                appendLine("|---|---|")
                result.countBySeverity.forEach { (s, n) -> appendLine("| $s | $n |") }
                appendLine()
                appendLine("| Severity | Kind | Location | Issue |")
                appendLine("|---|---|---|---|")
                active.forEach { (_, i) ->
                    appendLine("| ${i.severity} | ${i.kind} | `${i.file}:${i.line}` | ${cell(i.message)} |")
                }
                active.forEachIndexed { n, (r, i) -> appendDetail(n + 1, r, i) }
            }
            val allowed = findings.filter { it.second.allowListedBy != null }
            if (allowed.isNotEmpty()) {
                appendLine()
                appendLine("## Allow-listed")
                appendLine()
                allowed.forEach { (_, i) ->
                    appendLine("- `${i.file}:${i.line}` ${cell(i.message)} (allow-listed by `${i.allowListedBy}`)")
                }
            }
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(text)
    }

    private fun StringBuilder.appendDetail(n: Int, r: SeamResult, i: Issue) {
        appendLine()
        appendLine("## $n. `${i.file}:${i.line}` - ${i.kind}")
        appendLine()
        appendLine("**${i.severity}**: ${i.message}")
        i.enclosingMethod?.let { appendLine("in `$it`") }
        appendLine()
        appendLine("**Suggestion:** ${i.suggestion}")
        if (i.likelyBugs.isNotEmpty()) {
            appendLine()
            appendLine("**Likely bugs:** ${LikelyBugs.describe(i.likelyBugs)} - fix, or let them fail; do not swallow")
        }
        i.suggestedCatch?.let {
            appendLine()
            appendLine("```java")
            appendLine(it)
            appendLine("```")
        }
        codeContext(r)?.let {
            appendLine()
            appendLine("```java")
            appendLine(it)
            appendLine("```")
        }
        if (r.reachable.isNotEmpty()) {
            appendLine()
            appendLine("Reachable exceptions:")
            appendLine()
            r.reachable.forEach { e ->
                val via = if (e.callPath.isEmpty()) "try body" else e.callPath.joinToString(" -> ")
                val notes = listOfNotNull(e.reason, e.handledBy?.let { "handled by $it" }).joinToString("; ")
                appendLine("- `${e.type}` (${e.origin.name.lowercase()}, checked: ${e.checked ?: "unknown"}, via $via)" +
                    if (notes.isNotEmpty()) " - $notes" else "")
            }
        }
    }

    private fun codeContext(r: SeamResult): String? {
        val lines = runCatching { baseDir.resolve(r.seam.file).readLines() }.getOrNull() ?: return null
        val from = maxOf(1, r.seam.line - 3)
        val to = minOf(lines.size, r.seam.endLine + 2)
        if (from > to) return null
        return (from..to).joinToString("\n") { n ->
            val marker = if (n in r.seam.line..r.seam.endLine) ">" else " "
            "$marker ${n.toString().padStart(4)}  ${lines[n - 1]}"
        }
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
}
