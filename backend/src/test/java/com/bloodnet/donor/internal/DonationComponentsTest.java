package com.bloodnet.donor.internal;

import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.ComponentType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.bloodnet.shared.ComponentType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DonationComponentsTest {

    @Test
    void defaultsToSeparatedComponents() {
        assertThat(DonorService.componentsFor(null)).containsExactly(ComponentType.separatedComponents());
        assertThat(DonorService.componentsFor(List.of())).containsExactly(ComponentType.separatedComponents());
    }

    @Test
    void removesDuplicates() {
        assertThat(DonorService.componentsFor(List.of(PLASMA, PLASMA, RED_CELLS))).containsExactly(PLASMA, RED_CELLS);
    }

    @Test
    void wholeBloodAloneIsFine() {
        assertThat(DonorService.componentsFor(List.of(WHOLE_BLOOD))).containsExactly(WHOLE_BLOOD);
    }

    @Test
    void wholeBloodPlusComponentsWouldDoubleCount() {
        assertThatThrownBy(() -> DonorService.componentsFor(List.of(WHOLE_BLOOD, RED_CELLS)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void nullEntryIsAClientError() {
        assertThatThrownBy(() -> DonorService.componentsFor(Arrays.asList(RED_CELLS, null)))
                .isInstanceOf(BusinessRuleException.class);
    }
}
