package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.analysis.SpringRules
import com.johncolemantw.exceptionseam.evaluation.DefaultCatchQualityEvaluator
import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.CatchBodyKind
import com.johncolemantw.exceptionseam.model.SeamResult
import com.johncolemantw.exceptionseam.reporting.JsonReporter
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RobustnessTest {
    @TempDir
    lateinit var dir: File

    private val root get() = File(dir, "src/main/java")

    private fun write(path: String, body: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(body)
    }

    private fun analyse(): Pair<AnalysisResult, List<String>> {
        val problems = mutableListOf<String>()
        val index = SourceIndex.build(listOf(root), dir) { f, m -> problems += "${f.name}: $m" }
        val engine = JavaSourceReachabilityEngine(index, AnalysisConfig(), SpringRules(index))
        val evaluator = DefaultCatchQualityEvaluator(index)
        val result = AnalysisResult(JavaParserSeamScanner(dir).scan(index).map { seam ->
            val reachable = engine.analyze(seam)
            SeamResult(seam, reachable.toList(), evaluator.evaluate(seam, reachable))
        })
        return result to problems
    }

    private fun json(result: AnalysisResult): String {
        val out = File(dir, "out-${System.nanoTime()}.json")
        JsonReporter(out).report(result)
        return out.readText()
    }

    // ---- determinism ------------------------------------------------------------------------------

    private fun corpus() {
        write("com/acme/OrderRepo.java", """
            package com.acme;
            import org.springframework.data.jpa.repository.JpaRepository;
            public interface OrderRepo extends JpaRepository<Object, Long> { }
        """.trimIndent())
        write("com/acme/Gateway.java", "package com.acme;\npublic interface Gateway { void send() throws java.io.IOException; }")
        write("com/acme/Http.java", """
            package com.acme;
            public class Http implements Gateway {
                public void send() { throw new IllegalStateException(); }
            }
        """.trimIndent())
        write("com/acme/Advice.java", """
            package com.acme;
            @ControllerAdvice
            class Advice { @ExceptionHandler(IllegalStateException.class) void h() { } }
        """.trimIndent())
        write("com/acme/Service.java", """
            package com.acme;
            public class Service {
                private OrderRepo repo; private Gateway gateway;
                public void a() { try { repo.findAll(); gateway.send(); } catch (Exception e) { log.error("x", e); } }
                public void b() { try { gateway.send(); } catch (java.io.IOException e) { } catch (RuntimeException e) { throw e; } }
                public void c() { try { try { b(); } catch (Exception inner) { } } catch (Throwable t) { throw new RuntimeException(t); } }
            }
        """.trimIndent())
    }

    @Test
    fun `identical input gives byte-identical reports across repeated runs`() {
        corpus()
        val first = json(analyse().first)
        repeat(3) { assertEquals(first, json(analyse().first)) }
    }

    @Test
    fun `result does not depend on the order files were created`() {
        corpus()
        val expected = json(analyse().first)
        val files = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path to it.readText() }.toList()
        root.deleteRecursively()
        // Recreate in reverse path order so the directory creation order differs.
        files.sortedByDescending { it.first }.forEach { (p, b) -> write(p, b) }
        assertEquals(expected, json(analyse().first))
    }

    // ---- modern syntax ----------------------------------------------------------------------------

    @Test
    fun `modern java syntax parses and its seams are found`() {
        write("com/acme/Modern.java", """
            package com.acme;
            import java.util.*;
            public class Modern {
                sealed interface Shape permits Circle, Square { }
                record Circle(double r) implements Shape { }
                record Square(double s) implements Shape { }

                String describe(Object o) {
                    var text = ${"\"\"\""}
                        hello
                        ${"\"\"\""};
                    if (o instanceof String s && !s.isEmpty()) {
                        try { return s.substring(1); } catch (IndexOutOfBoundsException e) { return text; }
                    }
                    return switch (o) {
                        case Circle c -> "circle";
                        case Square q -> "square";
                        default -> "other";
                    };
                }

                void records() {
                    try { new Circle(1); } catch (RuntimeException e) { throw e; }
                }
            }
        """.trimIndent())
        val (result, problems) = analyse()
        assertEquals(emptyList<String>(), problems)
        assertEquals(listOf("IndexOutOfBoundsException", "RuntimeException"), result.results.map { it.seam.caughtTypes.single() })
    }

    @Test
    fun `unnamed catch parameter does not drop the file`() {
        write("com/acme/Unnamed.java", """
            package com.acme;
            class Unnamed {
                void run() {
                    try { work(); } catch (java.io.IOException _) { }
                }
                void work() throws java.io.IOException { }
            }
        """.trimIndent())
        val (result, problems) = analyse()
        assertEquals(emptyList<String>(), problems)
        val seam = result.results.single().seam
        assertEquals(listOf("java.io.IOException"), seam.caughtTypes)
        assertEquals(CatchBodyKind.EMPTY, seam.bodyKind)
    }

    // ---- scanner edge cases -----------------------------------------------------------------------

    @Test
    fun `final modifier, static initializer, enum, interface default and anonymous class`() {
        write("com/acme/Edges.java", """
            package com.acme;
            class Edges<T> {
                static { try { init(); } catch (final Exception e) { handle(e); } }
                static void init() { }
                enum Mode { A; void go() { try { init(); } catch (RuntimeException x) { throw new IllegalStateException(); } } }
                interface Port { default void use() { try { } catch (Error y) { handle(y); } } }
                Runnable r = new Runnable() { public void run() { try { } catch (Throwable z) { throw new IllegalStateException(); } } };
            }
        """.trimIndent())
        val (result, problems) = analyse()
        assertEquals(emptyList<String>(), problems)
        val byVar = result.results.associate { it.seam.variableName to it.seam }
        assertEquals(null, byVar.getValue("e").enclosingMethod)
        assertEquals("com.acme.Edges", byVar.getValue("e").enclosingClass)
        assertEquals("go()", byVar.getValue("x").enclosingMethod)
        assertEquals("com.acme.Edges.Mode", byVar.getValue("x").enclosingClass)
        assertEquals("use()", byVar.getValue("y").enclosingMethod)
        assertEquals("run()", byVar.getValue("z").enclosingMethod)
    }

    @Test
    fun `throwing a factory call is WRAP with the caught variable and THROW_NEW without`() {
        write("com/acme/Other.java", """
            package com.acme;
            class Other {
                void run() {
                    try { } catch (Exception e) { throw translate(e); }
                    try { } catch (Exception e) { throw refused("could not read"); }
                    try { } catch (Exception e) { throw this.translate(e, "x"); }
                    try { } catch (Exception e) { RuntimeException other = null; throw other; }
                }
                RuntimeException translate(Exception e) { return new RuntimeException(e); }
                RuntimeException translate(Exception e, String m) { return new RuntimeException(m, e); }
                RuntimeException refused(String m) { return new RuntimeException(m); }
            }
        """.trimIndent())
        assertEquals(
            listOf(CatchBodyKind.WRAP, CatchBodyKind.THROW_NEW, CatchBodyKind.WRAP, CatchBodyKind.OTHER),
            analyse().first.results.map { it.seam.bodyKind },
        )
    }

    @Test
    fun `empty source tree and files without try statements are fine`() {
        root.mkdirs()
        assertTrue(analyse().first.results.isEmpty())
        write("com/acme/Plain.java", "package com.acme;\nclass Plain { void run() { } }")
        assertTrue(analyse().first.results.isEmpty())
    }

    @Test
    fun `a broken file does not stop other files being analysed`() {
        write("com/acme/Broken.java", "package com.acme;\nclass Broken { void run() { try { } catch (")
        write("com/acme/Good.java", "package com.acme;\nclass Good { void run() { try { } catch (Exception e) { } } }")
        val (result, problems) = analyse()
        assertTrue(problems.any { it.startsWith("Broken.java") })
        assertEquals(listOf("src/main/java/com/acme/Good.java"), result.results.map { it.seam.file }.distinct())
    }
}
