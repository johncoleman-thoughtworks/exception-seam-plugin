package com.johncolemantw.exceptionseam

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExceptionSeamPluginFunctionalTest {
    @TempDir
    lateinit var projectDir: File

    private val broadCatch = """
        package com.acme;
        class A {
            void run() { try { work(); } catch (Exception e) { throw new IllegalStateException(); } }
            void work() throws java.io.IOException { }
        }
    """.trimIndent()

    private val cleanCatch = broadCatch.replace("catch (Exception e)", "catch (java.io.IOException e)")

    private fun project(java: String, config: String = "") {
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"sample\"")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins {
                java
                id("io.github.johncoleman-thoughtworks.exception-seam")
            }
            $config
            """.trimIndent(),
        )
        File(projectDir, "src/main/java/com/acme").mkdirs()
        File(projectDir, "src/main/java/com/acme/A.java").writeText(java)
    }

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args)

    private fun run(vararg args: String): BuildResult = runner(*args).build()
    private fun runFailing(vararg args: String): BuildResult = runner(*args).buildAndFail()

    private val report get() = File(projectDir, "build/reports/exception-seam/exception-seam.json")

    @Test
    fun `build fails on a HIGH issue and reports it`() {
        project(broadCatch)
        val result = runFailing("checkExceptionSeams")
        assertEquals(TaskOutcome.FAILED, result.task(":checkExceptionSeams")?.outcome)
        assertTrue(result.output.contains("src/main/java/com/acme/A.java:3"))
        assertTrue(result.output.contains("[HIGH] catch (Exception e) is overly broad"))
        assertTrue(result.output.contains("Reachable: IOException"))
        assertTrue(result.output.contains("failed with 1 issue(s) of severity HIGH or higher"))
        assertEquals(1, jacksonObjectMapper().readTree(report)["summary"]["bySeverity"]["HIGH"].asInt())
    }

    private val sleepyCatch = """
        package com.acme;
        class A {
            void run() { try { Thread.sleep(1); } catch (Exception e) { System.out.println(e); } }
        }
    """.trimIndent()

    @Test
    fun `swallowed broad catch is critical and suggests the library exception`() {
        project(sleepyCatch)
        val result = runFailing("checkExceptionSeams")
        assertTrue(result.output.contains("[CRITICAL] catch (Exception e) does not always rethrow"), result.output)
        assertTrue(result.output.contains("Suggested:  catch (InterruptedException e)"), result.output)
    }

    @Test
    fun `classpathAware off ignores library throws clauses`() {
        project(sleepyCatch, "exceptionSeam { classpathAware.set(false) }")
        val result = runFailing("checkExceptionSeams")
        assertTrue(result.output.contains("[CRITICAL]"), result.output)
        assertTrue(!result.output.contains("InterruptedException"), result.output)
    }

    @Test
    fun `clean code passes and is up to date on second run`() {
        project(cleanCatch)
        assertEquals(TaskOutcome.SUCCESS, run("checkExceptionSeams").task(":checkExceptionSeams")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("checkExceptionSeams").task(":checkExceptionSeams")?.outcome)
    }

    @Test
    fun `failOnSeverity NONE reports without failing`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\") }")
        val result = run("checkExceptionSeams")
        assertTrue(result.output.contains("overly broad"))
        assertTrue(report.exists())
    }

    @Test
    fun `reportFormats limits the output`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\"); reportFormats.set(listOf(\"json\")) }")
        val result = run("checkExceptionSeams")
        assertTrue(!result.output.contains("overly broad"))
        assertTrue(report.exists())
    }

    @Test
    fun `check lifecycle task runs the analysis`() {
        project(broadCatch)
        assertEquals(TaskOutcome.FAILED, runFailing("check").task(":checkExceptionSeams")?.outcome)
    }

    @Test
    fun `invalid severity is a clear error`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"SEVERE\") }")
        assertTrue(runFailing("checkExceptionSeams").output.contains("Unknown severity 'SEVERE'"))
    }

    private fun springProject(config: String) {
        project(broadCatch, config)
        val dir = File(projectDir, "src/main/java/com/acme")
        File(dir, "OrderRepo.java").writeText(
            """
            package com.acme;
            import org.springframework.data.jpa.repository.JpaRepository;
            interface OrderRepo extends JpaRepository<Object, Long> { }
            """.trimIndent(),
        )
        File(dir, "A.java").writeText(
            """
            package com.acme;
            class A {
                private OrderRepo repo;
                void run() { try { repo.findById(1L); } catch (Exception e) { throw new IllegalStateException(); } }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `springAware adds framework exceptions and can be switched off`() {
        springProject("exceptionSeam { failOnSeverity.set(\"NONE\") }")
        assertTrue(run("checkExceptionSeams").output.contains("Reachable: DataAccessException"))

        springProject("exceptionSeam { failOnSeverity.set(\"NONE\"); springAware.set(false) }")
        assertTrue(!run("checkExceptionSeams").output.contains("DataAccessException"))
    }

    @Test
    fun `html and sarif reports are written`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\"); reportFormats.set(listOf(\"html\", \"sarif\")) }")
        run("checkExceptionSeams")
        val dir = File(projectDir, "build/reports/exception-seam")
        assertTrue(File(dir, "index.html").readText().contains("src/main/java/com/acme/A.java:3"))
        assertEquals("2.1.0", jacksonObjectMapper().readTree(File(dir, "exception-seam.sarif"))["version"].asText())
    }

    @Test
    fun `allow-listed broad catch does not fail the build`() {
        project(broadCatch, "exceptionSeam { allowList.add(\"A.java#run\") }")
        val result = run("checkExceptionSeams")
        assertTrue(result.output.contains("1 allow-listed"))
        assertEquals(0, jacksonObjectMapper().readTree(report)["summary"]["issues"].asInt())
        assertEquals(1, jacksonObjectMapper().readTree(report)["summary"]["allowListed"].asInt())
    }

    @Test
    fun `failure message points at the html report when enabled`() {
        project(broadCatch, "exceptionSeam { reportFormats.set(listOf(\"html\")) }")
        assertTrue(runFailing("checkExceptionSeams").output.contains("index.html"))
    }

    @Test
    fun `runtime data is merged into the report`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\"); runtimeDataFile.set(file(\"runtime.json\")) }")
        File(projectDir, "runtime.json").writeText(
            """{"observations":[{"file":"src/main/java/com/acme/A.java","line":3,
               "exceptions":[{"type":"java.sql.SQLException","count":2}]}]}""",
        )
        val result = run("checkExceptionSeams")
        assertTrue(result.output.contains("Reachable: IOException, SQLException"))
        val seams = jacksonObjectMapper().readTree(report)["seams"][0]["reachable"]
        assertEquals("RUNTIME_OBSERVED", seams.first { it["type"].asText() == "java.sql.SQLException" }["origin"].asText())
    }

    @Test
    fun `runtime data changes invalidate the task`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\"); runtimeDataFile.set(file(\"runtime.json\")) }")
        val data = File(projectDir, "runtime.json")
        data.writeText("""{"observations":[]}""")
        assertEquals(TaskOutcome.SUCCESS, run("checkExceptionSeams").task(":checkExceptionSeams")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("checkExceptionSeams").task(":checkExceptionSeams")?.outcome)
        data.writeText("""{"observations":[{"file":"A.java","line":3,"exceptions":["java.sql.SQLException"]}]}""")
        assertEquals(TaskOutcome.SUCCESS, run("checkExceptionSeams").task(":checkExceptionSeams")?.outcome)
    }

    @Test
    fun `bad or missing runtime data fails clearly`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\"); runtimeDataFile.set(file(\"runtime.json\")) }")
        assertTrue(runFailing("checkExceptionSeams").output.contains("runtimeDataFile"))
        File(projectDir, "runtime.json").writeText("{oops")
        assertTrue(runFailing("checkExceptionSeams").output.contains("exceptionSeam.runtimeDataFile"))
    }

    @Test
    fun `multi-module build reports per module and fails only for the bad one`() {
        File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"multi\"\ninclude(\"core\", \"web\")")
        File(projectDir, "build.gradle.kts").writeText(
            """
            plugins { id("io.github.johncoleman-thoughtworks.exception-seam") apply false }
            subprojects {
                apply(plugin = "java")
                apply(plugin = "io.github.johncoleman-thoughtworks.exception-seam")
            }
            """.trimIndent(),
        )
        File(projectDir, "core/src/main/java/com/acme").mkdirs()
        File(projectDir, "core/src/main/java/com/acme/A.java").writeText(broadCatch)
        File(projectDir, "web/src/main/java/com/acme").mkdirs()
        File(projectDir, "web/src/main/java/com/acme/A.java").writeText(cleanCatch)

        val result = runner("check", "--continue").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":core:checkExceptionSeams")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":web:checkExceptionSeams")?.outcome)
        assertTrue(result.output.contains("core/src/main/java/com/acme/A.java:3"))
        assertTrue(File(projectDir, "core/build/reports/exception-seam/exception-seam.json").exists())
        assertTrue(File(projectDir, "web/build/reports/exception-seam/exception-seam.json").exists())
    }

    private val testSource = """
        package com.acme;
        class ATest {
            void run() { try { } catch (Exception e) { throw new IllegalStateException(); } }
        }
    """.trimIndent()

    @Test
    fun `includeTests controls whether test sources are analysed`() {
        project(cleanCatch)
        File(projectDir, "src/test/java/com/acme").mkdirs()
        File(projectDir, "src/test/java/com/acme/ATest.java").writeText(testSource)
        assertEquals(TaskOutcome.SUCCESS, run("checkExceptionSeams").task(":checkExceptionSeams")?.outcome)

        project(cleanCatch, "exceptionSeam { includeTests.set(true) }")
        val result = runFailing("checkExceptionSeams")
        assertTrue(result.output.contains("src/test/java/com/acme/ATest.java"))
    }

    @Test
    fun `failOnSeverity MEDIUM fails on wide catches that HIGH tolerates`() {
        val wide = """
            package com.acme;
            class A {
                void run() { try { work(); } catch (java.io.IOException e) { System.out.println(e); } }
                void work() throws java.io.FileNotFoundException { }
            }
        """.trimIndent()
        project(wide)
        assertEquals(TaskOutcome.SUCCESS, run("checkExceptionSeams").task(":checkExceptionSeams")?.outcome)
        project(wide, "exceptionSeam { failOnSeverity.set(\"MEDIUM\") }")
        assertTrue(runFailing("checkExceptionSeams").output.contains("is wider than needed"))
    }

    @Test
    fun `maxCallDepth limits how far exceptions are followed`() {
        val deep = """
            package com.acme;
            class A {
                void run() { try { a(); } catch (Exception e) { throw new IllegalStateException(); } }
                void a() { b(); }
                void b() throws java.io.IOException { }
            }
        """.trimIndent()
        project(deep, "exceptionSeam { failOnSeverity.set(\"NONE\"); maxCallDepth.set(5) }")
        assertTrue(run("checkExceptionSeams").output.contains("Reachable: IOException"))
        project(deep, "exceptionSeam { failOnSeverity.set(\"NONE\"); maxCallDepth.set(0) }")
        assertTrue(!run("checkExceptionSeams").output.contains("Reachable: IOException"))
    }

    @Test
    fun `configuration cache is reused`() {
        project(cleanCatch)
        run("checkExceptionSeams", "--configuration-cache")
        assertTrue(run("checkExceptionSeams", "--configuration-cache").output.contains("Reusing configuration cache"))
    }

    private val reportDir get() = File(projectDir, "build/reports/exception-seam")

    @Test
    fun `report-format option overrides the configured formats and can be repeated`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\"); reportFormats.set(listOf(\"json\")) }")
        val result = run("checkExceptionSeams", "--report-format=html", "--report-format=sarif", "--report-format=md")
        assertTrue(File(reportDir, "exception-seam.md").readText().contains("catch (Exception e) is overly broad"))
        assertTrue(File(reportDir, "index.html").exists())
        assertTrue(File(reportDir, "exception-seam.sarif").exists())
        assertTrue(!File(reportDir, "exception-seam.json").exists(), "configured json format was overridden")
        assertTrue(!result.output.contains("overly broad"), "console was not requested")
    }

    @Test
    fun `report-format accepts a comma separated list`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\") }")
        val result = run("checkExceptionSeams", "--report-format=console,html,sarif")
        assertTrue(result.output.contains("overly broad"))
        assertTrue(File(reportDir, "index.html").exists())
        assertTrue(File(reportDir, "exception-seam.sarif").exists())
        assertTrue(!File(reportDir, "exception-seam.json").exists())
    }

    @Test
    fun `report-format rejects unknown formats and changing it reruns the task`() {
        project(broadCatch, "exceptionSeam { failOnSeverity.set(\"NONE\") }")
        val bad = runFailing("checkExceptionSeams", "--report-format=pdf")
        assertTrue(bad.output.contains("Unknown --report-format 'pdf'; supported: console, json, html, sarif, md"))

        assertEquals(TaskOutcome.SUCCESS, run("checkExceptionSeams", "--report-format=json").task(":checkExceptionSeams")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, run("checkExceptionSeams", "--report-format=json").task(":checkExceptionSeams")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, run("checkExceptionSeams", "--report-format=json,html").task(":checkExceptionSeams")?.outcome)
    }
}
