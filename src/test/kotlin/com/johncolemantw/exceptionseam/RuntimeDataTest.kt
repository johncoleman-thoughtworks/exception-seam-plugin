package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.RuntimeData
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.model.ExceptionOrigin
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RuntimeDataTest {
    @TempDir
    lateinit var dir: File

    private val sourceRoot get() = File(dir, "src/main/java")

    private fun merge(json: String): Set<com.johncolemantw.exceptionseam.model.ReachableException> {
        File(sourceRoot, "com/acme").mkdirs()
        File(sourceRoot, "com/acme/A.java").writeText(
            """
            package com.acme;
            class A {
                void run() {
                    try { work(); } catch (Exception e) {
                        handle(e);
                    }
                }
                void work() throws java.io.IOException { }
            }
            """.trimIndent(),
        )
        val data = File(dir, "runtime.json").apply { writeText(json) }
        val index = SourceIndex.build(listOf(sourceRoot), dir)
        val seam = JavaParserSeamScanner(dir).scan(index).single()
        val static = JavaSourceReachabilityEngine(index, AnalysisConfig()).analyze(seam)
        return RuntimeData.load(data).merge(seam, static, index)
    }

    @Test
    fun `runtime-only exceptions are added and static ones are marked observed`() {
        val merged = merge(
            """{"observations":[{"file":"src/main/java/com/acme/A.java","line":4,
               "exceptions":["java.io.IOException",{"type":"java.sql.SQLException","count":3}]}]}""",
        ).associateBy { it.type }
        assertEquals(setOf("java.io.IOException", "java.sql.SQLException"), merged.keys)
        assertTrue(merged.getValue("java.io.IOException").observed)
        assertEquals(ExceptionOrigin.DECLARED_THROWS, merged.getValue("java.io.IOException").origin)
        val runtime = merged.getValue("java.sql.SQLException")
        assertEquals(ExceptionOrigin.RUNTIME_OBSERVED, runtime.origin)
        assertEquals("observed at runtime (3 times)", runtime.reason)
        assertEquals(true, runtime.checked)
    }

    @Test
    fun `any line of the catch clause matches, other lines and files do not`() {
        fun types(file: String, line: Int) = merge(
            """{"observations":[{"file":"$file","line":$line,"exceptions":["java.sql.SQLException"]}]}""",
        ).map { it.type }.toSet()
        assertTrue("java.sql.SQLException" in types("A.java", 5))
        assertTrue("java.sql.SQLException" in types("/abs/path/src/main/java/com/acme/A.java", 4))
        assertTrue("java.sql.SQLException" !in types("src/main/java/com/acme/A.java", 9))
        assertTrue("java.sql.SQLException" !in types("src/main/java/com/acme/B.java", 4))
    }

    @Test
    fun `malformed files give clear errors`() {
        fun load(json: String) = File(dir, "bad.json").apply { writeText(json) }.let(RuntimeData::load)
        assertTrue(assertThrows(IllegalArgumentException::class.java) { load("{nope") }.message!!.contains("not valid JSON"))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { load("{}") }.message!!.contains("'observations'"))
        assertTrue(
            assertThrows(IllegalArgumentException::class.java) {
                load("""{"observations":[{"file":"A.java","exceptions":[]}]}""")
            }.message!!.contains("observations[0] needs an integer 'line'"),
        )
    }
}
