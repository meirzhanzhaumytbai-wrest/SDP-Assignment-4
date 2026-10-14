package kz.aitu.registration.support;

import kz.aitu.registration.app.RegistrationSystem;
import kz.aitu.registration.eligibility.EligibilityRepository;
import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.facade.ChainInspection;
import kz.aitu.registration.facade.ChainLogRepository;
import kz.aitu.registration.facade.ChainRecord;
import kz.aitu.registration.facade.ChainStatus;
import kz.aitu.registration.facade.RegistrationAdminFacade;
import kz.aitu.registration.facade.RegistrationException;
import kz.aitu.registration.facade.RegistrationFacade;
import kz.aitu.registration.facade.RegistrationReceipt;
import kz.aitu.registration.facade.RegistrationRequest;
import kz.aitu.registration.facade.Stage;
import kz.aitu.registration.finalization.FinalizationRepository;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.seating.SeatRepository;
import kz.aitu.registration.seating.SeatingService;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A small test language for the registration domain, so a test reads like the story it checks:
 *
 * <pre>
 *   university.aStudent("S1").whoPassed("CS101", "CS201").withCreditLimit(100_000);
 *   university.aCourse("CS301").requiring("CS201").worth(4).withSeats(10);
 *   Attempt attempt = university.whenStudent("S1").registersFor("CS301");
 *   attempt.shouldFailAt(Stage.C).andUndo(Stage.B, Stage.A);
 *   university.chainOf(attempt).hasSeat("RELEASED").hasClearance("REVOKED").hasNoInvoice();
 * </pre>
 *
 * Every scenario lives in its own directory, so tests never share state.
 */
public final class UniversityScenario implements AutoCloseable {

    public static final String TERM = "2026-FALL";

    /** Decides which concrete service objects are built on top of the stores (to plug in faults). */
    public interface Wiring {
        default EligibilityService eligibility(EligibilityRepository repository) {
            return new EligibilityService(repository);
        }

        default SeatingService seating(SeatRepository repository) {
            return new SeatingService(repository);
        }

        default FinalizationService finalization(FinalizationRepository repository) {
            return new FinalizationService(repository);
        }
    }

    /** Stands in for a process that is killed: it is an Error, so the facade does not catch it. */
    public static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        public SimulatedCrash(String where) {
            super("simulated crash " + where);
        }
    }

    private final Path directory;
    private RegistrationSystem system;

    private UniversityScenario(Path directory, Wiring wiring) {
        this.directory = directory;
        this.system = build(directory, wiring);
    }

    public static UniversityScenario inDirectory(Path directory) {
        return new UniversityScenario(directory, new Wiring() { });
    }

    public static UniversityScenario inDirectory(Path directory, Wiring wiring) {
        return new UniversityScenario(directory, wiring);
    }

    private static RegistrationSystem build(Path directory, Wiring wiring) {
        return RegistrationSystem.assemble(
                wiring.eligibility(new EligibilityRepository(directory.resolve("eligibility"))),
                wiring.seating(new SeatRepository(directory.resolve("seating"))),
                wiring.finalization(new FinalizationRepository(directory.resolve("finalization"))),
                new ChainLogRepository(directory.resolve("facade")));
    }

    // ------------------------------------------------------------------------------------ restart

    /** Closes every store and opens them again from disk: what a process restart does. */
    public UniversityScenario restart() {
        return restartWith(new Wiring() { });
    }

    public UniversityScenario restartWith(Wiring wiring) {
        system.close();
        system = build(directory, wiring);
        return this;
    }

    // ------------------------------------------------------------------------------------ given

    public StudentBuilder aStudent(String id) {
        return new StudentBuilder(id);
    }

    public CourseBuilder aCourse(String id) {
        return new CourseBuilder(id);
    }

    public UniversityScenario billingOfficeIsClosed() {
        system.admin().setBillingWindowOpen(false);
        return this;
    }

    public final class StudentBuilder {
        private final String id;
        private final Set<String> passed = new TreeSet<>();
        private long debt;
        private long creditLimit = 1_000_000;
        private int scholarship;

        private StudentBuilder(String id) {
            this.id = id;
            save();
        }

        public StudentBuilder whoPassed(String... courses) {
            passed.addAll(List.of(courses));
            return save();
        }

        public StudentBuilder owing(long overdueDebt) {
            this.debt = overdueDebt;
            return save();
        }

        public StudentBuilder withCreditLimit(long limit) {
            this.creditLimit = limit;
            return save();
        }

        public StudentBuilder withScholarship(int percent) {
            this.scholarship = percent;
            return save();
        }

        private StudentBuilder save() {
            system.eligibility().registerStudent(id, "Student " + id, passed, debt);
            system.finalization().defineAccount(id, creditLimit, scholarship);
            return this;
        }
    }

    public final class CourseBuilder {
        private final String id;
        private final Set<String> prerequisites = new TreeSet<>();
        private int credits = 3;
        private int seats = 30;
        private boolean open = true;

        private CourseBuilder(String id) {
            this.id = id;
            save();
        }

        public CourseBuilder requiring(String... courses) {
            prerequisites.addAll(List.of(courses));
            return save();
        }

        public CourseBuilder worth(int credits) {
            this.credits = credits;
            return save();
        }

        public CourseBuilder withSeats(int seats) {
            this.seats = seats;
            return save();
        }

        public CourseBuilder thatIsClosed() {
            this.open = false;
            return save();
        }

        private CourseBuilder save() {
            system.eligibility().defineCourse(id, prerequisites);
            system.seating().defineSection(id, TERM, seats, open);
            system.finalization().defineCourseCredits(id, credits);
            return this;
        }
    }

    // ------------------------------------------------------------------------------------ when

    public Registering whenStudent(String studentId) {
        return new Registering(studentId);
    }

    public final class Registering {
        private final String studentId;

        private Registering(String studentId) {
            this.studentId = studentId;
        }

        public Attempt registersFor(String courseId) {
            return registersFor(courseId, TERM);
        }

        public Attempt registersFor(String courseId, String term) {
            try {
                return new Attempt(system.registration().register(new RegistrationRequest(studentId, courseId, term)),
                        null);
            } catch (RegistrationException e) {
                return new Attempt(null, e);
            }
        }
    }

    /** The outcome of one registration, with assertions phrased as sentences. */
    public static final class Attempt {
        private final RegistrationReceipt receipt;
        private final RegistrationException failure;

        private Attempt(RegistrationReceipt receipt, RegistrationException failure) {
            this.receipt = receipt;
            this.failure = failure;
        }

        public Attempt shouldSucceed() {
            assertNull(failure, () -> "expected success but got: " + (failure == null ? "" : failure.getMessage()));
            assertNotNull(receipt);
            assertEquals(ChainStatus.COMPLETED, receipt.status());
            return this;
        }

        public Attempt shouldBeRejectedAt(Stage stage) {
            assertNotNull(failure, "expected a failure but the registration succeeded");
            assertEquals(RegistrationException.Kind.REJECTED, failure.kind());
            assertEquals(stage, failure.failedStage());
            assertEquals(List.of(), failure.undone());
            return this;
        }

        public Attempt shouldFailAt(Stage stage) {
            assertNotNull(failure, "expected a failure but the registration succeeded");
            assertEquals(RegistrationException.Kind.COMPENSATED, failure.kind(), failure.getMessage());
            assertEquals(stage, failure.failedStage());
            return this;
        }

        public Attempt andUndo(Stage... inThisOrder) {
            assertEquals(List.of(inThisOrder), failure.undone());
            return this;
        }

        public String chainId() {
            return receipt != null ? receipt.chainId() : failure.chainId();
        }

        public RegistrationReceipt receipt() {
            return receipt;
        }

        public RegistrationException failure() {
            return failure;
        }
    }

    // ------------------------------------------------------------------------------------ then

    public ChainAssertions chainOf(Attempt attempt) {
        return chainWithId(attempt.chainId());
    }

    public ChainAssertions chainWithId(String chainId) {
        return new ChainAssertions(system.admin().inspect(chainId));
    }

    /** The only chain in the log (for tests where a crash prevented a normal result). */
    public ChainRecord onlyChain() {
        List<ChainRecord> chains = system.admin().listChains();
        assertEquals(1, chains.size(), "expected exactly one chain in the log");
        return chains.get(0);
    }

    public int seatsLeftIn(String courseId) {
        return system.seating().seatsLeft(courseId, TERM);
    }

    /** What A, B and C really hold on disk for one chain, as assertions. */
    public static final class ChainAssertions {
        private final ChainInspection view;

        private ChainAssertions(ChainInspection view) {
            this.view = view;
        }

        public ChainAssertions hasStatus(ChainStatus status) {
            assertEquals(status, view.chain().status());
            return this;
        }

        public ChainAssertions failedAt(Stage stage) {
            assertEquals(stage, view.chain().failedStage());
            return this;
        }

        public ChainAssertions hasUndone(Stage... inThisOrder) {
            assertEquals(List.of(inThisOrder), view.chain().undoneSteps());
            return this;
        }

        public ChainAssertions hasClearance(String status) {
            assertEquals(status, view.clearanceStatus(), "clearance in A");
            return this;
        }

        public ChainAssertions hasSeat(String status) {
            assertEquals(status, view.seatStatus(), "seat in B");
            return this;
        }

        public ChainAssertions hasInvoice(String status) {
            assertEquals(status, view.invoiceStatus(), "invoice in C");
            return this;
        }

        public ChainAssertions hasTranscript(String status) {
            assertEquals(status, view.transcriptStatus(), "transcript in C");
            return this;
        }

        public ChainAssertions hasNoClearance() {
            return hasClearance("-");
        }

        public ChainAssertions hasNoSeat() {
            return hasSeat("-");
        }

        public ChainAssertions hasNoInvoice() {
            return hasInvoice("-");
        }
    }

    // ------------------------------------------------------------------------------------ access

    public RegistrationFacade facade() {
        return system.registration();
    }

    public RegistrationAdminFacade admin() {
        return system.admin();
    }

    public RegistrationSystem system() {
        return system;
    }

    public void shouldRefuseRegistration(String studentId, String courseId, RegistrationException.Kind kind) {
        RegistrationException e = assertThrows(RegistrationException.class,
                () -> system.registration().register(new RegistrationRequest(studentId, courseId, TERM)));
        assertEquals(kind, e.kind());
    }

    @Override
    public void close() {
        system.close();
    }
}
