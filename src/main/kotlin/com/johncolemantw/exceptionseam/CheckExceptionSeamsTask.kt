package com.johncolemantw.exceptionseam

import com.johncolemantw.exceptionseam.analysis.AnalysisConfig
import com.johncolemantw.exceptionseam.evaluation.AllowList
import com.johncolemantw.exceptionseam.evaluation.DefaultCatchQualityEvaluator
import com.johncolemantw.exceptionseam.model.AnalysisResult
import com.johncolemantw.exceptionseam.model.SeamResult
import com.johncolemantw.exceptionseam.model.Severity
import com.johncolemantw.exceptionseam.reporting.ConsoleReporter
import com.johncolemantw.exceptionseam.reporting.HtmlReporter
import com.johncolemantw.exceptionseam.reporting.JsonReporter
import com.johncolemantw.exceptionseam.reporting.MarkdownReporter
import com.johncolemantw.exceptionseam.reporting.SarifReporter
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.options.OptionValues
import org.gradle.api.tasks.Internal
import com.johncolemantw.exceptionseam.analysis.JavaSourceReachabilityEngine
import com.johncolemantw.exceptionseam.analysis.LibraryClasses
import com.johncolemantw.exceptionseam.analysis.SourceIndex
import com.johncolemantw.exceptionseam.analysis.SpringRules
import com.johncolemantw.exceptionseam.scanner.JavaParserSeamScanner
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import com.johncolemantw.exceptionseam.analysis.RuntimeData
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class CheckExceptionSeamsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classpath: ConfigurableFileCollection

    @get:Input abstract val failOnSeverity: Property<String>
    @get:Input abstract val maxCallDepth: Property<Int>
    @get:Input abstract val excludePackages: SetProperty<String>
    @get:Input abstract val springAware: Property<Boolean>
    @get:Input abstract val classpathAware: Property<Boolean>
    @get:Input abstract val reportFormats: SetProperty<String>
    @get:Input abstract val allowList: SetProperty<String>

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeDataFile: RegularFileProperty

    /** Report paths are shown relative to this directory (the root project). */
    @get:Internal
    abstract val baseDir: DirectoryProperty

    /** Command-line override of `exceptionSeam.reportFormats`: `--report-format=html --report-format=sarif` or `=html,sarif`. */
    @Option(
        option = "report-format",
        description = "Report format to write; repeat or comma-separate for several. Overrides exceptionSeam.reportFormats.",
    )
    fun setReportFormatsFromCommandLine(values: List<String>) {
        val formats = values.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val unknown = formats.filter { it !in SUPPORTED_FORMATS }
        if (unknown.isNotEmpty()) {
            throw InvalidUserDataException(
                "Unknown --report-format ${unknown.joinToString { "'$it'" }}; supported: ${SUPPORTED_FORMATS.joinToString()}",
            )
        }
        reportFormats.set(formats)
    }

    @OptionValues("report-format")
    fun getSupportedReportFormats(): List<String> = SUPPORTED_FORMATS

    @TaskAction
    fun check() {
        val threshold = try {
            Severity.parseThreshold(failOnSeverity.get())
        } catch (e: IllegalArgumentException) {
            throw GradleException("exceptionSeam.failOnSeverity: ${e.message}")
        }
        val formats = reportFormats.get().map { it.lowercase() }.toSet()
        val dir = reportDir.get().asFile
        dir.mkdirs()

        val base = baseDir.get().asFile
        val onProblem = { file: java.io.File, message: String ->
            logger.warn("Exception Seam: could not fully parse $file: $message")
        }
        val sources = sourceDirs.files
        val result = LibraryClasses(if (classpathAware.get()) classpath.files else emptyList()).use { libraries ->
            val index = SourceIndex.build(sources, base, libraries, onProblem)
            val seams = JavaParserSeamScanner(base).scan(index)
            val engine = JavaSourceReachabilityEngine(
                index,
                AnalysisConfig(maxCallDepth.get(), excludePackages.get(), classpathAware.get()),
                if (springAware.get()) SpringRules(index) else null,
            )
            val evaluator = DefaultCatchQualityEvaluator(index)

            val runtime = runtimeDataFile.orNull?.asFile?.let {
                try {
                    RuntimeData.load(it)
                } catch (e: IllegalArgumentException) {
                    throw GradleException("exceptionSeam.runtimeDataFile: ${e.message}")
                }
            }
            val allow = AllowList(allowList.get())
            AnalysisResult(seams.map { seam ->
                val static = engine.analyze(seam)
                val reachable = runtime?.merge(seam, static, index) ?: static
                SeamResult(seam, reachable.toList(), allow.apply(evaluator.evaluate(seam, reachable)))
            }).also {
                if (libraries.problems.isNotEmpty()) {
                    logger.warn(
                        "Exception Seam: the throws clauses of ${libraries.problems.size} library type(s) could not be read, " +
                            "so calls on them show no reachable exceptions (is the dependency on the main compile classpath?): " +
                            libraries.problems.entries.take(10).joinToString("; ") { (type, why) -> "$type ($why)" } +
                            if (libraries.problems.size > 10) "; ..." else "",
                    )
                }
            }
        }

        if ("console" in formats) ConsoleReporter { logger.lifecycle(it) }.report(result)
        if ("json" in formats) JsonReporter(dir.resolve("exception-seam.json")).report(result)
        if ("html" in formats) HtmlReporter(dir.resolve("index.html"), base).report(result)
        if ("sarif" in formats) SarifReporter(dir.resolve("exception-seam.sarif")).report(result)
        if ("md" in formats) MarkdownReporter(dir.resolve("exception-seam.md"), base).report(result)
        (formats - SUPPORTED_FORMATS.toSet()).forEach {
            logger.warn("Exception Seam: unknown report format '$it' was skipped")
        }

        val blocking = result.issues.filter { threshold != null && it.severity >= threshold }
        if (blocking.isNotEmpty()) {
            throw GradleException(
                "Exception Seam Analysis failed with ${blocking.size} issue(s) of severity $threshold or higher.\n" +
                    "See the full report at ${dir.resolve(if ("html" in formats) "index.html" else "exception-seam.json")}",
            )
        }
    }

    private companion object {
        val SUPPORTED_FORMATS = listOf("console", "json", "html", "sarif", "md")
    }
}
