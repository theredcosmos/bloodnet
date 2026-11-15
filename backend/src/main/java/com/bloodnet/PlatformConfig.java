package com.bloodnet;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

/** Cross-cutting infrastructure: clock, async, scheduling + ShedLock, OpenAPI security scheme. */
@Configuration
@EnableAsync
@OpenAPIDefinition(info = @Info(title = "BloodNet API", version = "v1"),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class PlatformConfig {

    /** Injected everywhere time matters, so tests can control "now". */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Scheduling is switched off in tests (bloodnet.scheduling.enabled=false) so jobs never race the assertions. */
    @Configuration
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
    @ConditionalOnProperty(name = "bloodnet.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
        @Bean
        LockProvider lockProvider(DataSource dataSource) {
            return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                    .withJdbcTemplate(new JdbcTemplate(dataSource))
                    .usingDbTime()   // lock times come from MySQL, so app-server clock skew cannot break locking
                    .build());
        }
    }
}
