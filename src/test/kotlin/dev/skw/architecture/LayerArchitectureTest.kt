package dev.skw.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

@AnalyzeClasses(packages = ["dev.skw"], importOptions = [DoNotIncludeTests::class])
class LayerArchitectureTest {
    @ArchTest
    val domainDoesNotAccessOuterLayers: ArchRule =
        noClassesRule()
            .that()
            .resideInAnyPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages("dev.skw.domain..", "java..", "kotlin..", "org.jetbrains.annotations..")
            .allowEmptyShould(true)

    @ArchTest
    val applicationDoesNotAccessAdapters: ArchRule =
        noClassesRule()
            .that()
            .resideInAnyPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideOutsideOfPackages(
                "dev.skw.application..",
                "dev.skw.domain..",
                "java..",
                "kotlin..",
                "org.jetbrains.annotations..",
            ).allowEmptyShould(true)

    @ArchTest
    val generatedDtosStayInRestAdapters: ArchRule =
        noClassesRule()
            .that()
            .resideInAnyPackage("..domain..", "..application..", "..adapter.outbound..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter.inbound.rest.generated..")
            .allowEmptyShould(true)

    @ArchTest
    val outboundAdaptersDoNotAccessInboundAdapters: ArchRule =
        noClassesRule()
            .that()
            .resideInAnyPackage("..adapter.outbound..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter.inbound..")
            .allowEmptyShould(true)

    @ArchTest
    val concreteRepositoriesLiveInOutboundAdapters: ArchRule =
        classes()
            .that()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .resideInAnyPackage("..adapter.outbound..")
            .orShould()
            .beInterfaces()
            .allowEmptyShould(true)

    @ArchTest
    val inboundAdaptersDoNotAccessOutboundAdapters: ArchRule =
        noClassesRule()
            .that()
            .resideInAnyPackage("..adapter.inbound..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter.outbound..")
            .allowEmptyShould(true)

    @ArchTest
    val domainDoesNotUseInfrastructure: ArchRule = noClassesOutsideDomain()

    @Test
    fun `defined package slices are free of cycles`() {
        val classes = ClassFileImporter().withImportOption(DoNotIncludeTests()).importPackages("dev.skw")
        slices()
            .matching("dev.skw.(*)..")
            .should()
            .beFreeOfCycles()
            .check(classes)
    }

    private fun noClassesOutsideDomain(): ArchRule =
        noClassesRule()
            .that()
            .resideInAnyPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "java.sql..",
                "javax.sql..",
                "jakarta.persistence..",
                "org.postgresql..",
                "..application..",
                "..adapter..",
            ).allowEmptyShould(true)

    private fun noClassesRule() =
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition
            .noClasses()
}
