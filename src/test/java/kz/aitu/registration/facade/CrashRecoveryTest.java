package kz.aitu.registration.facade;

import kz.aitu.registration.eligibility.EligibilityRepository;
import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.facade.RegistrationException.Kind;
import kz.aitu.registration.finalization.FinalizationRepository;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.finalization.Invoice;
import kz.aitu.registration.seating.SeatReservation;
import kz.aitu.registration.seating.SeatRepository;
import kz.aitu.registration.seating.SeatingService;
import kz.aitu.registration.support.UniversityScenario;
import kz.aitu.registration.support.UniversityScenario.SimulatedCrash;
import kz.aitu.registration.support.UniversityScenario.Wiring;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static kz.aitu.registration.facade.ChainStatus.COMPENSATED;
import static kz.aitu.registration.facade.ChainStatus.COMPENSATING;
import static kz.aitu.registration.facade.ChainStatus.COMPLETED;
import static kz.aitu.registration.facade.ChainStatus.PENDING_B;
import static kz.aitu.registration.facade.ChainStatus.PENDING_C;
import static kz.aitu.registration.facade.ChainStatus.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CrashRecoveryTest {

    @TempDir
    Path dir;

    private UniversityScenario university;

    @AfterEach
    void close() {
        university.close();
    }

    private void start(Wiring wiring) {
        university = UniversityScenario.inDirectory(dir, wiring);
        university.aCourse("CS201");
        university.aCourse("CS301").requiring("CS201").worth(4).withSeats(1);
        university.aStudent("S1").whoPassed("CS201").withCreditLimit(1_000_000);
    }

    private RecoveryReport recoverAfterRestart() {
        university.restart();
        List<RecoveryReport> reports = university.facade().recoverInterruptedChains();
        assertEquals(1, reports.size());
        return reports.get(0);
    }

    private void registerAndCrash() {
        assertThrows(SimulatedCrash.class, () -> university.facade().register(
                new RegistrationRequest("S1", "CS301", UniversityScenario.TERM)));
    }


    @Test
    @DisplayName("crash before C: log says PENDING_C, A and B are on disk -> after restart B then A are undone")
    void crashBeforeFinalizationIsRolledBack() {
        start(new Wiring() {
            @Override
            public FinalizationService finalization(FinalizationRepository repository) {
                return new FinalizationService(repository) {
                    @Override
                    public Invoice finalizeRegistration(String ref, String student, String course, String term) {
                        throw new SimulatedCrash("before C commits");
                    }
                };
            }
        });
        registerAndCrash();
        String chainId = university.onlyChain().id();
        university.chainWithId(chainId).hasStatus(PENDING_C).hasClearance("CLEARED").hasSeat("HELD").hasNoInvoice();

        RecoveryReport report = recoverAfterRestart();

        assertEquals(PENDING_C, report.statusBefore());
        assertEquals(COMPENSATED, report.statusAfter());
        university.chainWithId(chainId).hasStatus(COMPENSATED).hasUndone(Stage.B, Stage.A)
                .hasClearance("REVOKED").hasSeat("RELEASED").hasNoInvoice();
        assertEquals(1, university.seatsLeftIn("CS301"));
    }

    @Test
    @DisplayName("crash after C committed but before the confirm phase: recovery ROLLS FORWARD to COMPLETED")
    void crashAfterFinalizationIsRolledForward() {
        start(new Wiring() {
            @Override
            public SeatingService seating(SeatRepository repository) {
                return new SeatingService(repository) {
                    @Override
                    public SeatReservation confirm(String reference) {
                        throw new SimulatedCrash("before the confirm phase");
                    }
                };
            }
        });
        registerAndCrash();
        String chainId = university.onlyChain().id();
        university.chainWithId(chainId).hasStatus(PENDING_C).hasClearance("CLEARED").hasSeat("HELD")
                .hasInvoice("ISSUED");

        RecoveryReport report = recoverAfterRestart();

        assertEquals(COMPLETED, report.statusAfter());
        assertTrue(report.action().startsWith("rolled forward"));
        university.chainWithId(chainId).hasStatus(COMPLETED).hasClearance("CONSUMED").hasSeat("CONFIRMED")
                .hasInvoice("ISSUED").hasTranscript("IN_PROGRESS");
        assertEquals(0, university.seatsLeftIn("CS301"));
    }

    @Test
    @DisplayName("crash during B: log says PENDING_B, only A is on disk -> after restart A is undone")
    void crashDuringSeatingIsRolledBack() {
        start(new Wiring() {
            @Override
            public SeatingService seating(SeatRepository repository) {
                return new SeatingService(repository) {
                    @Override
                    public SeatReservation allocate(String ref, String student, String course, String term) {
                        throw new SimulatedCrash("during B");
                    }
                };
            }
        });
        registerAndCrash();
        String chainId = university.onlyChain().id();
        university.chainWithId(chainId).hasStatus(PENDING_B).hasClearance("CLEARED").hasNoSeat();

        RecoveryReport report = recoverAfterRestart();

        assertEquals(PENDING_B, report.statusBefore());
        university.chainWithId(chainId).hasStatus(COMPENSATED).hasUndone(Stage.A).hasClearance("REVOKED");
    }

    @Test
    @DisplayName("crash right after the intent was logged: nothing committed -> chain closed with nothing to undo")
    void crashBeforeAnyCommitLeavesNothingToUndo() {
        start(new Wiring() {
            @Override
            public EligibilityService eligibility(EligibilityRepository repository) {
                return new EligibilityService(repository) {
                    @Override
                    public kz.aitu.registration.eligibility.EligibilityClearance clear(String ref, String student,
                                                                                       String course, String term) {
                        throw new SimulatedCrash("during A");
                    }
                };
            }
        });
        registerAndCrash();
        String chainId = university.onlyChain().id();
        university.chainWithId(chainId).hasStatus(STARTED).hasNoClearance();

        RecoveryReport report = recoverAfterRestart();

        assertTrue(report.action().contains("nothing had been committed"));
        university.chainWithId(chainId).hasStatus(COMPENSATED).hasUndone();
    }

    @Test
    @DisplayName("crash in the MIDDLE of an undo: the chain stays COMPENSATING and the restart finishes the undo")
    void crashDuringCompensationIsFinishedAfterRestart() {
        start(new Wiring() {
            @Override
            public SeatingService seating(SeatRepository repository) {
                return new SeatingService(repository) {
                    @Override
                    public boolean release(String reference) {
                        throw new SimulatedCrash("while releasing the seat");
                    }
                };
            }
        });
        university.aStudent("POOR").whoPassed("CS201").withCreditLimit(1);      // C will refuse -> undo starts
        assertThrows(SimulatedCrash.class, () -> university.facade().register(
                new RegistrationRequest("POOR", "CS301", UniversityScenario.TERM)));
        String chainId = university.onlyChain().id();
        university.chainWithId(chainId).hasStatus(COMPENSATING).failedAt(Stage.C)
                .hasClearance("CLEARED").hasSeat("HELD");          // nothing had been undone yet

        RecoveryReport report = recoverAfterRestart();

        assertEquals(COMPENSATING, report.statusBefore());
        university.chainWithId(chainId).hasStatus(COMPENSATED).failedAt(Stage.C).hasUndone(Stage.B, Stage.A)
                .hasClearance("REVOKED").hasSeat("RELEASED");
        assertEquals(1, university.seatsLeftIn("CS301"));
    }


    @Test
    @DisplayName("an undo that throws leaves the chain COMPENSATING (reported to the client) and a later recovery heals it")
    void failedCompensationIsReportedAndLaterHealed() {
        start(new Wiring() {
            @Override
            public SeatingService seating(SeatRepository repository) {
                return new SeatingService(repository) {
                    @Override
                    public boolean release(String reference) {
                        throw new IllegalStateException("seat store unavailable");
                    }
                };
            }
        });
        university.aStudent("POOR").whoPassed("CS201").withCreditLimit(1);
        RegistrationException failure = assertThrows(RegistrationException.class, () -> university.facade().register(
                new RegistrationRequest("POOR", "CS301", UniversityScenario.TERM)));
        assertEquals(Kind.COMPENSATION_INCOMPLETE, failure.kind());
        assertEquals(Stage.C, failure.failedStage());
        assertTrue(failure.getMessage().contains("seat store unavailable"));
        university.chainWithId(failure.chainId()).hasStatus(COMPENSATING).hasClearance("CLEARED").hasSeat("HELD");

        RecoveryReport report = recoverAfterRestart();

        assertEquals(COMPENSATED, report.statusAfter());
        university.chainWithId(failure.chainId()).hasUndone(Stage.B, Stage.A).hasSeat("RELEASED");
    }

    @Test
    @DisplayName("if recovery itself fails it says so, keeps the chain as it was, and the next run succeeds")
    void failingRecoveryIsReportedAndRetried() {
        start(new Wiring() {
            @Override
            public FinalizationService finalization(FinalizationRepository repository) {
                return new FinalizationService(repository) {
                    @Override
                    public Invoice finalizeRegistration(String ref, String student, String course, String term) {
                        throw new SimulatedCrash("before C commits");
                    }
                };
            }
        });
        registerAndCrash();
        String chainId = university.onlyChain().id();

        university.restartWith(new Wiring() {
            @Override
            public SeatingService seating(SeatRepository repository) {
                return new SeatingService(repository) {
                    @Override
                    public boolean release(String reference) {
                        throw new IllegalStateException("still broken");
                    }
                };
            }
        });
        RecoveryReport failed = university.facade().recoverInterruptedChains().get(0);
        assertTrue(failed.action().startsWith("recovery failed"));
        assertEquals(COMPENSATING, failed.statusAfter());

        university.restart();
        RecoveryReport healed = university.facade().recoverInterruptedChains().get(0);
        assertEquals(COMPENSATED, healed.statusAfter());
        university.chainWithId(chainId).hasUndone(Stage.B, Stage.A);
    }


    @Test
    @DisplayName("the log lags behind the disk: A, B and C all committed but the log says STARTED -> rolled forward")
    void laggingLogIsCorrectedFromWhatIsOnDisk() {
        start(new Wiring() { });
        ChainRecord chain = ChainRecord.start(new RegistrationRequest("S1", "CS301", UniversityScenario.TERM));
        university.system().chainLog().save(chain);                       // log: STARTED, nothing else
        university.system().eligibility().clear(chain.id(), "S1", "CS301", UniversityScenario.TERM);
        university.system().seating().allocate(chain.id(), "S1", "CS301", UniversityScenario.TERM);
        university.system().finalization().finalizeRegistration(chain.id(), "S1", "CS301", UniversityScenario.TERM);

        RecoveryReport report = recoverAfterRestart();

        assertEquals(STARTED, report.statusBefore());
        assertEquals(COMPLETED, report.statusAfter());
        university.chainWithId(chain.id()).hasStatus(COMPLETED).hasClearance("CONSUMED").hasSeat("CONFIRMED");
    }

    @Test
    @DisplayName("C is on disk but B is missing (inconsistent): recovery does not trust it and rolls back C then A")
    void inconsistentChainIsRolledBack() {
        start(new Wiring() { });
        ChainRecord chain = ChainRecord.start(new RegistrationRequest("S1", "CS301", UniversityScenario.TERM));
        university.system().chainLog().save(chain);
        university.system().eligibility().clear(chain.id(), "S1", "CS301", UniversityScenario.TERM);
        university.system().finalization().finalizeRegistration(chain.id(), "S1", "CS301", UniversityScenario.TERM);

        recoverAfterRestart();

        university.chainWithId(chain.id()).hasStatus(COMPENSATED).hasUndone(Stage.C, Stage.A)
                .hasClearance("REVOKED").hasInvoice("VOIDED").hasTranscript("WITHDRAWN");
    }

    // ---------------------------------------------------------------- restart without any problem

    @Test
    @DisplayName("a completed registration is read back unchanged after a restart and recovery leaves it alone")
    void completedChainSurvivesRestart() {
        start(new Wiring() { });
        UniversityScenario.Attempt attempt = university.whenStudent("S1").registersFor("CS301").shouldSucceed();

        university.restart();

        assertTrue(university.facade().recoverInterruptedChains().isEmpty());
        university.chainOf(attempt).hasStatus(COMPLETED).hasClearance("CONSUMED").hasSeat("CONFIRMED")
                .hasInvoice("ISSUED").hasTranscript("IN_PROGRESS");
        assertEquals(0, university.seatsLeftIn("CS301"));
    }
}
