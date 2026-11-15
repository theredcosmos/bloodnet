package com.bloodnet.inventory.internal;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory copy of the blood_compatibility table, loaded on first use.
 * The table only changes through a Flyway migration (i.e. with a deploy), so caching it for the
 * life of the app is fine. Keeping it in the DB rather than in code means it can be queried and reviewed.
 */
@Component
@RequiredArgsConstructor
public class CompatibilityMatrix {

    private final JdbcTemplate jdbc;
    private volatile Map<ComponentType, Map<BloodGroup, Set<BloodGroup>>> cache;

    public Set<BloodGroup> donorsFor(BloodGroup recipient, ComponentType component) {
        Map<BloodGroup, Set<BloodGroup>> byRecipient = load().getOrDefault(component, Map.of());
        return byRecipient.getOrDefault(recipient, EnumSet.of(recipient));
    }

    private Map<ComponentType, Map<BloodGroup, Set<BloodGroup>>> load() {
        Map<ComponentType, Map<BloodGroup, Set<BloodGroup>>> local = cache;
        if (local == null) {
            Map<ComponentType, Map<BloodGroup, Set<BloodGroup>>> m = new EnumMap<>(ComponentType.class);
            jdbc.query("SELECT recipient_group, donor_group, component_type FROM blood_compatibility", rs -> {
                m.computeIfAbsent(ComponentType.valueOf(rs.getString(3)), k -> new EnumMap<>(BloodGroup.class))
                        .computeIfAbsent(BloodGroup.valueOf(rs.getString(1)), k -> EnumSet.noneOf(BloodGroup.class))
                        .add(BloodGroup.valueOf(rs.getString(2)));
            });
            cache = local = m;
        }
        return local;
    }
}
