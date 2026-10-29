package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.model.ExceptionOrigin
import com.johncolemantw.exceptionseam.model.ReachableException
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReachabilityEngineTest {
    @TempDir
    lateinit var root: File

    private fun source(name: String, body: String) {
        File(root, "com/acme").mkdirs()
        File(root, "com/acme/$name.java").writeText("package com.acme;\n$body")
    }

    /** Reachable exceptions for the catch clause whose variable is [variable]. */
    private fun reach(
        variable: String = "e",
        config: AnalysisConfig = AnalysisConfig(),
    ): Set<ReachableException> {
        val seams = JavaParserSeamScanner().scan(listOf(root))
        val seam = seams.single { it.variableName == variable }
        return JavaSourceReachabilityEngine(SourceIndex.build(listOf(root)), config).analyze(seam)
    }

    private fun Set<ReachableException>.types() = map { it.type }.toSet()

    @Test
    fun `explicit throw in try body`() {
        source("A", """
            class A {
                void run() {
                    try { throw new IllegalStateException("x"); } catch (Exception e) { }
                }
            }
        """.trimIndent())
        val r = reach().single()
        assertEquals("java.lang.IllegalStateException", r.type)
        assertEquals(ExceptionOrigin.THROW_STATEMENT, r.origin)
        assertEquals(false, r.checked)
        assertEquals(0, r.depth)
    }

    @Test
    fun `declared throws of a resolved method, with source-defined exceptions`() {
        source("OrderException", "class OrderException extends Exception { }")
        source("A", """
            import java.io.IOException;
            class A {
                void run() {
                    try { load(); save(1); } catch (Exception e) { }
                }
                void load() throws IOException { }
                void save(int id) throws OrderException { }
            }
        """.trimIndent())
        val result = reach()
        assertEquals(setOf("java.io.IOException", "com.acme.OrderException"), result.types())
        assertTrue(result.all { it.origin == ExceptionOrigin.DECLARED_THROWS && it.checked == true })
    }

    @Test
    fun `arity is used to pick overloads`() {
        source("A", """
            class A {
                void run() { try { go(1, 2); } catch (Exception e) { } }
                void go(int a) throws java.io.IOException { }
                void go(int a, int b) throws java.sql.SQLException { }
            }
        """.trimIndent())
        assertEquals(setOf("java.sql.SQLException"), reach().types())
    }

    @Test
    fun `transitive through a field typed collaborator, bounded by depth`() {
        source("Repo", """
            class Repo { void save() { throw new IllegalArgumentException(); } }
        """.trimIndent())
        source("Service", """
            class Service {
                private final Repo repo = new Repo();
                void place() { repo.save(); }
            }
        """.trimIndent())
        source("A", """
            class A {
                private Service service;
                void run() { try { service.place(); } catch (Exception e) { } }
            }
        """.trimIndent())
        val deep = reach().single()
        assertEquals("java.lang.IllegalArgumentException", deep.type)
        assertEquals(listOf("com.acme.Service#place()", "com.acme.Repo#save()"), deep.callPath)
        assertTrue(reach(config = AnalysisConfig(maxCallDepth = 1)).isEmpty())
    }

    @Test
    fun `recursion terminates`() {
        source("A", """
            class A {
                void run() { try { ping(); } catch (Exception e) { } }
                void ping() { pong(); }
                void pong() throws java.io.IOException { ping(); }
            }
        """.trimIndent())
        assertEquals(setOf("java.io.IOException"), reach().types())
    }

    @Test
    fun `exception caught by a nested try does not escape`() {
        source("A", """
            class A {
                void run() {
                    try {
                        try { throw new IllegalStateException(); } catch (RuntimeException inner) { }
                        throw new java.io.IOException();
                    } catch (Exception e) { }
                }
            }
        """.trimIndent())
        assertEquals(setOf("java.io.IOException"), reach().types())
    }

    @Test
    fun `exceptions thrown from an inner catch block do escape`() {
        source("A", """
            class A {
                void run() {
                    try {
                        try { work(); } catch (RuntimeException inner) { throw new java.io.IOException(); }
                    } catch (Exception e) { }
                }
                void work() { }
            }
        """.trimIndent())
        assertEquals(setOf("java.io.IOException"), reach().types())
    }

    @Test
    fun `earlier catch clauses of the same try are subtracted`() {
        source("A", """
            class A {
                void run() {
                    try { load(); }
                    catch (java.io.IOException first) { }
                    catch (Exception e) { }
                }
                void load() throws java.io.IOException, java.sql.SQLException { }
            }
        """.trimIndent())
        assertEquals(setOf("java.sql.SQLException"), reach().types())
        assertEquals(setOf("java.io.IOException", "java.sql.SQLException"), reach("first").types())
    }

    @Test
    fun `lambda bodies are not entered`() {
        source("A", """
            class A {
                void run() {
                    try { Runnable r = () -> { throw new IllegalStateException(); }; } catch (Exception e) { }
                }
            }
        """.trimIndent())
        assertTrue(reach().isEmpty())
    }

    @Test
    fun `interface receiver includes implementations`() {
        source("Gateway", "interface Gateway { void send() throws java.io.IOException; }")
        source("HttpGateway", """
            class HttpGateway implements Gateway {
                public void send() { throw new IllegalStateException(); }
            }
        """.trimIndent())
        source("A", """
            class A {
                private Gateway gateway;
                void run() { try { gateway.send(); } catch (Exception e) { } }
            }
        """.trimIndent())
        assertEquals(setOf("java.io.IOException", "java.lang.IllegalStateException"), reach().types())
    }

    @Test
    fun `constructors and rethrown variables`() {
        source("Conn", """
            class Conn { Conn(String url) throws java.sql.SQLException { } }
        """.trimIndent())
        source("A", """
            class A {
                void run() {
                    try { new Conn("x"); } catch (Exception e) { }
                }
                void again() {
                    try {
                        try { run(); } catch (java.io.IOException cause) { throw cause; }
                    } catch (Exception outer) { }
                }
            }
        """.trimIndent())
        assertEquals(setOf("java.sql.SQLException"), reach().types())
        assertEquals(setOf("java.io.IOException"), reach("outer").types())
    }

    @Test
    fun `excluded packages are not entered`() {
        source("Repo", "class Repo { void save() throws java.io.IOException { } }")
        source("A", """
            class A {
                private Repo repo;
                void run() { try { repo.save(); } catch (Exception e) { } }
            }
        """.trimIndent())
        assertTrue(reach(config = AnalysisConfig(excludePackages = setOf("com.acme.Repo"))).isEmpty())
        assertEquals(1, reach().size)
    }

    @Test
    fun `third-party exceptions are caught by catch-alls but not by narrower catches`() {
        source("A", """
            class A {
                void run() {
                    try {
                        try { work(); } catch (java.io.IOException inner) { }
                    } catch (Exception e) { }
                }
                void work() throws com.lib.LibException { }
            }
        """.trimIndent())
        assertEquals(setOf("com.lib.LibException"), reach("e").types())
        assertEquals(setOf("com.lib.LibException"), reach("inner").types())
    }
}
