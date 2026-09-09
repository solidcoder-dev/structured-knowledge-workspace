import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.openapi.generator)
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

    testImplementation(libs.spring.boot.starter.test)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

val openApiSpec = layout.projectDirectory.file("api/openapi.yaml").asFile.absolutePath
val generatedOpenApiDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<ValidateTask>("openApiValidate") {
    inputSpec.set(openApiSpec)
}

tasks.named<GenerateTask>("openApiGenerate") {
    dependsOn("openApiValidate")
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
    doFirst {
        delete(generatedOpenApiDir.get().asFile)
    }
}

sourceSets {
    named("main") {
        java.srcDir(generatedOpenApiDir.map { it.dir("src/main/kotlin") })
    }
}

tasks.named("compileKotlin") {
    dependsOn("openApiGenerate")
}

tasks.named("check") {
    dependsOn("openApiValidate")
}
