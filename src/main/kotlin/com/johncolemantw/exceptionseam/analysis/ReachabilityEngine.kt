package com.johncolemantw.exceptionseam.analysis

import com.johncolemantw.exceptionseam.model.ReachableException
import com.johncolemantw.exceptionseam.model.Seam

data class AnalysisConfig(
    val maxCallDepth: Int = 5,
    /** Callee types whose names start with one of these prefixes are not entered. */
    val excludePackages: Set<String> = emptySet(),
    /** Read `throws` clauses of JDK and classpath methods and constructors that are not in the sources. */
    val libraryThrows: Boolean = true,
) {
    fun isExcluded(type: String) = excludePackages.any { type.startsWith(it) }
}

interface ReachabilityEngine {
    /** Exceptions that can arrive at the seam's catch clause (after earlier clauses of the same try). */
    fun analyze(seam: Seam): Set<ReachableException>
}

class JavaSourceReachabilityEngine(
    private val index: SourceIndex,
    config: AnalysisConfig,
    private val spring: SpringRules? = null,
) : ReachabilityEngine {
    private val walker = CallGraphWalker(index, config, spring)

    override fun analyze(seam: Seam): Set<ReachableException> {
        val (tryStmt, clause) = index.locate(seam) ?: return emptySet()
        val clauseIndex = tryStmt.catchClauses.indexOf(clause)

        val earlier = tryStmt.catchClauses.take(clauseIndex).flatMap { index.typesOf(it.parameter.type, it).orEmpty() }
        val found = walker.reachableFrom(tryStmt.resources.toList() + tryStmt.tryBlock)
            .filterNot { e -> earlier.any { index.isSubtype(e.type, it) } }

        // One entry per type, preferring the shortest path.
        val unique = found.sortedBy { it.depth }.distinctBy { it.type }
        return (spring?.markHandled(unique, seam.enclosingClass) ?: unique).toCollection(LinkedHashSet())
    }
}
