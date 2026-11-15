package com.bloodnet.inventory.internal;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Endpoints for the public "find blood near me" page. No login: SecurityConfig permits /api/public/**
 * and PublicRateLimitFilter caps requests per client. Responses may be cached for 30 seconds.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Validated
public class PublicController {

    private static final CacheControl SHORT_CACHE = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final PublicAvailabilityService service;

    @GetMapping("/availability")
    public ResponseEntity<List<PublicAvailabilityService.PublicBank>> availability(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
            @RequestParam(defaultValue = "25") @Min(1) @Max(200) double radiusKm,
            @RequestParam(required = false) BloodGroup bloodGroup,
            @RequestParam(required = false) ComponentType component) {
        return ResponseEntity.ok().cacheControl(SHORT_CACHE)
                .body(service.near(lat, lng, radiusKm, bloodGroup, component));
    }

    @GetMapping("/banks/{id}")
    public ResponseEntity<PublicAvailabilityService.BankStock> bank(@PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(SHORT_CACHE).body(service.bank(id));
    }

    @GetMapping("/areas")
    public ResponseEntity<List<PublicAvailabilityService.Area>> areas() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(service.areas());
    }
}
