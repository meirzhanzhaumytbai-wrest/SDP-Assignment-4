package kz.aitu.registration.eligibility;

import kz.aitu.registration.eligibility.EligibilityClearance.Status;
import kz.aitu.registration.eligibility.EligibilityService.NotEligibleException;
import kz.aitu.registration.eligibility.EligibilityService.NotEligibleException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Subsystem A on its own: no facade, no other subsystem, every test on a fresh store. */
class EligibilityServiceTest {

    @TempDir
    Path dir;

    private EligibilityService eligibility;

    @BeforeEach
    void openFreshStore() {
        eligibility = EligibilityService.openAt(dir.resolve("a"));
        eligibility.defineCourse("CS101", Set.of());
        eligibility.defineCourse("CS201", Set.of("CS101"));
        eligibility.registerStudent("S1", "Aibek", Set.of("CS101"), 0);
    }

    @AfterEach
    void closeStore() {
        eligibility.close();
    }

    private Reason reasonOf(Runnable action) {
        return assertThrows(NotEligibleException.class, action::run).reason();
    }

    @Test
    @DisplayName("an eligible student gets a CLEARED clearance that is stored")
    void clearsEligibleStudent() {
        EligibilityClearance clearance = eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        assertEquals(Status.CLEARED, clearance.status());
        assertEquals(Status.CLEARED, eligibility.findByReference("ref-1").orElseThrow().status());
        assertEquals(1, eligibility.findAll().size());
    }

    @Test
    void courseWithoutPrerequisitesIsOpenToEveryone() {
        assertEquals(Status.CLEARED, eligibility.clear("ref-1", "S1", "CS101", "2026-FALL").status());
    }

    @Test
    void missingPrerequisiteIsRefusedAndNothingIsStored() {
        eligibility.registerStudent("S2", "Newbie", Set.of(), 0);
        assertEquals(Reason.PREREQUISITE_MISSING, reasonOf(() -> eligibility.clear("ref-1", "S2", "CS201", "2026-FALL")));
        assertTrue(eligibility.findAll().isEmpty());
    }

    @Test
    void financialHoldIsRefused() {
        eligibility.setOverdueDebt("S1", 250_000);
        assertEquals(Reason.FINANCIAL_HOLD, reasonOf(() -> eligibility.clear("ref-1", "S1", "CS201", "2026-FALL")));
    }

    @Test
    void unknownStudentAndUnknownCourseAreRefused() {
        assertEquals(Reason.UNKNOWN_STUDENT, reasonOf(() -> eligibility.clear("r1", "NOBODY", "CS201", "2026-FALL")));
        assertEquals(Reason.UNKNOWN_COURSE, reasonOf(() -> eligibility.clear("r2", "S1", "XYZ999", "2026-FALL")));
    }

    @Test
    void secondActiveClearanceForTheSameCourseAndTermIsRefused() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        assertEquals(Reason.ALREADY_CLEARED, reasonOf(() -> eligibility.clear("ref-2", "S1", "CS201", "2026-FALL")));
        // another term is a different right
        assertEquals(Status.CLEARED, eligibility.clear("ref-3", "S1", "CS201", "2027-SPRING").status());
    }

    @Test
    @DisplayName("clear() is idempotent per reference")
    void clearTwiceWithSameReferenceReturnsTheSameClearance() {
        EligibilityClearance first = eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        EligibilityClearance again = eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        assertEquals(first.id(), again.id());
        assertEquals(1, eligibility.findAll().size());
    }

    @Test
    void consumeMovesToConsumedAndIsIdempotent() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        assertEquals(Status.CONSUMED, eligibility.consume("ref-1").status());
        assertEquals(Status.CONSUMED, eligibility.consume("ref-1").status());
    }

    @Test
    void consumeWithoutClearanceFails() {
        assertThrows(IllegalStateException.class, () -> eligibility.consume("missing"));
    }

    @Test
    void consumeOfRevokedClearanceIsAnIllegalTransition() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        eligibility.revoke("ref-1");
        assertThrows(EligibilityClearance.IllegalTransitionException.class, () -> eligibility.consume("ref-1"));
    }

    @Test
    @DisplayName("revoke() reports whether it changed anything and is safe to repeat")
    void revokeIsIdempotent() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        assertTrue(eligibility.revoke("ref-1"));
        assertFalse(eligibility.revoke("ref-1"));
        assertFalse(eligibility.revoke("never-existed"));
        assertEquals(Status.REVOKED, eligibility.findByReference("ref-1").orElseThrow().status());
    }

    @Test
    void revokedClearanceFreesTheCourseForANewClearance() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        eligibility.revoke("ref-1");
        assertEquals(Status.CLEARED, eligibility.clear("ref-2", "S1", "CS201", "2026-FALL").status());
    }

    @Test
    void revokeWorksOnAConsumedClearanceToo() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        eligibility.consume("ref-1");
        assertTrue(eligibility.revoke("ref-1"));
    }

    @Test
    @DisplayName("everything survives closing and reopening the store")
    void stateIsReadBackFromDiskAfterReopen() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        eligibility.consume("ref-1");
        eligibility.close();

        eligibility = EligibilityService.openAt(dir.resolve("a"));
        Optional<EligibilityClearance> reloaded = eligibility.findByReference("ref-1");
        assertEquals(Status.CONSUMED, reloaded.orElseThrow().status());
        assertEquals(Set.of("CS101"), eligibility.findStanding("S1").orElseThrow().completedCourses());
        assertEquals(1, eligibility.allStandings().size());
    }

    @Test
    void setOverdueDebtOnUnknownStudentFails() {
        assertThrows(IllegalArgumentException.class, () -> eligibility.setOverdueDebt("NOBODY", 5));
    }

    @Test
    void resetAllDataEmptiesTheStore() {
        eligibility.clear("ref-1", "S1", "CS201", "2026-FALL");
        eligibility.resetAllData();
        assertTrue(eligibility.findAll().isEmpty());
        assertTrue(eligibility.allStandings().isEmpty());
        assertSame(Optional.empty(), eligibility.findStanding("S1"));
    }

    @Test
    void customCheckersCanBeInjected() {
        eligibility.close();
        eligibility = new EligibilityService(new EligibilityRepository(dir.resolve("custom")),
                new PrerequisiteChecker(), new FinancialHoldChecker(10));
        eligibility.registerStudent("S1", "Aibek", Set.of(), 11);
        eligibility.defineCourse("CS101", Set.of());
        assertEquals(Reason.FINANCIAL_HOLD, reasonOf(() -> eligibility.clear("ref", "S1", "CS101", "2026-FALL")));
    }
}
