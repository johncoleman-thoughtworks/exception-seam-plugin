package com.johncolemantw.exceptionseam.model

/**
 * JDK exceptions that usually mean the code or its configuration is wrong, not that an external system failed.
 * Catching one broadly and carrying on hides the bug and can leave half-finished work behind, so reports call them out.
 * Informational only: it never changes a severity.
 */
object LikelyBugs {
    private val reasons: Map<String, String> = linkedMapOf(
        "java.lang.IllegalStateException" to "operation in the wrong state; earlier work may already have changed state",
        "java.util.ConcurrentModificationException" to "collection changed during iteration; behaviour is undefined",
        "java.lang.NullPointerException" to "null where a value was required",
        "java.lang.IllegalArgumentException" to "invalid argument from the caller",
        "java.lang.IndexOutOfBoundsException" to "bad index",
        "java.lang.ArrayIndexOutOfBoundsException" to "bad index",
        "java.lang.ClassCastException" to "wrong type",
        "java.lang.ArithmeticException" to "for example an integer divide by zero",
        "java.lang.UnsupportedOperationException" to "operation not supported by the object",
        "java.util.NoSuchElementException" to "iterator, stack or Optional misused",
        "java.lang.CloneNotSupportedException" to "clone() on a class that is not Cloneable",
        "java.lang.ClassNotFoundException" to "wrong class name, missing dependency or classpath problem",
        "java.lang.InstantiationException" to "reflection created an abstract class, interface or one with no usable constructor",
        "java.lang.IllegalAccessException" to "reflection reached an inaccessible member",
        "java.lang.NoSuchMethodException" to "wrong method name or signature in reflection",
        "java.lang.NoSuchFieldException" to "wrong field name in reflection",
        "java.lang.reflect.InvocationTargetException" to "wraps what a reflective call threw; the cause may itself be a bug",
        "java.io.UnsupportedEncodingException" to "invalid charset name; use StandardCharsets",
        "java.text.ParseException" to "bad input, or output your own code generated that its parser rejects",
    )

    /** Why [type] is usually a bug, or `null` if it is not one of them. Accepts a simple name when the type was not resolved. */
    fun reasonFor(type: String): String? =
        reasons[type] ?: if ('.' !in type) reasons.entries.firstOrNull { it.key.substringAfterLast('.') == type }?.value else null

    /** The members of [types] that are likely bugs, in their original order and without repeats. */
    fun among(types: Collection<String>): List<String> = types.filter { reasonFor(it) != null }.distinct()

    /** `IllegalStateException (reason), ...` for the likely bugs among [types]; empty when there are none. */
    fun describe(types: Collection<String>): String =
        among(types).joinToString(", ") { "${it.substringAfterLast('.')} (${reasonFor(it)})" }
}
