package com.johncolemantw.exceptionseam.model

enum class Severity {
    LOW, MEDIUM, HIGH, CRITICAL;

    companion object {
        /** `null` for "NONE" (never fail); throws for anything unrecognised. */
        fun parseThreshold(value: String): Severity? =
            if (value.equals("NONE", ignoreCase = true)) null
            else entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown severity '$value'; expected CRITICAL, HIGH, MEDIUM, LOW or NONE")
    }
}

enum class IssueKind {
    /** Catches `Exception`, `RuntimeException` or `Throwable`. */
    BROAD_CATCH,

    /** Catches a supertype although only narrower subtypes can reach it. */
    OVERLY_WIDE_CATCH,

    /** Catches a project-defined exception that nothing reachable throws. */
    DEAD_CATCH,

    /** Empty catch body. CRITICAL severity: the most severe defect, reported ahead of everything else. */
    SWALLOWED_EXCEPTION,
}

data class Issue(
    val kind: IssueKind,
    val severity: Severity,
    val message: String,
    val suggestion: String,
    val file: String,
    val line: Int,
    val column: Int,
    val enclosingMethod: String?,
    val caughtTypes: List<String>,
    /** Fully qualified names of the exceptions relevant to this issue. */
    val reachable: List<String>,
    /** The allow-list pattern that suppresses this issue, if any. */
    val allowListedBy: String? = null,
    /** A paste-ready replacement catch header, e.g. `catch (java.io.IOException | java.sql.SQLException e)`. */
    val suggestedCatch: String? = null,
    /** The [reachable] exceptions that usually indicate a bug (see [LikelyBugs]); informational, never changes severity. */
    val likelyBugs: List<String> = emptyList(),
)

data class SeamResult(
    val seam: Seam,
    val reachable: List<ReachableException>,
    val issues: List<Issue>,
)

data class AnalysisResult(val results: List<SeamResult>) {
    private val everyIssue: List<Issue> get() = results.flatMap { it.issues }

    /** Active issues, most severe first. Allow-listed issues are excluded. */
    val issues: List<Issue>
        get() = everyIssue.filter { it.allowListedBy == null }.sorted()

    val allowListed: List<Issue>
        get() = everyIssue.filter { it.allowListedBy != null }.sorted()

    val countBySeverity: Map<Severity, Int>
        get() = Severity.entries.reversed().associateWith { s -> issues.count { it.severity == s } }

    private fun List<Issue>.sorted() =
        sortedWith(compareByDescending<Issue> { it.severity }.thenBy { it.file }.thenBy { it.line })
}
