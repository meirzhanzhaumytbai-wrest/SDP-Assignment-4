package kz.aitu.registration.facade;

import kz.aitu.registration.support.UniversityScenario;
import kz.aitu.registration.support.UniversityScenario.Attempt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static kz.aitu.registration.facade.ChainStatus.COMPENSATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistrationAdminFacadeTest {

    @TempDir
    Path dir;

    private UniversityScenario university;

    @BeforeEach
    void aSmallUniversity() {
        university = UniversityScenario.inDirectory(dir);
        university.aCourse("CS201");
        university.aCourse("CS301").requiring("CS201").worth(4).withSeats(2);
        university.aStudent("S1").whoPassed("CS201");
    }

    @AfterEach
    void close() {
        university.close();
    }

    @Test
    @DisplayName("an administrator can cancel a COMPLETED registration: C, then B, then A are undone")
    void compensatingACompletedChain() {
        Attempt attempt = university.whenStudent("S1").registersFor("CS301").shouldSucceed();

        ChainRecord chain = university.admin().compensate(attempt.chainId());

        assertEquals(COMPENSATED, chain.status());
        assertEquals(List.of(Stage.C, Stage.B, Stage.A), chain.undoneSteps());
        university.chainOf(attempt).hasClearance("REVOKED").hasSeat("RELEASED").hasInvoice("VOIDED")
                .hasTranscript("WITHDRAWN");
        assertEquals(2, university.seatsLeftIn("CS301"));
    }

    @Test
    @DisplayName("an administrator can compensate a chain stuck half way (A and B committed, C never ran)")
    void compensatingAStuckChain() {
        ChainRecord stuck = ChainRecord.start(new RegistrationRequest("S1", "CS301", UniversityScenario.TERM));
        university.system().chainLog().save(stuck);
        university.system().eligibility().clear(stuck.id(), "S1", "CS301", UniversityScenario.TERM);
        university.system().seating().allocate(stuck.id(), "S1", "CS301", UniversityScenario.TERM);

        ChainRecord done = university.admin().compensate(stuck.id());

        assertEquals(List.of(Stage.B, Stage.A), done.undoneSteps());
        university.chainWithId(stuck.id()).hasClearance("REVOKED").hasSeat("RELEASED");
    }

    @Test
    void nothingToCompensateOnRejectedOrAlreadyCompensatedChains() {
        Attempt rejected = university.whenStudent("NOBODY").registersFor("CS301").shouldBeRejectedAt(Stage.A);
        assertThrows(IllegalStateException.class, () -> university.admin().compensate(rejected.chainId()));

        Attempt done = university.whenStudent("S1").registersFor("CS301").shouldSucceed();
        university.admin().compensate(done.chainId());
        assertThrows(IllegalStateException.class, () -> university.admin().compensate(done.chainId()));
    }

    @Test
    void chainsCanBeFoundByAShortPrefix() {
        Attempt attempt = university.whenStudent("S1").registersFor("CS301").shouldSucceed();
        ChainInspection view = university.admin().inspect(attempt.chainId().substring(0, 8));
        assertEquals(attempt.chainId(), view.chain().id());
        assertEquals("CONSUMED", view.clearanceStatus());
        assertEquals("CONFIRMED", view.seatStatus());
        assertEquals("ISSUED", view.invoiceStatus());
        assertEquals("IN_PROGRESS", view.transcriptStatus());
    }

    @Test
    void unknownAndBlankChainReferencesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> university.admin().inspect("does-not-exist"));
        assertThrows(IllegalArgumentException.class, () -> university.admin().inspect("  "));
        assertThrows(IllegalArgumentException.class, () -> university.admin().inspect(null));
    }

    @Test
    @DisplayName("a prefix shared by several chains is refused as ambiguous")
    void ambiguousPrefixIsRefused() {
        // chain ids start with one of 16 hex digits, so among 17 chains two must share the first character
        for (int i = 0; i < 17; i++) {
            university.whenStudent("NOBODY").registersFor("CS301");
        }
        java.util.Map<Character, Long> perFirstCharacter = university.admin().listChains().stream()
                .collect(java.util.stream.Collectors.groupingBy(c -> c.id().charAt(0),
                        java.util.stream.Collectors.counting()));
        char shared = perFirstCharacter.entrySet().stream()
                .filter(e -> e.getValue() > 1).findFirst().orElseThrow().getKey();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> university.admin().inspect(String.valueOf(shared)));
        assertTrue(e.getMessage().contains("ambiguous"));
    }

    @Test
    void overviewsShowStudentsAndSections() {
        university.whenStudent("S1").registersFor("CS301").shouldSucceed();

        StudentOverview student = university.admin().listStudents().get(0);
        assertEquals("S1", student.studentId());
        assertEquals("CS201", student.completedCourses());
        assertEquals(160_000, student.outstanding());
        assertEquals(1_000_000, student.creditLimit());

        SectionOverview section = university.admin().listSections().stream()
                .filter(s -> s.courseId().equals("CS301")).findFirst().orElseThrow();
        assertEquals(2, section.capacity());
        assertEquals(1, section.seatsInUse());
        assertTrue(section.open());

        assertEquals(1, university.admin().transcriptOf("S1").size());
        assertTrue(university.admin().transcriptOf("S1").get(0).contains("IN_PROGRESS"));
        assertTrue(university.admin().transcriptOf("NOBODY").isEmpty());
    }

    @Test
    @DisplayName("staging operations really change what the next registration meets")
    void stagingOperationsProvokeFailures() {
        university.admin().setSectionCapacity("CS301", UniversityScenario.TERM, 0);
        university.whenStudent("S1").registersFor("CS301").shouldFailAt(Stage.B).andUndo(Stage.A);
        university.admin().setSectionCapacity("CS301", UniversityScenario.TERM, 2);

        university.admin().setSectionOpen("CS301", UniversityScenario.TERM, false);
        university.whenStudent("S1").registersFor("CS301").shouldFailAt(Stage.B).andUndo(Stage.A);
        university.admin().setSectionOpen("CS301", UniversityScenario.TERM, true);

        university.admin().setOverdueDebt("S1", 500_000);
        university.whenStudent("S1").registersFor("CS301").shouldBeRejectedAt(Stage.A);
        university.admin().setOverdueDebt("S1", 0);

        university.admin().setCreditLimit("S1", 10);
        university.whenStudent("S1").registersFor("CS301").shouldFailAt(Stage.C).andUndo(Stage.B, Stage.A);
        university.admin().setCreditLimit("S1", 1_000_000);

        university.admin().setBillingWindowOpen(false);
        assertFalse(university.admin().isBillingWindowOpen());
        university.whenStudent("S1").registersFor("CS301").shouldFailAt(Stage.C).andUndo(Stage.B, Stage.A);
        university.admin().setBillingWindowOpen(true);

        university.whenStudent("S1").registersFor("CS301").shouldSucceed();
    }
}
