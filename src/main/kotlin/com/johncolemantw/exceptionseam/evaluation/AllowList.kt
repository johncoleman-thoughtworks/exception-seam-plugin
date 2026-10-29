package com.johncolemantw.exceptionseam.evaluation

import com.johncolemantw.exceptionseam.model.Issue
import com.johncolemantw.exceptionseam.model.IssueKind

/**
 * Suppresses broad-catch issues at intentional places. Patterns look like `file-glob` or
 * `file-glob#method-glob`; see `ExceptionSeamExtension.allowList`.
 */
class AllowList(patterns: Collection<String>) {
    private class Entry(val raw: String, val file: Regex, val fileNameOnly: Boolean, val method: Regex?)

    private val entries = patterns.map { raw ->
        val file = raw.substringBefore('#')
        val method = raw.substringAfter('#', "").takeIf { '#' in raw }
        Entry(raw, glob(file), '/' !in file, method?.let(::glob))
    }

    fun apply(issues: List<Issue>): List<Issue> = issues.map { issue ->
        if (issue.kind != IssueKind.BROAD_CATCH) return@map issue
        val match = entries.firstOrNull { matches(it, issue) } ?: return@map issue
        issue.copy(allowListedBy = match.raw)
    }

    private fun matches(entry: Entry, issue: Issue): Boolean {
        val path = if (entry.fileNameOnly) issue.file.substringAfterLast('/') else issue.file
        if (!entry.file.matches(path)) return false
        val method = entry.method ?: return true
        return method.matches(issue.enclosingMethod?.substringBefore('(').orEmpty())
    }

    private companion object {
        fun glob(pattern: String): Regex {
            val out = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3 }
                    pattern.startsWith("**", i) -> { out.append(".*"); i += 2 }
                    pattern[i] == '*' -> { out.append("[^/]*"); i++ }
                    pattern[i] == '?' -> { out.append("[^/]"); i++ }
                    else -> { out.append(Regex.escape(pattern[i].toString())); i++ }
                }
            }
            return Regex(out.toString())
        }
    }
}
