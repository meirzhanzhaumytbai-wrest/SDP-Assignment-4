package kz.aitu.registration.facade;

import kz.aitu.registration.facade.RegistrationException.Kind;
import kz.aitu.registration.support.UniversityScenario;
import kz.aitu.registration.support.UniversityScenario.Attempt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static kz.aitu.registration.facade.ChainStatus.COMPENSATED;
import static kz.aitu.registration.facade.ChainStatus.COMPLETED;
import static kz.aitu.registration.facade.ChainStatus.REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The facade's everyday workflow, driven through the test language. Each test gets its own fresh stores. */
class RegistrationFacadeTest {

    @TempDir
    Path dir;

    private UniversityScenario university;

    @BeforeEach
    void aSmallUniversity() {
        university = UniversityScenario.inDirectory(dir);
        university.aCourse("CS201").withSeats(30);
        university.aCourse("CS301").requiring("CS201").worth(4).withSeats(10);
        university.aStudent("S1").whoPassed("CS201").withCreditLimit(1_000_000);
    }

    @AfterEach
    void close() {
        university.close();
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    @DisplayName("a good request goes through A, B and C and leaves every subsystem committed and confirmed")
    void successfulRegistration() {
        Attempt attempt = university.whenStudent("S1").registersFor("CS301").shouldSucceed();

        assertEquals(160_000, attempt.receipt().tuitionKzt());
        university.chainOf(attempt).hasStatus(COMPLETED)
                .hasClearance("CONSUMED").hasSeat("CONFIRMED")
                .hasInvoice("ISSUED").hasTranscript("IN_PROGRESS");
        assertEquals(9, university.seatsLeftIn("CS301"));
    }

    @Test
    void scholarshipIsAppliedByTheFinalStep() {
        university.aStudent("S2").whoPassed("CS201").withScholarship(50);
        Attempt attempt = university.whenStudent("S2").registersFor("CS301").shouldSucceed();
        assertEquals(80_000, attempt.receipt().tuitionKzt());
    }

    // ---------------------------------------------------------------- A refuses: nothing committed, nothing to undo

    @Test
    @DisplayName("missing prerequisite: rejected at A, chain REJECTED, B and C were never touched")
    void rejectedAtAForMissingPrerequisite() {
        university.aStudent("S3").whoPassed("CS101");
        Attempt attempt = university.whenStudent("S3").registersFor("CS301").shouldBeRejectedAt(Stage.A);

        assertTrue(attempt.failure().getMessage().contains("PREREQUISITE_MISSING"));
        university.chainOf(attempt).hasStatus(REJECTED).failedAt(Stage.A)
                .hasNoClearance().hasNoSeat().hasNoInvoice();
        assertEquals(10, university.seatsLeftIn("CS301"));
    }

    @Test
    void rejectedAtAForFinancialHold() {
        university.aStudent("S4").whoPassed("CS201").owing(150_000);
        Attempt attempt = university.whenStudent("S4").registersFor("CS301").shouldBeRejectedAt(Stage.A);
        assertTrue(attempt.failure().getMessage().contains("FINANCIAL_HOLD"));
        university.chainOf(attempt).hasStatus(REJECTED).hasNoClearance();
    }

    @Test
    void rejectedAtAForUnknownStudentAndUnknownCourse() {
        university.whenStudent("NOBODY").registersFor("CS301").shouldBeRejectedAt(Stage.A);
        university.whenStudent("S1").registersFor("XYZ999").shouldBeRejectedAt(Stage.A);
    }

    @Test
    void registeringTwiceForTheSameCourseIsRejectedAtA() {
        university.whenStudent("S1").registersFor("CS301").shouldSucceed();
        Attempt second = university.whenStudent("S1").registersFor("CS301").shouldBeRejectedAt(Stage.A);
        assertTrue(second.failure().getMessage().contains("ALREADY_CLEARED"));
        assertEquals(9, university.seatsLeftIn("CS301"));   // the first seat is still the only one in use
    }

    // ---------------------------------------------------------------- B fails after A committed: A alone is undone

    @Test
    @DisplayName("B fails (section full) after A committed: only A is undone")
    void seatingFailureUndoesEligibilityOnly() {
        university.aCourse("CS350").requiring("CS201").withSeats(0);

        Attempt attempt = university.whenStudent("S1").registersFor("CS350")
                .shouldFailAt(Stage.B).andUndo(Stage.A);

        assertTrue(attempt.failure().getMessage().contains("SECTION_FULL"));
        university.chainOf(attempt).hasStatus(COMPENSATED).failedAt(Stage.B).hasUndone(Stage.A)
                .hasClearance("REVOKED").hasNoSeat().hasNoInvoice();
    }

    @Test
    void closedSectionAndUnknownSectionAlsoFailAtB() {
        university.aCourse("CS360").requiring("CS201").thatIsClosed();
        Attempt closed = university.whenStudent("S1").registersFor("CS360").shouldFailAt(Stage.B).andUndo(Stage.A);
        assertTrue(closed.failure().getMessage().contains("SECTION_CLOSED"));

        // known to A (it has prerequisites) but there is no section in this term at all
        Attempt otherTerm = university.whenStudent("S1").registersFor("CS301", "2030-SPRING")
                .shouldFailAt(Stage.B).andUndo(Stage.A);
        assertTrue(otherTerm.failure().getMessage().contains("UNKNOWN_SECTION"));
    }

    @Test
    @DisplayName("after B's failure the student is free to try again: the revoked clearance no longer blocks")
    void studentCanRetryAfterACompensatedFailure() {
        university.aCourse("CS350").requiring("CS201").withSeats(0);
        university.whenStudent("S1").registersFor("CS350").shouldFailAt(Stage.B);

        university.aCourse("CS350").requiring("CS201").withSeats(1);     // a seat opens up
        university.whenStudent("S1").registersFor("CS350").shouldSucceed();
    }

    // ---------------------------------------------------------------- C fails after A and B: B, then A

    @Test
    @DisplayName("C fails (credit limit) after A and B committed: B is undone first, then A")
    void finalizationFailureUndoesSeatingThenEligibility() {
        university.aStudent("S5").whoPassed("CS201").withCreditLimit(100_000);

        Attempt attempt = university.whenStudent("S5").registersFor("CS301")
                .shouldFailAt(Stage.C).andUndo(Stage.B, Stage.A);

        assertTrue(attempt.failure().getMessage().contains("LIMIT_EXCEEDED"));
        university.chainOf(attempt).hasStatus(COMPENSATED).failedAt(Stage.C).hasUndone(Stage.B, Stage.A)
                .hasClearance("REVOKED").hasSeat("RELEASED").hasNoInvoice().hasTranscript("-");
        assertEquals(10, university.seatsLeftIn("CS301"));      // the seat really went back
    }

    @Test
    void closedBillingOfficeFailsAtCAndUndoesBAndA() {
        university.billingOfficeIsClosed();
        Attempt attempt = university.whenStudent("S1").registersFor("CS301")
                .shouldFailAt(Stage.C).andUndo(Stage.B, Stage.A);
        assertTrue(attempt.failure().getMessage().contains("BILLING_CLOSED"));
    }

    @Test
    @DisplayName("the seat released by a failed chain can immediately be used by someone else")
    void releasedSeatIsReallyAvailableAgain() {
        university.aCourse("CS350").requiring("CS201").withSeats(1);
        university.aStudent("POOR").whoPassed("CS201").withCreditLimit(1);
        university.whenStudent("POOR").registersFor("CS350").shouldFailAt(Stage.C).andUndo(Stage.B, Stage.A);
        university.whenStudent("S1").registersFor("CS350").shouldSucceed();
    }

    // ---------------------------------------------------------------- invalid input

    @Test
    void invalidRequestsAreRefusedBeforeAnythingIsStored() {
        for (RegistrationRequest bad : new RegistrationRequest[]{
                new RegistrationRequest("", "CS301", "2026-FALL"),
                new RegistrationRequest("S1", "  ", "2026-FALL"),
                new RegistrationRequest("S1", "CS301", null),
                new RegistrationRequest("S1".repeat(20), "CS301", "2026-FALL"),
                null}) {
            RegistrationException e = assertThrows(RegistrationException.class,
                    () -> university.facade().register(bad));
            assertEquals(Kind.INVALID_REQUEST, e.kind());
        }
        assertTrue(university.admin().listChains().isEmpty());
    }

    @Test
    void everyAttemptIsLoggedWithItsOutcome() {
        university.whenStudent("S1").registersFor("CS301").shouldSucceed();
        university.whenStudent("NOBODY").registersFor("CS301").shouldBeRejectedAt(Stage.A);
        assertEquals(2, university.admin().listChains().size());
        assertEquals(COMPLETED, university.admin().listChains().get(0).status());
        assertEquals(REJECTED, university.admin().listChains().get(1).status());
    }
}
