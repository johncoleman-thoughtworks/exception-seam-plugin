package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.evaluation.AllowList
import com.johncolemantw.exceptionseam.evaluation.DefaultCatchQualityEvaluator
import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.SeamResult
import com.johncolemantw.exceptionseam.reporting.HtmlReporter
import com.johncolemantw.exceptionseam.reporting.MarkdownReporter
import com.johncolemantw.exceptionseam.reporting.SarifReporter
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReportingTest {
    @TempDir
    lateinit var project: File

    private fun analyse(allow: List<String> = emptyList()): AnalysisResult {
        val dir = File(project, "src/main/java/com/acme").apply { mkdirs() }
        File(dir, "Handler.java").writeText(
            """
            package com.acme;
            class Handler {
                void run() {
                    try { work(); } catch (Exception e) { System.out.println("<b>&"); throw new IllegalStateException(); }
                }
                void work() throws java.io.IOException { }
            }
            """.trimIndent(),
        )
        val roots = listOf(File(project, "src/main/java"))
        val index = SourceIndex.build(roots, project)
        val engine = JavaSourceReachabilityEngine(index, AnalysisConfig())
        val evaluator = DefaultCatchQualityEvaluator(index)
        val allowList = AllowList(allow)
        return AnalysisResult(JavaParserSeamScanner(project).scan(index).map { seam ->
            val reachable = engine.analyze(seam)
            SeamResult(seam, reachable.toList(), allowList.apply(evaluator.evaluate(seam, reachable)))
        })
    }

    private val handlerPath = "src/main/java/com/acme/Handler.java"

    @Test
    fun `allow-list globs on file and method`() {
        assertEquals(1, analyse().issues.size)
        listOf(
            "Handler.java",
            "**/Handler.java",
            "src/main/java/**/Handler.java#run",
            "src/**/com/acme/*.java#r*",
            "$handlerPath",
        ).forEach { pattern ->
            val r = analyse(listOf(pattern))
            assertTrue(r.issues.isEmpty(), pattern)
            assertEquals(pattern, r.allowListed.single().allowListedBy)
        }
        listOf("Other.java", "Handler.java#other", "src/main/*.java", "com/Handler.java").forEach { pattern ->
            assertEquals(1, analyse(listOf(pattern)).issues.size, pattern)
        }
    }

    @Test
    fun `html report escapes content and shows code context and reachable details`() {
        val out = File(project, "out/index.html")
        HtmlReporter(out, project).report(analyse())
        val html = out.readText()
        assertTrue(html.contains("Exception Seam Report"))
        assertTrue(html.contains("$handlerPath:4"))
        assertTrue(html.contains("System.out.println(&quot;&lt;b&gt;&amp;&quot;);"), "source line is escaped")
        assertFalse(html.contains("<b>&\""))
        assertTrue(html.contains("<code>java.io.IOException</code>"))
        assertTrue(html.contains("id=\"fSeverity\""))
        assertTrue(html.contains("class=\"ln hl\""))
    }

    @Test
    fun `html report lists allow-listed issues hidden by default`() {
        val out = File(project, "out/index.html")
        HtmlReporter(out, project).report(analyse(listOf("Handler.java")))
        val html = out.readText()
        assertTrue(html.contains("data-allow=\"true\""))
        assertTrue(html.contains("allow-listed by Handler.java"))
        assertTrue(html.contains("1 allow-listed"))
    }

    @Test
    fun `sarif report follows the 2_1_0 shape`() {
        val out = File(project, "out/report.sarif")
        SarifReporter(out).report(analyse(listOf("Nope.java")))
        val sarif = jacksonObjectMapper().readTree(out)
        assertEquals("2.1.0", sarif["version"].asText())
        val run = sarif["runs"][0]
        assertEquals("exception-seam", run["tool"]["driver"]["name"].asText())
        assertEquals(4, run["tool"]["driver"]["rules"].size())
        val result = run["results"][0]
        assertEquals("BROAD_CATCH", result["ruleId"].asText())
        assertEquals("error", result["level"].asText())
        val loc = result["locations"][0]["physicalLocation"]
        assertEquals(handlerPath, loc["artifactLocation"]["uri"].asText())
        assertEquals(4, loc["region"]["startLine"].asInt())
        assertEquals("java.io.IOException", result["properties"]["reachable"][0].asText())
        assertTrue(result["suppressions"] == null)
    }

    @Test
    fun `sarif marks allow-listed results as suppressed`() {
        val out = File(project, "out/report.sarif")
        SarifReporter(out).report(analyse(listOf("Handler.java")))
        val result = jacksonObjectMapper().readTree(out)["runs"][0]["results"][0]
        assertEquals("external", result["suppressions"][0]["kind"].asText())
    }

    @Test
    fun `markdown report has table, suggested catch, code context and reachable list`() {
        val out = File(project, "out/report.md")
        MarkdownReporter(out, project).report(analyse())
        val md = out.readText()
        assertTrue(md.startsWith("# Exception Seam Report"))
        assertTrue(md.contains("| HIGH | BROAD_CATCH | `$handlerPath:4` | catch (Exception e) is overly broad |"))
        assertTrue(md.contains("```java\ncatch (java.io.IOException e)\n```"))
        assertTrue(Regex("^> +4 +try \\{ work\\(\\); \\} catch \\(Exception e\\)", RegexOption.MULTILINE).containsMatchIn(md))
        assertTrue(md.contains("- `java.io.IOException` (declared_throws, checked: true, via com.acme.Handler#work())"))
    }

    @Test
    fun `markdown report lists allow-listed issues separately and handles no issues`() {
        val out = File(project, "out/report.md")
        MarkdownReporter(out, project).report(analyse(listOf("Handler.java")))
        val md = out.readText()
        assertTrue(md.contains("No issues found."))
        assertTrue(md.contains("## Allow-listed"))
        assertTrue(md.contains("(allow-listed by `Handler.java`)"))
    }
}
