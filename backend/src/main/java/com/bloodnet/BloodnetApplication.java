package com.bloodnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

import java.util.TimeZone;

/**
 * Each package directly under com.bloodnet is a module (auth, donor, inventory, request, transfer,
 * notification, reporting, shared). Other modules may only use classes in a module's top package;
 * anything in its "internal" package is private to it. ModularityTest checks this.
 */
@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class BloodnetApplication {
    public static void main(String[] args) {
        // Everything is stored and compared in UTC. Pinning the JVM zone means a server in IST
        // (or a laptop) can't shift JDBC Timestamps by +5:30.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(BloodnetApplication.class, args);
    }
}
