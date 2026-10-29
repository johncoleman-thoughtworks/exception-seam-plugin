package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.evaluation.DefaultCatchQualityEvaluator
import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.Issue
import com.johncolemantw.exceptionseam.model.IssueKind
import com.johncolemantw.exceptionseam.model.SeamResult
import com.johncolemantw.exceptionseam.model.Severity
import com.johncolemantw.exceptionseam.reporting.ConsoleReporter
import com.johncolemantw.exceptionseam.reporting.JsonReporter
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CatchQualityEvaluatorTest {
    @TempDir
    lateinit var root: File

    private fun analyse(vararg sources: Pair<String, String>): AnalysisResult {
        File(root, "com/acme").mkdirs()
        sources.forEach { (name, body) -> File(root, "com/acme/$name.java").writeText("package com.acme;\n$body") }
        val index = SourceIndex.build(listOf(root))
        val engine = JavaSourceReachabilityEngine(index, AnalysisConfig())
        val evaluator = DefaultCatchQualityEvaluator(index)
        return AnalysisResult(JavaParserSeamScanner().scan(listOf(root)).map { seam ->
            val reachable = engine.analyze(seam)
            SeamResult(seam, reachable.toList(), evaluator.evaluate(seam, reachable))
        })
    }

    private fun issues(catchType: String, throwsClause: String, catchBody: String = "throw new IllegalStateException();"): List<Issue> =
        analyse(
            "A" to """
                class A {
                    void run() {
                        try { work(); } catch ($catchType e) { $catchBody
                        }
                    }
                    void work() $throwsClause { }
                }
            """.trimIndent(),
        ).issues

    @Test
    fun `broad catches are HIGH and list what is reachable`() {
        listOf("Exception", "Throwable").forEach { type ->
            val i = issues(type, "throws java.io.IOException").filter { it.kind == IssueKind.BROAD_CATCH }
            assertEquals(1, i.size, type)
            assertEquals(Severity.HIGH, i.single().severity, type)
        }
        val broad = issues("Exception", "throws java.io.IOException").single()
        assertEquals(listOf("java.io.IOException"), broad.reachable)
    }

    @Test
    fun `RuntimeException catch only sees unchecked reachables`() {
        val i = issues("RuntimeException", "throws java.io.IOException").single()
        assertEquals(IssueKind.BROAD_CATCH, i.kind)
        assertTrue(i.reachable.isEmpty())
    }

    @Test
    fun `wrapping a broad catch is downgraded to MEDIUM`() {
        assertEquals(Severity.MEDIUM, issues("Exception", "", "throw new IllegalStateException(e);").single().severity)
    }

    @Test
    fun `a wide catch that only rolls back and rethrows is not reported`() {
        // catch (IOException) around a FileNotFoundException: wider than needed, but `throw e;` changes nothing.
        assertEquals(
            emptyList<Issue>(),
            issues("java.io.IOException", "throws java.io.FileNotFoundException", "connection.rollback();\n throw e;"),
        )
        // The same catch that changes the exception, or that can swallow it, is still reported.
        assertEquals(
            listOf(IssueKind.OVERLY_WIDE_CATCH),
            issues("java.io.IOException", "throws java.io.FileNotFoundException", "throw new IllegalStateException(e);").map { it.kind },
        )
        assertEquals(
            listOf(IssueKind.OVERLY_WIDE_CATCH),
            issues("java.io.IOException", "throws java.io.FileNotFoundException", "if (a) { throw e; } else { throw new IllegalStateException(e); }").map { it.kind },
        )
    }

    @Test
    fun `catch wider than the reachable subtypes is MEDIUM`() {
        val i = issues("java.io.IOException", "throws java.io.FileNotFoundException").single()
        assertEquals(IssueKind.OVERLY_WIDE_CATCH, i.kind)
        assertEquals(Severity.MEDIUM, i.severity)
        assertTrue(i.suggestion.contains("FileNotFoundException"))
    }

    @Test
    fun `exact catch is clean`() {
        assertTrue(issues("java.io.IOException", "throws java.io.IOException").isEmpty())
    }

    @Test
    fun `empty body is flagged as swallowed`() {
        val i = issues("java.io.IOException", "throws java.io.IOException", "")
        assertEquals(listOf(IssueKind.SWALLOWED_EXCEPTION), i.map { it.kind })
        assertEquals(Severity.CRITICAL, i.single().severity)
    }

    @Test
    fun `swallowing broad catch is reported once, as swallowed, not also as broad`() {
        val i = issues("java.lang.Exception", "throws java.io.IOException", "")
        assertEquals(listOf(IssueKind.SWALLOWED_EXCEPTION), i.map { it.kind })
        assertEquals(listOf(Severity.CRITICAL), i.map { it.severity })
        assertEquals("catch (java.io.IOException e)", i.single().suggestedCatch)
    }

    private fun swallowKinds(type: String, body: String) =
        issues(type, "throws java.io.IOException", body).filter { it.kind == IssueKind.SWALLOWED_EXCEPTION }

    @Test
    fun `broad catch that logs and returns is critical`() {
        val i = swallowKinds("Exception", "log.warn(\"failed: {}\", e.getMessage(), e); return;")
        assertEquals(listOf(Severity.CRITICAL), i.map { it.severity })
        assertTrue(i.single().message.contains("does not always rethrow"))
    }

    @Test
    fun `RuntimeException and Throwable that do not rethrow are critical`() {
        assertEquals(1, swallowKinds("RuntimeException", "log.warn(\"x\"); return;").size)
        assertEquals(1, swallowKinds("Throwable", "log.warn(\"x\"); return;").size)
    }

    @Test
    fun `conditional rethrow is still critical`() {
        assertEquals(1, swallowKinds("Exception", "if (e instanceof IllegalStateException) { throw e; } log.warn(\"x\");").size)
        assertEquals(1, swallowKinds("Exception", "if (retry) { throw e; }").size)
    }

    @Test
    fun `unconditional throw on every path is not swallowing`() {
        assertEquals(0, swallowKinds("Exception", "throw e;").size)
        assertEquals(0, swallowKinds("Exception", "log.warn(\"x\"); throw new IllegalStateException(e);").size)
        assertEquals(0, swallowKinds("Exception", "if (a) { throw e; } else { throw new IllegalStateException(e); }").size)
        assertEquals(0, swallowKinds("Exception", "try { cleanup(); } finally { throw new IllegalStateException(); }").size)
    }

    @Test
    fun `throw inside a lambda does not count`() {
        assertEquals(1, swallowKinds("Exception", "Runnable r = () -> { throw new IllegalStateException(); }; r.run();").size)
    }

    @Test
    fun `logging a specific exception is not critical`() {
        assertEquals(0, swallowKinds("java.io.IOException", "log.warn(\"x\"); return;").size)
    }

    @Test
    fun `broad catch is BROAD_CATCH only when it throws on every path`() {
        val wrapped = issues("Exception", "throws java.io.IOException", "throw new IllegalStateException(e);").single()
        assertEquals(Severity.MEDIUM, wrapped.severity)
        val conditional = issues("Exception", "throws java.io.IOException", "if (a) { throw e; }")
        assertEquals(listOf(IssueKind.SWALLOWED_EXCEPTION), conditional.map { it.kind })
        assertEquals(listOf(Severity.CRITICAL), conditional.map { it.severity })
        val newException = issues("Exception", "throws java.io.IOException", "throw new IllegalStateException();").single()
        assertEquals(listOf(IssueKind.BROAD_CATCH, Severity.HIGH), listOf(newException.kind, newException.severity))
    }

    @Test
    fun `RuntimeException boundary with nothing reachable is MEDIUM, not HIGH`() {
        val boundary = issues("RuntimeException", "throws java.io.IOException", "throw new IllegalStateException();").single()
        assertEquals(Severity.MEDIUM, boundary.severity)
        // Same body around an Exception catch, or with unchecked exceptions actually reachable, stays HIGH.
        assertEquals(Severity.HIGH, issues("Exception", "throws java.io.IOException").single().severity)
        val reachable = analyse("A" to """
            class A {
                void run() { try { work(); } catch (RuntimeException e) { throw new IllegalStateException(); } }
                void work() { throw new IllegalArgumentException(); }
            }
        """.trimIndent()).issues.single()
        assertEquals(Severity.HIGH, reachable.severity)
    }

    @Test
    fun `throwing a new exception without the cause is called out`() {
        val lost = issues("Exception", "throws java.io.IOException", "throw new IllegalStateException(\"bad\");").single()
        assertTrue(lost.suggestion.startsWith("pass the caught exception as the cause"), lost.suggestion)
        val kept = issues("Exception", "throws java.io.IOException", "throw new IllegalStateException(\"bad\", e);").single()
        assertTrue(!kept.suggestion.contains("as the cause"))
    }

    @Test
    fun `cleanup then rethrow of the same exception is not a broad catch`() {
        for (type in listOf("Exception", "RuntimeException", "Throwable")) {
            assertEquals(emptyList<Issue>(), issues(type, "throws java.io.IOException", "writer.discard();\n throw e;"), type)
            assertEquals(emptyList<Issue>(), issues(type, "throws java.io.IOException", "if (a) { cleanup(); throw e; } else { throw e; }"), type)
        }
    }

    @Test
    fun `rethrow that is conditional, mixed with a new exception, or in a lambda is still reported`() {
        // Not on every path: it can swallow.
        assertEquals(listOf(IssueKind.SWALLOWED_EXCEPTION), issues("Exception", "", "if (a) { throw e; }").map { it.kind })
        // Throws something other than the caught exception on one path: the exception is changed.
        assertEquals(
            listOf(IssueKind.BROAD_CATCH),
            issues("Exception", "", "if (a) { throw e; } else { throw new IllegalStateException(e); }").map { it.kind },
        )
        // A throw inside a lambda is not the block's own throw.
        assertEquals(
            listOf(IssueKind.SWALLOWED_EXCEPTION),
            issues("Exception", "", "Runnable r = () -> { throw e; };").map { it.kind },
        )
    }

    private fun deadCatches(vararg sources: Pair<String, String>) =
        analyse(*sources).results.flatMap { it.issues }.filter { it.kind == IssueKind.DEAD_CATCH }

    @Test
    fun `an exception built by a helper and thrown with throw helper is not dead`() {
        val dead = deadCatches("A" to """
            class A {
                void run() { try { check(); } catch (Rejection r) { System.out.println(r); } }
                void check() { throw reject("x"); }
                private Rejection reject(String m) { return new Rejection(m); }
                private static final class Rejection extends RuntimeException { Rejection(String m) { super(m); } }
            }
        """.trimIndent())
        assertEquals(emptyList<Issue>(), dead)
    }

    @Test
    fun `a helper that returns a base type keeps subclass catches alive, and a different type does not`() {
        val base = deadCatches("A" to """
            class A {
                void run() { try { check(); } catch (Rejection r) { System.out.println(r); } }
                void check() { throw build("x"); }
                static RuntimeException build(String m) { return new IllegalStateException(m); }
                static class Rejection extends IllegalStateException { }
            }
        """.trimIndent())
        assertEquals(emptyList<Issue>(), base)
        val different = deadCatches("A" to """
            class A {
                void run() { try { check(); } catch (Rejection r) { System.out.println(r); } }
                void check() { throw other("x"); }
                static Other other(String m) { return new Other(); }
                static class Other extends RuntimeException { }
                static class Rejection extends RuntimeException { }
            }
        """.trimIndent())
        assertEquals(1, different.size)
    }

    @Test
    fun `a nested exception inherited from an implemented interface resolves to the same type`() {
        val dead = deadCatches(
            "Gateway" to "interface Gateway { final class Rejected extends RuntimeException { } void send(); }",
            "Adapter" to "class Adapter implements Gateway { public void send() { throw new Rejected(); } }",
            "Caller" to """
                class Caller {
                    private final Adapter adapter = new Adapter();
                    void run() { try { adapter.send(); } catch (Gateway.Rejected r) { System.out.println(r); } }
                }
            """.trimIndent(),
        )
        assertEquals(emptyList<Issue>(), dead)
    }

    @Test
    fun `project exception nothing throws is a LOW dead catch`() {
        val result = analyse(
            "GoneException" to "class GoneException extends Exception { }",
            "A" to """
                class A {
                    void run() { try { work(); } catch (GoneException e) { handle(e); } }
                    void work() { }
                }
            """.trimIndent(),
        )
        val i = result.issues.single()
        assertEquals(IssueKind.DEAD_CATCH, i.kind)
        assertEquals(Severity.LOW, i.severity)
    }

    @Test
    fun `JDK exceptions are never flagged dead because library calls are invisible`() {
        assertTrue(issues("java.io.IOException", "").isEmpty())
    }

    @Test
    fun `reporters write console text and json`() {
        val result = analyse(
            "A" to """
                class A {
                    void run() { try { work(); } catch (Exception e) { throw new IllegalStateException(); } }
                    void work() throws java.sql.SQLException { }
                }
            """.trimIndent(),
        )
        val lines = mutableListOf<String>()
        ConsoleReporter { lines += it }.report(result)
        val text = lines.joinToString("\n")
        assertTrue(text.contains("com/acme/A.java:3"))
        assertTrue(text.contains("[HIGH] catch (Exception e) is overly broad"))
        assertTrue(text.contains("Reachable: SQLException"))

        val file = File(root, "out/report.json")
        JsonReporter(file).report(result)
        val json = jacksonObjectMapper().readTree(file)
        assertEquals(1, json["summary"]["bySeverity"]["HIGH"].asInt())
        assertEquals("BROAD_CATCH", json["issues"][0]["kind"].asText())
        assertEquals("java.sql.SQLException", json["seams"][0]["reachable"][0]["type"].asText())
    }

    // ---- suggested catch --------------------------------------------------------------------------

    private fun single(catchType: String, work: String): Issue =
        analyse(
            "A" to """
                class A {
                    void run() { try { work(); } catch ($catchType e) { throw new IllegalStateException(); } }
                    $work
                }
            """.trimIndent(),
        ).issues.single()

    @Test
    fun `wide catch suggests the narrower types as a valid multi-catch`() {
        val i = single("java.io.IOException", "void work() throws java.io.FileNotFoundException, java.io.EOFException { }")
        assertEquals("catch (java.io.FileNotFoundException | java.io.EOFException e)", i.suggestedCatch)
        assertEquals("catch FileNotFoundException | EOFException instead", i.suggestion)
    }

    @Test
    fun `related reachable types are reduced to the most general one`() {
        val i = single(
            "java.io.IOException",
            "void work() throws java.nio.charset.CharacterCodingException, java.nio.charset.MalformedInputException { }",
        )
        assertEquals("catch (java.nio.charset.CharacterCodingException e)", i.suggestedCatch)
        assertTrue(!i.suggestion.contains("MalformedInputException"))
    }

    @Test
    fun `broad catch suggests checked types and lets unchecked ones propagate`() {
        val i = single(
            "Exception",
            "void work() throws java.io.IOException { throw new IllegalStateException(); }",
        )
        assertEquals("catch (java.io.IOException e)", i.suggestedCatch)
        assertTrue(i.suggestion.contains("catch IOException"))
        assertTrue(i.suggestion.contains("let IllegalStateException (unchecked) propagate"))
    }

    @Test
    fun `broad catch with only unchecked exceptions suggests letting them propagate`() {
        val i = single("Exception", "void work() { throw new IllegalStateException(); }")
        assertTrue(i.suggestion.contains("only unchecked exceptions are reachable (IllegalStateException)"))
        assertEquals("catch (IllegalStateException e)", i.suggestedCatch)
    }

    @Test
    fun `unclassified library exceptions are treated as needing a decision`() {
        val i = single("Exception", "void work() throws com.lib.Unknown { }")
        assertEquals("catch (com.lib.Unknown e)", i.suggestedCatch)
    }

    @Test
    fun `no suggested catch when nothing is reachable`() {
        val i = single("Exception", "void work() { }")
        assertEquals(null, i.suggestedCatch)
        assertTrue(i.suggestion.contains("catch the specific exceptions this block expects"))
    }

    @Test
    fun `other alternatives of a multi-catch are kept`() {
        val i = single("Exception | Error", "void work() throws java.io.IOException { }")
        assertEquals("catch (java.io.IOException | Error e)", i.suggestedCatch)
    }

    @Test
    fun `reports carry the suggested catch`() {
        val result = analyse(
            "A" to """
                class A {
                    void run() { try { work(); } catch (Exception e) { throw new IllegalStateException(); } }
                    void work() throws java.sql.SQLException { }
                }
            """.trimIndent(),
        )
        val lines = mutableListOf<String>()
        ConsoleReporter { lines += it }.report(result)
        assertTrue(lines.joinToString("\n").contains("Suggested:  catch (java.sql.SQLException e)"))

        val html = File(root, "out/index.html").also { com.johncolemantw.exceptionseam.reporting.HtmlReporter(it, root).report(result) }.readText()
        assertTrue(html.contains("<code class=\"suggest\">catch (java.sql.SQLException e)</code>"))

        val json = File(root, "out/report.json").also { JsonReporter(it).report(result) }
        assertEquals("catch (java.sql.SQLException e)", jacksonObjectMapper().readTree(json)["issues"][0]["suggestedCatch"].asText())
    }

    @Test
    fun `reachable exceptions that usually mean a bug are listed as likely bugs`() {
        val result = analyse(
            "A" to """
                class A {
                    void run() { try { work(); } catch (Exception e) { throw new RuntimeException(e); } }
                    void work() throws IllegalStateException, java.io.IOException { }
                }
            """.trimIndent(),
        )
        val issue = result.issues.single()
        assertEquals(listOf("java.lang.IllegalStateException"), issue.likelyBugs)

        val lines = mutableListOf<String>()
        ConsoleReporter { lines += it }.report(result)
        assertTrue(lines.joinToString("\n").contains("Likely bugs: IllegalStateException ("))

        val md = File(root, "out/report.md").also { com.johncolemantw.exceptionseam.reporting.MarkdownReporter(it, root).report(result) }.readText()
        assertTrue(md.contains("**Likely bugs:** IllegalStateException ("))

        val json = File(root, "out/report.json").also { JsonReporter(it).report(result) }
        assertEquals("java.lang.IllegalStateException", jacksonObjectMapper().readTree(json)["issues"][0]["likelyBugs"][0].asText())
    }

    @Test
    fun `no likely bugs line when only external failures are reachable`() {
        val result = analyse(
            "A" to """
                class A {
                    void run() { try { work(); } catch (Exception e) { throw new RuntimeException(e); } }
                    void work() throws java.io.IOException { }
                }
            """.trimIndent(),
        )
        assertTrue(result.issues.single().likelyBugs.isEmpty())
        val lines = mutableListOf<String>()
        ConsoleReporter { lines += it }.report(result)
        assertTrue(!lines.joinToString("\n").contains("Likely bugs"))
    }
}
