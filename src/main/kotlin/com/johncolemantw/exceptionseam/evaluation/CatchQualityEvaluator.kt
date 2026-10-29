package com.johncolemantw.exceptionseam.evaluation

import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.model.CatchBodyKind
import com.johncolemantw.exceptionseam.model.Issue
import com.johncolemantw.exceptionseam.model.IssueKind
import com.johncolemantw.exceptionseam.model.LikelyBugs
import com.johncolemantw.exceptionseam.model.ReachableException
import com.johncolemantw.exceptionseam.model.Seam
import com.johncolemantw.exceptionseam.model.Severity

interface CatchQualityEvaluator {
    fun evaluate(seam: Seam, reachable: Set<ReachableException>): List<Issue>
}

class DefaultCatchQualityEvaluator(private val index: SourceIndex) : CatchQualityEvaluator {

    override fun evaluate(seam: Seam, reachable: Set<ReachableException>): List<Issue> {
        val caught = index.locate(seam)?.let { (_, clause) -> index.typesOf(clause.parameter.type, clause) }
            ?: seam.caughtTypes
        val issues = mutableListOf<Issue>()

        fun issue(
            kind: IssueKind, severity: Severity, message: String, suggestion: String, relevant: Collection<String>,
            suggestedCatch: String? = null,
        ) {
            issues += Issue(
                kind, severity, message, suggestion, seam.file, seam.line, seam.column,
                seam.enclosingMethod, caught, relevant.toList(), suggestedCatch = suggestedCatch,
                likelyBugs = LikelyBugs.among(relevant),
            )
        }

        /** The catch header with [replaced] swapped for [with], redundant (sub)types removed. */
        fun rewrite(replaced: String, with: List<String>): String {
            val types = mostGeneral(caught.flatMap { if (it == replaced) with else listOf(it) })
            return "catch (${types.joinToString(" | ") { display(it) }} ${seam.variableName})"
        }

        /** What to catch instead of the broad [type], from the exceptions that can actually reach this catch. */
        class Advice(val suggestion: String, val handled: List<String>, val suggestedCatch: String?)

        fun adviceFor(type: String): Advice {
            val handledEntries = reachable.filter { index.isSubtype(it.type, type) }
            val handled = handledEntries.map { it.type }
            val advised = handledEntries.mapNotNull { it.handledBy }.toSet()
            val adviceNote = if (advised.isNotEmpty() && handledEntries.all { it.handledBy != null }) {
                "; these are already handled by ${advised.joinToString()} - consider letting them propagate"
            } else ""
            // Checked (or unclassified) exceptions must be dealt with here or declared; unchecked ones
            // can usually propagate, so they are not part of the suggested catch.
            val mustHandle = handledEntries.filter { it.checked != false }.map { it.type }
            val unchecked = handledEntries.filter { it.checked == false }.map { it.type }
            val replacement: List<String>
            val suggestion: String
            when {
                handled.isEmpty() -> {
                    suggestion = "catch the specific exceptions this block expects, or drop the try if nothing can fail"
                    replacement = emptyList()
                }
                mustHandle.isNotEmpty() -> {
                    replacement = mostGeneral(mustHandle)
                    suggestion = "catch ${names(replacement)}" +
                        (if (unchecked.isNotEmpty()) " and let ${names(mostGeneral(unchecked))} (unchecked) propagate unless you can recover from them" else "") +
                        ", or translate to a domain exception"
                }
                else -> {
                    replacement = mostGeneral(unchecked)
                    suggestion = "only unchecked exceptions are reachable (${names(replacement)}): let them propagate, " +
                        "or catch just the ones you can recover from"
                }
            }
            return Advice(
                suggestion + adviceNote, handled, replacement.takeIf { it.isNotEmpty() }?.let { rewrite(type, it) },
            )
        }

        // A catch that can drop a broad exception on the floor is the most severe defect: report it first.
        val broadType = caught.firstOrNull { it in BROAD }
        val swallowsBroad = broadType != null && !seam.alwaysThrows
        if (seam.bodyKind == CatchBodyKind.EMPTY || swallowsBroad) {
            val advice = broadType?.let { adviceFor(it) }
            val base = "rethrow (or wrap and throw) on every path, or catch only the specific exceptions you can recover from"
            issue(
                IssueKind.SWALLOWED_EXCEPTION, Severity.CRITICAL,
                if (seam.bodyKind == CatchBodyKind.EMPTY) "${describe(seam, caught)} swallows the exception with an empty body"
                else "catch (${simple(broadType!!)} ${seam.variableName}) does not always rethrow: some paths swallow the exception",
                when {
                    advice == null -> "handle the exception, log it, or document why ignoring it is safe"
                    advice.suggestedCatch != null -> "rethrow on every path, or narrow the catch: ${advice.suggestion}"
                    else -> base
                },
                advice?.handled.orEmpty(),
                suggestedCatch = advice?.suggestedCatch,
            )
        }

        for (type in caught) {
            val handledEntries = reachable.filter { index.isSubtype(it.type, type) }
            val handled = handledEntries.map { it.type }
            val advised = handledEntries.mapNotNull { it.handledBy }.toSet()
            val adviceNote = if (advised.isNotEmpty() && handledEntries.all { it.handledBy != null }) {
                "; these are already handled by ${advised.joinToString()} - consider letting them propagate"
            } else ""
            when {
                // A broad catch that can drop the exception is already reported once, as SWALLOWED_EXCEPTION;
                // BROAD_CATCH is for broad catches that do throw on every path.
                type in BROAD && !seam.alwaysThrows -> Unit
                // Cleanup, then `throw e;`: the caller sees the original exception, so nothing is hidden or widened.
                type in BROAD && seam.onlyRethrows -> Unit
                type in BROAD -> {
                    // Catch-and-rethrow or translate is a common, intentional boundary pattern.
                    val translating = seam.bodyKind == CatchBodyKind.RETHROW || seam.bodyKind == CatchBodyKind.WRAP
                    val advice = adviceFor(type)
                    // A RuntimeException catch that always throws and has no known exception behind it is the usual
                    // way to turn a library's undeclared unchecked failures into a domain error: not worth HIGH.
                    val boundary = type == RUNTIME_EXCEPTION && advice.handled.isEmpty()
                    val lostCause = if (seam.bodyKind == CatchBodyKind.THROW_NEW) {
                        "pass the caught exception as the cause of the exception you throw, or its stack trace is lost; "
                    } else ""
                    issue(
                        IssueKind.BROAD_CATCH, if (translating || boundary) Severity.MEDIUM else Severity.HIGH,
                        "catch (${simple(type)} ${seam.variableName}) is overly broad",
                        lostCause + advice.suggestion, advice.handled,
                        suggestedCatch = advice.suggestedCatch,
                    )
                }
                // Rollback or release, then `throw e;`: the caller gets the exception unchanged, so a wider catch
                // than strictly needed costs nothing.
                handled.isNotEmpty() && type !in handled && !seam.onlyRethrows -> {
                    val narrower = mostGeneral(handled)
                    issue(
                        IssueKind.OVERLY_WIDE_CATCH, Severity.MEDIUM,
                        "catch (${simple(type)} ${seam.variableName}) is wider than needed",
                        "catch ${names(narrower)} instead$adviceNote",
                        handled,
                        suggestedCatch = rewrite(type, narrower),
                    )
                }
                handled.isEmpty() && index.isIndexed(type) &&
                    reachable.none { index.isSubtype(type, it.type) } ->
                    issue(
                        IssueKind.DEAD_CATCH, Severity.LOW,
                        "catch (${simple(type)} ${seam.variableName}) may be dead: nothing reachable throws it",
                        "remove the clause if it is unreachable (analysis is approximate: library calls are not seen)",
                        emptyList(),
                    )
            }
        }
        return issues
    }

    /** Drops any type that is a subtype of another in the list: a multi-catch may not repeat a hierarchy. */
    private fun mostGeneral(types: List<String>): List<String> {
        val distinct = types.distinct()
        return distinct.filter { t -> distinct.none { other -> other != t && index.isSubtype(t, other) } }
    }

    private fun describe(seam: Seam, caught: List<String>) =
        "catch (${caught.joinToString(" | ") { simple(it) }} ${seam.variableName})"

    private companion object {
        const val RUNTIME_EXCEPTION = "java.lang.RuntimeException"
        val BROAD = setOf("java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable")
        fun simple(type: String) = type.substringAfterLast('.')
        fun names(types: List<String>) = types.joinToString(" | ") { simple(it) }

        /** Source form that compiles without an import: `java.lang` types are bare, others fully qualified. */
        fun display(type: String) = type.removePrefix("java.lang.").takeIf { '.' !in it } ?: type
    }
}
