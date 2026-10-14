package kz.aitu.registration.eligibility;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EligibilityCheckersTest {

    private final PrerequisiteChecker prerequisites = new PrerequisiteChecker();

    @Test
    void prerequisitesCoveredWhenEveryRequiredCourseIsCompleted() {
        assertTrue(prerequisites.isSatisfied(Set.of("CS101", "MATH101"), Set.of("CS101")));
        assertTrue(prerequisites.isSatisfied(Set.of(), Set.of()));
    }

    @Test
    void missingPrerequisitesAreListedInOrder() {
        assertEquals(Set.of("CS201", "MATH201"),
                prerequisites.missing(Set.of("CS101"), Set.of("MATH201", "CS201", "CS101")));
        assertFalse(prerequisites.isSatisfied(Set.of("CS101"), Set.of("CS201")));
    }

    @Test
    void holdStartsStrictlyAboveTheThreshold() {
        FinancialHoldChecker checker = new FinancialHoldChecker(100_000);
        assertFalse(checker.hasHold(100_000));
        assertTrue(checker.hasHold(100_001));
        assertFalse(checker.hasHold(0));
    }

    @Test
    void defaultThresholdIsOneHundredThousandTenge() {
        assertEquals(100_000, new FinancialHoldChecker().threshold());
    }

    @Test
    void negativeThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FinancialHoldChecker(-1));
    }
}
