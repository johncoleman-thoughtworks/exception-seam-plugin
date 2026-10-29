package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.LibraryClasses
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.evaluation.DefaultCatchQualityEvaluator
import com.johncolemantw.exceptionseam.model.ExceptionOrigin
import com.johncolemantw.exceptionseam.model.Issue
import com.johncolemantw.exceptionseam.model.IssueKind
import com.johncolemantw.exceptionseam.model.ReachableException
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.tools.ToolProvider

class LibraryThrowsTest {
    @TempDir
    lateinit var dir: File
    private lateinit var libClasses: File
    private val toClose = mutableListOf<LibraryClasses>()

    /** A stand-in for a third-party library such as Jackson: compiled to class files, no source in the project. */
    @BeforeEach
    fun compileLibrary() {
        val src = File(dir, "libsrc/lib").apply { mkdirs() }
        File(src, "ClientException.java").writeText("package lib; public class ClientException extends java.io.IOException { }")
        File(src, "Client.java").writeText(
            """
            package lib;
            public class Client {
                public String fetch(String key) throws ClientException { return key; }
                public String find(String a) throws ClientException { return a; }
                public String find(int a) throws java.io.IOException { return ""; }
                public String quiet(String key) { return key; }
                public Client(String url) throws ClientException { }
                public static String lookup(String k) throws ClientException { return k; }
            }
            """.trimIndent(),
        )
        libClasses = File(dir, "libout").apply { mkdirs() }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "tests need a JDK" }
        val status = compiler.run(null, null, null, "-d", libClasses.path, File(src, "Client.java").path, File(src, "ClientException.java").path)
        assertEquals(0, status)
    }

    @AfterEach
    fun close() = toClose.forEach { it.close() }

    private fun analyse(
        body: String,
        classpath: Boolean = true,
        config: AnalysisConfig = AnalysisConfig(),
    ): Pair<Set<ReachableException>, List<Issue>> {
        val srcRoot = File(dir, "src/com/acme").apply { mkdirs() }
        File(srcRoot, "A.java").writeText(
            """
            package com.acme;
            import lib.Client;
            class A {
                private final Client client = new Client("u");
                void run() throws Exception { $body }
            }
            """.trimIndent(),
        )
        val libs = LibraryClasses(if (classpath) listOf(libClasses) else emptyList()).also { toClose += it }
        val index = SourceIndex.build(listOf(File(dir, "src")), libraries = libs)
        val seam = JavaParserSeamScanner().scan(index).last()
        val reachable = JavaSourceReachabilityEngine(index, config).analyze(seam)
        return reachable to DefaultCatchQualityEvaluator(index).evaluate(seam, reachable)
    }

    @Test
    fun `declared exception of a classpath method is reachable`() {
        val (reachable, _) = analyse("try { client.fetch(\"k\"); } catch (Exception e) { throw e; }")
        val r = reachable.single()
        assertEquals("lib.ClientException", r.type)
        assertEquals(ExceptionOrigin.DECLARED_THROWS, r.origin)
        assertEquals(true, r.checked) // hierarchy read from the classpath: ClientException -> IOException
        assertEquals(listOf("Client#fetch"), r.callPath)
    }

    @Test
    fun `swallowing broad catch suggests the library exception`() {
        val (_, issues) = analyse("try { client.fetch(\"k\"); } catch (Exception exp) { System.out.println(exp); }")
        val swallowed = issues.single { it.kind == IssueKind.SWALLOWED_EXCEPTION }
        assertEquals("catch (lib.ClientException exp)", swallowed.suggestedCatch)
        assertEquals(listOf("lib.ClientException"), swallowed.reachable)
        assertTrue(issues.none { it.kind == IssueKind.BROAD_CATCH }, "a swallowing catch is not also reported as broad")
    }

    @Test
    fun `swallow finding without reachable exceptions has no suggested catch`() {
        val (_, issues) = analyse("try { client.quiet(\"k\"); } catch (Exception e) { System.out.println(e); }")
        assertNull(issues.single { it.kind == IssueKind.SWALLOWED_EXCEPTION }.suggestedCatch)
    }

    @Test
    fun `static calls and constructors are resolved`() {
        assertEquals(
            listOf("lib.ClientException"),
            analyse("try { Client.lookup(\"k\"); } catch (Exception e) { throw e; }").first.map { it.type },
        )
        assertEquals(
            listOf("java.io.FileNotFoundException"),
            analyse("try { new java.io.FileReader(\"x\"); } catch (Exception e) { throw e; }").first.map { it.type },
        )
        assertEquals(
            listOf("java.lang.InterruptedException"),
            analyse("try { Thread.sleep(1); } catch (Exception e) { throw e; }").first.map { it.type },
        )
    }

    @Test
    fun `overloads are chosen by argument type, and unknown arguments keep only what all declare`() {
        fun types(call: String) = analyse("try { $call } catch (Exception e) { throw e; }").first.map { it.type }
        assertEquals(listOf("lib.ClientException"), types("client.find(\"k\");"))
        assertEquals(listOf("java.io.IOException"), types("client.find(1);"))
        // String.valueOf(..) has no known type here: both overloads fit, and they share no exception.
        assertEquals(emptyList<String>(), types("client.find(String.valueOf(1));"))
    }

    @Test
    fun `an inner catch removes the library exception`() {
        val (reachable, _) = analyse(
            "try { try { client.fetch(\"k\"); } catch (java.io.IOException io) { System.out.println(io); } } catch (Exception e) { throw e; }",
        )
        assertTrue(reachable.isEmpty(), reachable.toString())
    }

    @Test
    fun `without the classpath the library class is unknown`() {
        val (reachable, _) = analyse("try { client.fetch(\"k\"); } catch (Exception e) { throw e; }", classpath = false)
        assertTrue(reachable.isEmpty())
    }

    @Test
    fun `a library type that cannot be read is recorded for the build log`() {
        val libs = LibraryClasses()
        assertNull(libs.methodThrows("com.missing.Thing", "go", listOf()))
        assertEquals(setOf("com.missing.Thing"), libs.problems.keys)
    }

    @Test
    fun `libraryThrows off restores source-only analysis`() {
        val (reachable, _) = analyse(
            "try { Thread.sleep(1); client.fetch(\"k\"); } catch (Exception e) { throw e; }",
            config = AnalysisConfig(libraryThrows = false),
        )
        assertTrue(reachable.isEmpty())
    }

    @Test
    fun `generic throws clauses are ignored`() {
        val (reachable, _) = analyse("try { java.util.Optional.of(1).orElseThrow(() -> new IllegalStateException()); } catch (Exception e) { throw e; }")
        assertTrue(reachable.none { it.type == "java.lang.Throwable" }, reachable.toString())
    }
}
