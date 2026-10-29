import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
}

group = "io.github.johncoleman-thoughtworks"
// The release workflow passes the tag as -PreleaseVersion (v1.0.0 -> 1.0.0). The Plugin Portal rejects SNAPSHOT versions.
version = providers.gradleProperty("releaseVersion").getOrElse("1.0.0")

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation("com.github.javaparser:javaparser-core:3.28.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val functionalTest: SourceSet by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

gradlePlugin {
    website = providers.gradleProperty("pluginWebsite").getOrElse("https://github.com/johncoleman-thoughtworks/exception-seam-plugin")
    vcsUrl = providers.gradleProperty("pluginVcsUrl").getOrElse("https://github.com/johncoleman-thoughtworks/exception-seam-plugin.git")
    plugins {
        create("exceptionSeam") {
            id = "io.github.johncoleman-thoughtworks.exception-seam"
            implementationClass = "com.johncolemantw.exceptionseam.ExceptionSeamPlugin"
            displayName = "Exception Seam"
            description = "Finds overly broad or incorrect catch seams at build time."
            tags = listOf("exceptions", "static-analysis", "spring", "code-quality")
            compatibility {
                features {
                    configurationCache = true
                }
            }
        }
    }
    testSourceSets(functionalTest)
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs Gradle TestKit functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.test { useJUnitPlatform() }
tasks.check { dependsOn(functionalTestTask) }

// Internal repository: -PinternalRepoUrl=... [-PinternalRepoUser=... -PinternalRepoPassword=...]
// then ./gradlew publish. Credentials for the Plugin Portal come from gradle.publish.key/secret
// (./gradlew publishPlugins).
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            url = "https://github.com/johncoleman-thoughtworks/exception-seam-plugin"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    id = "johncolemantw"
                    name = "John Coleman"
                }
            }
            scm {
                connection = "scm:git:https://github.com/johncoleman-thoughtworks/exception-seam-plugin.git"
                developerConnection = "scm:git:ssh://git@github.com/johncoleman-thoughtworks/exception-seam-plugin.git"
                url = "https://github.com/johncoleman-thoughtworks/exception-seam-plugin"
            }
        }
    }
    repositories {
        providers.gradleProperty("internalRepoUrl").orNull?.let { repoUrl ->
            maven {
                name = "internal"
                url = uri(repoUrl)
                val user = providers.gradleProperty("internalRepoUser").orNull
                if (user != null) {
                    credentials {
                        username = user
                        password = providers.gradleProperty("internalRepoPassword").orNull
                    }
                }
            }
        }
    }
}
