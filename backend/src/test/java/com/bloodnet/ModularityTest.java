package com.bloodnet;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Breaks the build if a module uses another module's internal package, or if the modules
 * depend on each other in a cycle.
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(BloodnetApplication.class);

    @Test
    void modulesRespectBoundaries() {
        modules.forEach(System.out::println);
        modules.verify();
    }

    @Test
    void writeModuleDocumentation() {
        // Generates PlantUML component diagrams + module canvases under target/spring-modulith-docs
        new Documenter(modules).writeDocumentation();
    }
}
