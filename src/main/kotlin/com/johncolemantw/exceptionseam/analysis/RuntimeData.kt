package com.johncolemantw.exceptionseam.analysis

import com.johncolemantw.exceptionseam.model.ExceptionOrigin
import com.johncolemantw.exceptionseam.model.ReachableException
import com.johncolemantw.exceptionseam.model.Seam
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** Exceptions observed at runtime per catch location, merged into the static results. */
class RuntimeData private constructor(private val entries: List<Entry>) {

    private class Entry(val file: String, val line: Int, val observed: Map<String, Long?>)

    /** Observed type -> count (`null` when the file gave no count) for the entries matching [seam]. */
    fun observationsFor(seam: Seam): Map<String, Long?> {
        val merged = LinkedHashMap<String, Long?>()
        entries.filter { matches(it, seam) }.forEach { e ->
            e.observed.forEach { (type, count) ->
                merged[type] = if (type in merged) (merged[type] ?: 0) + (count ?: 0) else count
            }
        }
        return merged
    }

    fun merge(seam: Seam, static: Set<ReachableException>, index: SourceIndex): Set<ReachableException> {
        val observed = observationsFor(seam)
        if (observed.isEmpty()) return static
        val result = LinkedHashSet<ReachableException>()
        static.forEach { result += if (it.type in observed) it.copy(observed = true) else it }
        val known = static.map { it.type }.toSet()
        observed.filterKeys { it !in known }.toSortedMap().forEach { (type, count) ->
            result += ReachableException(
                type = type,
                origin = ExceptionOrigin.RUNTIME_OBSERVED,
                checked = index.isChecked(type),
                file = seam.file,
                line = seam.line,
                callPath = emptyList(),
                reason = "observed at runtime" + (count?.let { " ($it time${if (it == 1L) "" else "s"})" } ?: ""),
                observed = true,
            )
        }
        return result
    }

    private fun matches(entry: Entry, seam: Seam): Boolean {
        val a = entry.file
        val b = seam.file
        val sameFile = a == b || a.endsWith("/$b") || b.endsWith("/$a")
        return sameFile && entry.line in seam.line..seam.endLine
    }

    companion object {
        fun load(file: File): RuntimeData {
            fun fail(message: String): Nothing = throw IllegalArgumentException("${file.path}: $message")
            val root = try {
                ObjectMapper().readTree(file)
            } catch (e: Exception) {
                fail("not valid JSON (${e.message?.lineSequence()?.first()})")
            }
            val observations = root?.get("observations")?.takeIf { it.isArray } ?: fail("missing 'observations' array")
            return RuntimeData(observations.mapIndexed { i, node -> entry(node, i, ::fail) })
        }

        private fun entry(node: JsonNode, i: Int, fail: (String) -> Nothing): Entry {
            val where = "observations[$i]"
            val file = node["file"]?.takeIf { it.isTextual }?.asText() ?: fail("$where needs a text 'file'")
            val line = node["line"]?.takeIf { it.isInt }?.asInt() ?: fail("$where needs an integer 'line'")
            val exceptions = node["exceptions"]?.takeIf { it.isArray } ?: fail("$where needs an 'exceptions' array")
            val observed = LinkedHashMap<String, Long?>()
            exceptions.forEach { e ->
                when {
                    e.isTextual -> if (e.asText() !in observed) observed[e.asText()] = null
                    e.isObject && e["type"]?.isTextual == true ->
                        observed[e["type"].asText()] = e["count"]?.takeIf { it.isIntegralNumber }?.asLong()
                    else -> fail("$where has an exception entry that is neither a string nor {\"type\": ...}")
                }
            }
            return Entry(file.replace('\\', '/'), line, observed)
        }
    }
}
