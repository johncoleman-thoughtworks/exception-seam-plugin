package com.johncolemantw.exceptionseam

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPluginExtension

class ExceptionSeamPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("exceptionSeam", ExceptionSeamExtension::class.java).also {
            it.failOnSeverity.convention("HIGH")
            it.includeTests.convention(false)
            it.maxCallDepth.convention(5)
            it.excludePackages.convention(listOf("java.", "javax.", "jakarta.", "org.springframework"))
            it.springAware.convention(true)
            it.classpathAware.convention(true)
            it.reportFormats.convention(listOf("console", "json"))
        }

        val task = project.tasks.register("checkExceptionSeams", CheckExceptionSeamsTask::class.java) {
            it.group = JavaBasePlugin.VERIFICATION_GROUP
            it.description = "Analyses catch seams for overly broad or incorrect exception handling."
            it.failOnSeverity.set(ext.failOnSeverity)
            it.maxCallDepth.set(ext.maxCallDepth)
            it.excludePackages.set(ext.excludePackages)
            it.springAware.set(ext.springAware)
            it.classpathAware.set(ext.classpathAware)
            it.reportFormats.set(ext.reportFormats)
            it.allowList.set(ext.allowList)
            it.runtimeDataFile.set(ext.runtimeDataFile)
            // Root-relative paths keep SARIF locations correct in multi-module builds.
            it.baseDir.set(project.rootProject.layout.projectDirectory)
            it.reportDir.set(project.layout.buildDirectory.dir("reports/exception-seam"))
        }

        project.plugins.withId("java") {
            val sourceSets = project.extensions.getByType(JavaPluginExtension::class.java).sourceSets
            task.configure {
                it.sourceDirs.from(project.provider {
                    sourceSets.filter { it.name == "main" || ext.includeTests.get() }
                        .flatMap { it.allJava.srcDirs }
                })
                it.classpath.from(project.provider { sourceSets.getByName("main").compileClasspath })
            }
        }

        project.plugins.withType(org.gradle.language.base.plugins.LifecycleBasePlugin::class.java) {
            project.tasks.named("check") { it.dependsOn(task) }
        }
    }
}
