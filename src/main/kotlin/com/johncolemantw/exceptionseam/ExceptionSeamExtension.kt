package com.johncolemantw.exceptionseam

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

abstract class ExceptionSeamExtension {
    /** "CRITICAL", "HIGH", "MEDIUM", "LOW" or "NONE". */
    abstract val failOnSeverity: Property<String>
    abstract val includeTests: Property<Boolean>
    abstract val maxCallDepth: Property<Int>
    abstract val excludePackages: SetProperty<String>
    abstract val springAware: Property<Boolean>

    /** Read `throws` clauses of JDK and classpath methods (for example Jackson or `java.nio`) that are not in the sources. */
    abstract val classpathAware: Property<Boolean>

    /** Any of "console", "html", "sarif", "json". */
    abstract val reportFormats: SetProperty<String>
    // Places where broad catches are intentional. Each entry is a glob on the file path relative to the root project
    // path, optionally followed by '#' and a glob on the method name. A pattern without a '/' matches
    // the file name only. Only broad-catch issues are suppressed; they still appear in the reports.
    // Examples: "GlobalHandler.java", "src/main/java/app/Main.java#main"
    abstract val allowList: SetProperty<String>

    // Optional JSON of exceptions observed at runtime, merged into the static results. Format:
    //   {"observations": [{"file": "src/main/java/app/A.java", "line": 87,
    //                      "exceptions": ["java.io.IOException", {"type": "app.Boom", "count": 3}]}]}
    // "file" matches the end of the seam's root-relative path; "line" is any line of the catch clause.
    abstract val runtimeDataFile: RegularFileProperty
}
