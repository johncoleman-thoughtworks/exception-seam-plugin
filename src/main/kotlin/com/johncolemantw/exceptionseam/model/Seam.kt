package com.johncolemantw.exceptionseam.model

/** What a catch body does with the caught exception. */
enum class CatchBodyKind {
    /** No statements (comments only). */
    EMPTY,

    /** Only logging / printStackTrace calls. */
    LOG_ONLY,

    /** Throws the caught exception variable itself. */
    RETHROW,

    /** Throws a new exception that receives the caught exception as an argument. */
    WRAP,

    /** Throws a new exception without passing the cause along. */
    THROW_NEW,

    OTHER,
}

/** One catch clause. A multi-catch yields a single seam with several [caughtTypes]. */
data class Seam(
    val file: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val enclosingClass: String?,
    val enclosingMethod: String?,
    /** Types as written in source (simple or qualified). */
    val caughtTypes: List<String>,
    val variableName: String,
    val bodyKind: CatchBodyKind,
    /** Line range of the guarded `try` block, for reachability analysis. */
    val tryBodyStartLine: Int,
    val tryBodyStartColumn: Int,
    val tryBodyEndLine: Int,
    val tryWithResources: Boolean,
    /** Number of enclosing `try` blocks/catches within the same method (0 = top level). */
    val nestingDepth: Int,
    /** Simple names of annotations on the enclosing method and its classes. */
    val enclosingAnnotations: Set<String>,
    /**
     * True when every path through the catch body ends in a `throw` (so the exception cannot be swallowed).
     * Conservative: a throw that is conditional, or sits in a loop or switch, does not count.
     */
    val alwaysThrows: Boolean = false,
    /**
     * True when the block throws on every path and the only thing it ever throws is the caught exception itself
     * (cleanup, then `throw e;`). The exception reaches the caller unchanged.
     */
    val onlyRethrows: Boolean = false,
) {
    val isMultiCatch: Boolean get() = caughtTypes.size > 1
}
