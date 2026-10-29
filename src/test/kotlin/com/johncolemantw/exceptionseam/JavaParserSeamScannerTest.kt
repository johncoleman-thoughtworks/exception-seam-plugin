package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.model.CatchBodyKind
import com.johncolemantw.exceptionseam.model.Seam
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JavaParserSeamScannerTest {
    @TempDir
    lateinit var root: File

    private fun scan(source: String, name: String = "Sample"): List<Seam> {
        File(root, "com/acme").mkdirs()
        File(root, "com/acme/$name.java").writeText("package com.acme;\n$source")
        return JavaParserSeamScanner().scan(listOf(root))
    }

    private fun body(catchBody: String, type: String = "Exception") = scan(
        """
        class Sample {
            void run() {
                try { work(); } catch ($type e) { $catchBody
                }
            }
            void work() {}
        }
        """.trimIndent(),
    ).single()

    private fun scanBodies(vararg catchBodies: String): List<Seam> = scan(
        "class Sample {\n void run() {\n" +
            catchBodies.joinToString("\n") { "try { work(); } catch (Exception e) { $it\n }" } +
            "\n }\n void work() {}\n}",
    )

    @Test
    fun `a comment starting with exception-seam safe after the catch opens turns the catch off`() {
        assertEquals(0, scanBodies("// exception-seam:safe").size)
        assertEquals(0, scanBodies("// exception-seam:safe - best effort, never fail the caller\n log.warn(\"x\", e);").size)
        assertEquals(0, scanBodies("log.warn(\"x\", e);\n // exception-seam:safe later in the block").size)
        assertEquals(0, scanBodies("/* exception-seam:safe */ log.warn(\"x\");").size)
        assertEquals(0, scanBodies("//exception-seam:SAFE").size)
    }

    @Test
    fun `only the marked catch is skipped`() {
        val seams = scanBodies("// exception-seam:safe - first", "handle(e);", "// exception-seam:safe - third")
        assertEquals(1, seams.size)
        assertEquals(CatchBodyKind.OTHER, seams.single().bodyKind)
    }

    @Test
    fun `comments that merely mention the marker, or sit elsewhere, do not count`() {
        assertEquals(1, scanBodies("// see exception-seam:safe in the docs\n handle(e);").size)
        assertEquals(1, scanBodies("// TODO exception-seam:safe\n handle(e);").size)
        assertEquals(1, scanBodies("// exception-seam:unsafe\n handle(e);").size)
        assertEquals(1, scan("""
            class Sample {
                void run() {
                    // exception-seam:safe
                    try { work(); } // exception-seam:safe
                    catch (Exception e) { handle(e); }
                }
                void work() {}
            }
        """.trimIndent()).size)
    }

    @Test
    fun `a marker inside a nested catch belongs to the nested catch only`() {
        val seams = scan("""
            class Sample {
                void run() {
                    try { work(); } catch (Exception outer) {
                        try { work(); } catch (RuntimeException inner) {
                            // exception-seam:safe - cleanup must not fail
                        }
                    }
                }
                void work() {}
            }
        """.trimIndent())
        assertEquals(listOf(listOf("Exception")), seams.map { it.caughtTypes })
    }

    @Test
    fun `simple catch with location and context`() {
        val seam = body("handle(e);")
        assertEquals("com/acme/Sample.java", seam.file)
        assertEquals(listOf("Exception"), seam.caughtTypes)
        assertEquals("e", seam.variableName)
        assertEquals("com.acme.Sample", seam.enclosingClass)
        assertEquals("run()", seam.enclosingMethod)
        assertEquals(4, seam.line)
        assertEquals(CatchBodyKind.OTHER, seam.bodyKind)
        assertFalse(seam.tryWithResources)
        assertEquals(0, seam.nestingDepth)
    }

    @Test
    fun `multi catch yields one seam with all types`() {
        val seam = body("handle(e);", "IOException | IllegalStateException")
        assertEquals(listOf("IOException", "IllegalStateException"), seam.caughtTypes)
        assertTrue(seam.isMultiCatch)
    }

    @Test
    fun `several catch clauses yield several seams`() {
        val seams = scan(
            """
            class Sample {
                void run() {
                    try { work(); }
                    catch (IllegalStateException e) { handle(e); }
                    catch (RuntimeException e) { throw new IllegalStateException(); }
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf(listOf("IllegalStateException"), listOf("RuntimeException")), seams.map { it.caughtTypes })
    }

    @Test
    fun `body classification`() {
        assertEquals(CatchBodyKind.EMPTY, body("// ignored").bodyKind)
        assertEquals(CatchBodyKind.LOG_ONLY, body("log.error(\"x\", e);").bodyKind)
        assertEquals(CatchBodyKind.LOG_ONLY, body("e.printStackTrace();").bodyKind)
        assertEquals(CatchBodyKind.LOG_ONLY, body("System.err.println(e);").bodyKind)
        assertEquals(CatchBodyKind.RETHROW, body("throw e;").bodyKind)
        assertEquals(CatchBodyKind.WRAP, body("throw new RuntimeException(\"x\", e);").bodyKind)
        assertEquals(CatchBodyKind.THROW_NEW, body("throw new RuntimeException(\"x\");").bodyKind)
        assertEquals(CatchBodyKind.OTHER, body("log.error(\"x\"); cleanup();").bodyKind)
    }

    @Test
    fun `nested try and try with resources`() {
        val seams = scan(
            """
            class Sample {
                void run() throws Exception {
                    try (java.io.StringReader r = new java.io.StringReader("")) {
                        try { r.read(); } catch (java.io.IOException inner) { }
                    } catch (Exception outer) { }
                }
            }
            """.trimIndent(),
        )
        val inner = seams.single { it.variableName == "inner" }
        val outer = seams.single { it.variableName == "outer" }
        assertEquals(1, inner.nestingDepth)
        assertEquals(0, outer.nestingDepth)
        assertTrue(outer.tryWithResources)
        assertFalse(inner.tryWithResources)
        assertEquals(listOf("java.io.IOException"), inner.caughtTypes)
    }

    @Test
    fun `try with only finally has no seam`() {
        val seams = scan("class Sample { void run() { try { } finally { } } }")
        assertTrue(seams.isEmpty())
    }

    @Test
    fun `captures annotations, constructors, lambdas and inner classes`() {
        val seams = scan(
            """
            @org.springframework.stereotype.Service
            class Sample {
                @Transactional
                void run() {
                    Runnable r = () -> { try { } catch (Exception e) { } };
                }
                Sample() { try { } catch (Throwable t) { } }
                static class Inner { void go() { try { } catch (RuntimeException x) { } } }
            }
            """.trimIndent(),
        )
        val lambda = seams.single { it.variableName == "e" }
        assertEquals(setOf("Transactional", "Service"), lambda.enclosingAnnotations)
        assertEquals("Sample()", seams.single { it.variableName == "t" }.enclosingMethod)
        assertEquals("com.acme.Sample.Inner", seams.single { it.variableName == "x" }.enclosingClass)
    }

    @Test
    fun `unparseable file is reported and skipped`() {
        File(root, "Bad.java").writeText("class { try {")
        val problems = mutableListOf<File>()
        val seams = JavaParserSeamScanner { f, _ -> problems += f }.scan(listOf(root))
        assertTrue(seams.isEmpty())
        assertEquals(listOf("Bad.java"), problems.map { it.name })
    }
}
