package dev.mulcor.harness;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Module layering (ARCHITECTURE_ROADMAP §2.4, §6). The simulation core, memory primitives and registry tables are
 * hot-path code: they must not reach protocol libraries (Minestom's object model allocates freely), Netty, or JSON.
 * Lower layers never depend on higher ones.
 */
class LayeringTest {
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.mulcor");
    }

    @Test
    void hotModulesDoNotUseProtocolOrJsonLibraries() {
        noClasses().that().resideInAnyPackage("dev.mulcor.core..", "dev.mulcor.memory..", "dev.mulcor.registry..")
                .should().dependOnClassesThat().resideInAnyPackage("net.minestom..", "io.netty..", "com.google.gson..",
                        "net.kyori..")
                .check(classes);
    }

    @Test
    void memoryAndRegistryAreLeaves() {
        noClasses().that().resideInAPackage("dev.mulcor.memory..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.mulcor.core..", "dev.mulcor.net..",
                        "dev.mulcor.harness..", "dev.mulcor.registry..")
                .check(classes);
        noClasses().that().resideInAPackage("dev.mulcor.registry..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.mulcor.core..", "dev.mulcor.net..",
                        "dev.mulcor.harness..", "dev.mulcor.memory..")
                .check(classes);
    }

    @Test
    void coreDoesNotDependOnNetworkOrHarness() {
        noClasses().that().resideInAPackage("dev.mulcor.core..")
                .should().dependOnClassesThat().resideInAnyPackage("dev.mulcor.net..", "dev.mulcor.harness..")
                .check(classes);
    }
}
