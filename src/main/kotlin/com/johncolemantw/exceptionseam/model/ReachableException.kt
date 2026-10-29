package com.johncolemantw.exceptionseam.model

enum class ExceptionOrigin {
    /** An explicit `throw` statement. */
    THROW_STATEMENT,

    /** A `throws` clause on a resolved method or constructor. */
    DECLARED_THROWS,

    /** Added by a framework rule (for example Spring), not visible in the source. */
    FRAMEWORK_RULE,

    /** Only known from the `runtimeDataFile`; static analysis did not find it. */
    RUNTIME_OBSERVED,
}

data class ReachableException(
    /** Fully qualified where it could be resolved, otherwise as written in source. */
    val type: String,
    val origin: ExceptionOrigin,
    /** `true` = checked, `false` = unchecked, `null` = type hierarchy unknown. */
    val checked: Boolean?,
    /** Where the throw statement or `throws` declaration lives. */
    val file: String,
    val line: Int,
    /** Signatures of the methods walked through to get here (empty = directly in the try body). */
    val callPath: List<String>,
    /** Why a [ExceptionOrigin.FRAMEWORK_RULE] exception was added. */
    val reason: String? = null,
    /** `Owner#method` of an `@ExceptionHandler` that already handles this exception. */
    val handledBy: String? = null,
    /** Also seen at runtime according to the `runtimeDataFile`. */
    val observed: Boolean = false,
) {
    val depth: Int get() = callPath.size
}
