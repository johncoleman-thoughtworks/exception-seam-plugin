package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.analysis.SpringRules
import com.johncolemantw.exceptionseam.evaluation.DefaultCatchQualityEvaluator
import com.johncolemantw.exceptionseam.model.ExceptionOrigin
import com.johncolemantw.exceptionseam.model.IssueKind
import com.johncolemantw.exceptionseam.model.ReachableException
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SpringRulesTest {
    @TempDir
    lateinit var root: File

    private val dae = "org.springframework.dao.DataAccessException"
    private val tx = "org.springframework.transaction.TransactionException"

    private fun source(name: String, body: String) {
        File(root, "com/acme").mkdirs()
        File(root, "com/acme/$name.java").writeText("package com.acme;\n$body")
    }

    private fun reach(spring: Boolean = true, variable: String = "e"): Set<ReachableException> {
        val index = SourceIndex.build(listOf(root))
        val seam = JavaParserSeamScanner().scan(listOf(root)).single { it.variableName == variable }
        return JavaSourceReachabilityEngine(index, AnalysisConfig(), if (spring) SpringRules(index) else null).analyze(seam)
    }

    private fun caller(field: String, call: String, catchType: String = "Exception") = source("Caller", """
        class Caller {
            private $field;
            void run() { try { $call; } catch ($catchType e) { throw new IllegalStateException(); } }
        }
    """.trimIndent())

    @Test
    fun `spring data repository calls throw DataAccessException`() {
        source("OrderRepo", """
            import org.springframework.data.jpa.repository.JpaRepository;
            interface OrderRepo extends JpaRepository<Object, Long> { }
        """.trimIndent())
        caller("OrderRepo repo", "repo.findById(1L)")
        val r = reach().single()
        assertEquals(dae, r.type)
        assertEquals(ExceptionOrigin.FRAMEWORK_RULE, r.origin)
        assertEquals(false, r.checked)
        assertTrue(r.reason!!.contains("DataAccessException"))
        assertTrue(reach(spring = false).isEmpty())
    }

    @Test
    fun `jdbc template and repository beans`() {
        source("Jdbc", """
            import org.springframework.jdbc.core.JdbcTemplate;
            class Jdbc {
                private JdbcTemplate jdbc;
                void run() { try { jdbc.update("x"); } catch (Exception e) { throw new IllegalStateException(); } }
            }
        """.trimIndent())
        assertEquals(setOf(dae), reach().map { it.type }.toSet())
    }

    @Test
    fun `repository annotated bean`() {
        source("Dao", """
            @org.springframework.stereotype.Repository
            class Dao { void save() { } }
        """.trimIndent())
        caller("Dao dao", "dao.save()")
        assertEquals(setOf(dae), reach().map { it.type }.toSet())
    }

    @Test
    fun `transactional callee in another bean, not on self-invocation`() {
        source("Billing", """
            class Billing {
                @Transactional void charge() { }
            }
        """.trimIndent())
        caller("Billing billing", "billing.charge()")
        assertEquals(setOf(tx), reach().map { it.type }.toSet())

        source("Caller", """
            class Caller {
                void run() { try { charge(); } catch (Exception e) { throw new IllegalStateException(); } }
                @Transactional void charge() { }
            }
        """.trimIndent())
        assertTrue(reach().isEmpty())
    }

    @Test
    fun `class level transactional and validated`() {
        source("Svc", """
            @Transactional @Validated
            class Svc { void go(@Valid String s) { } }
        """.trimIndent())
        caller("Svc svc", "svc.go(\"x\")")
        assertEquals(setOf(tx, "jakarta.validation.ConstraintViolationException"), reach().map { it.type }.toSet())
    }

    @Test
    fun `spring hierarchy lets catch types relate to injected exceptions`() {
        source("OrderRepo", """
            import org.springframework.data.jpa.repository.JpaRepository;
            interface OrderRepo extends JpaRepository<Object, Long> { }
        """.trimIndent())
        caller("OrderRepo repo", "repo.findById(1L)", "RuntimeException")
        val index = SourceIndex.build(listOf(root))
        SpringRules(index)
        assertTrue(index.isSubtype(dae, "java.lang.RuntimeException"))
        assertTrue(index.isSubtype("org.springframework.dao.DuplicateKeyException", dae))
        assertEquals(false, index.isChecked(tx))
    }

    @Test
    fun `controller advice handlers mark exceptions, catch-all handlers do not`() {
        source("OrderNotFound", "class OrderNotFound extends RuntimeException { }")
        source("Other", "class Other extends RuntimeException { }")
        source("Advice", """
            @RestControllerAdvice
            class Advice {
                @ExceptionHandler(OrderNotFound.class) void notFound() { }
                @ExceptionHandler(Exception.class) void fallback() { }
            }
        """.trimIndent())
        source("Svc", "class Svc { void go() throws OrderNotFound, Other { } }")
        caller("Svc svc", "svc.go()")
        val byType = reach().associateBy { it.type }
        assertEquals("com.acme.Advice#notFound", byType.getValue("com.acme.OrderNotFound").handledBy)
        assertNull(byType.getValue("com.acme.Other").handledBy)

        val index = SourceIndex.build(listOf(root))
        val engine = JavaSourceReachabilityEngine(index, AnalysisConfig(), SpringRules(index))
        val seam = JavaParserSeamScanner().scan(listOf(root)).single()
        val issue = DefaultCatchQualityEvaluator(index).evaluate(seam, engine.analyze(seam)).single()
        assertEquals(IssueKind.BROAD_CATCH, issue.kind)
        assertTrue(!issue.suggestion.contains("already handled"), "Other is unhandled")
    }

    @Test
    fun `suggestion mentions advice when every handled exception is covered`() {
        source("OrderNotFound", "class OrderNotFound extends RuntimeException { }")
        source("Advice", """
            @ControllerAdvice
            class Advice {
                @ExceptionHandler void notFound(OrderNotFound ex) { }
            }
        """.trimIndent())
        source("Svc", "class Svc { void go() throws OrderNotFound { } }")
        caller("Svc svc", "svc.go()")
        val index = SourceIndex.build(listOf(root))
        val engine = JavaSourceReachabilityEngine(index, AnalysisConfig(), SpringRules(index))
        val seam = JavaParserSeamScanner().scan(listOf(root)).single()
        val issue = DefaultCatchQualityEvaluator(index).evaluate(seam, engine.analyze(seam)).single()
        assertTrue(issue.suggestion.contains("already handled by com.acme.Advice#notFound"))
    }
}
