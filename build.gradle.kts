import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.openapi.generator)
    alias(libs.plugins.spotless)
    alias(libs.plugins.dependency.analysis)
}

group = "dev.skw"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

val openApiSpec =
    layout.projectDirectory
        .file("api/openapi.yaml")
        .asFile.absolutePath
val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<ValidateTask>("openApiValidate") {
    inputSpec.set(openApiSpec)
    inputs.files(fileTree("api") { include("**/*.yaml") })
}

tasks.named<GenerateTask>("openApiGenerate") {
    dependsOn("openApiValidate")
    inputs.files(fileTree("api") { include("**/*.yaml") })
    inputSpec.set(openApiSpec)
    generatorName.set("kotlin-spring")
    outputDir.set(generatedOpenApiDir.get().asFile.absolutePath)
    apiPackage.set("dev.skw.adapter.inbound.rest.generated.api")
    modelPackage.set("dev.skw.adapter.inbound.rest.generated.model")
    configOptions.set(
        mapOf(
            "library" to "spring-boot",
            "useSpringBoot3" to "true",
            "useJakartaEe" to "true",
            "interfaceOnly" to "true",
            "skipDefaultInterface" to "true",
            "useTags" to "true",
            "useSwaggerUI" to "false",
            "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "gradleBuildFile" to "false",
        ),
    )
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "",
        ),
    )
    typeMappings.set(mapOf("PropertyValue" to "JsonNode"))
    importMappings.set(mapOf("JsonNode" to "com.fasterxml.jackson.databind.JsonNode"))
    doFirst {
        delete(generatedOpenApiDir.get().asFile)
    }
}

sourceSets {
    named("main") {
        java.srcDir(generatedOpenApiDir.map { it.dir("src/main/kotlin") })
    }
    create("integrationTest") {
        java.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add("integrationTestImplementation", libs.testcontainers.postgresql)
}

val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs tests against real infrastructure such as PostgreSQL."
        group = "verification"
        testClassesDirs =
            sourceSets
                .named("integrationTest")
                .get()
                .output.classesDirs
        classpath = sourceSets.named("integrationTest").get().runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(tasks.test)
    }

tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}

tasks.matching { it.name == "explodeCodeSourceMain" }.configureEach {
    dependsOn("openApiGenerate")
}

tasks.named("check") {
    dependsOn("openApiValidate")
    dependsOn("checkLayerBoundaries")
    dependsOn("spotlessCheck")
    dependsOn("buildHealth")
    dependsOn(integrationTest)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("checkLayerBoundaries") {
    group = "verification"
    description = "Rejects imports that violate the domain and application boundaries."
    val sourceRoots =
        listOf(
            layout.projectDirectory.dir("src/main/kotlin/dev/skw/domain"),
            layout.projectDirectory.dir("src/main/kotlin/dev/skw/application"),
        )
    val prohibitedImports =
        mapOf(
            "domain" to
                listOf(
                    "org.springframework",
                    "java.sql",
                    "javax.sql",
                    "jakarta.persistence",
                    "org.postgresql",
                    "dev.skw.application",
                    "dev.skw.adapter",
                ),
            "application" to
                listOf(
                    "org.springframework",
                    "java.sql",
                    "javax.sql",
                    "jakarta.persistence",
                    "org.postgresql",
                    "dev.skw.adapter",
                    "dev.skw.adapter.inbound.rest.generated",
                ),
        )
    doLast {
        sourceRoots.filter { it.asFile.exists() }.forEach { root ->
            val layer = root.asFile.name
            val patterns = prohibitedImports.getValue(layer)
            root.asFile
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java") }
                .forEach { sourceFile ->
                    sourceFile.useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            val importedType = line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") }
                            if (importedType != null && patterns.any { importedType.startsWith(it) }) {
                                throw GradleException(
                                    "Forbidden $layer import in ${sourceFile.relativeTo(projectDir)}:${index + 1}: $importedType",
                                )
                            }
                        }
                    }
                }
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
