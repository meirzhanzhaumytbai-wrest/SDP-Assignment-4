package kz.aitu.registration.app;

import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.facade.ChainLogRepository;
import kz.aitu.registration.facade.RegistrationAdminFacade;
import kz.aitu.registration.facade.RegistrationFacade;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.seating.SeatingService;

import java.nio.file.Path;

/**
 * Composition root: the only place that knows how the three subsystems, the chain log and the two facades
 * are wired together. Clients (the CLI) only ever receive the two facades from here.
 *
 * <p>Layout on disk, one embedded H2 database per subsystem plus the facade's own chain log:
 * <pre>
 *   data/eligibility.mv.db   data/seating.mv.db   data/finalization.mv.db   data/facade.mv.db
 * </pre>
 */
public final class RegistrationSystem implements AutoCloseable {

    private final EligibilityService eligibility;
    private final SeatingService seating;
    private final FinalizationService finalization;
    private final ChainLogRepository chainLog;
    private final RegistrationFacade registration;
    private final RegistrationAdminFacade admin;

    private RegistrationSystem(EligibilityService eligibility, SeatingService seating,
                               FinalizationService finalization, ChainLogRepository chainLog) {
        this.eligibility = eligibility;
        this.seating = seating;
        this.finalization = finalization;
        this.chainLog = chainLog;
        this.registration = new RegistrationFacade(eligibility, seating, finalization, chainLog);
        this.admin = new RegistrationAdminFacade(eligibility, seating, finalization, chainLog);
    }

    /** Opens (or creates) the four stores inside the directory. Existing data is read back from disk. */
    public static RegistrationSystem open(Path dataDirectory) {
        return new RegistrationSystem(
                EligibilityService.openAt(dataDirectory.resolve("eligibility")),
                SeatingService.openAt(dataDirectory.resolve("seating")),
                FinalizationService.openAt(dataDirectory.resolve("finalization")),
                new ChainLogRepository(dataDirectory.resolve("facade")));
    }

    /** Wires already-built parts together (used to plug in test doubles). */
    public static RegistrationSystem assemble(EligibilityService eligibility, SeatingService seating,
                                              FinalizationService finalization, ChainLogRepository chainLog) {
        return new RegistrationSystem(eligibility, seating, finalization, chainLog);
    }

    public RegistrationFacade registration() {
        return registration;
    }

    public RegistrationAdminFacade admin() {
        return admin;
    }

    /** Loads the demo university if the stores hold no students yet. */
    public void seedDemoDataIfEmpty() {
        if (eligibility.allStandings().isEmpty()) {
            DemoData.seed(eligibility, seating, finalization);
        }
    }

    /** Wipes every store (including the chain log) and loads the demo university again. */
    public void resetToDemoData() {
        eligibility.resetAllData();
        seating.resetAllData();
        finalization.resetAllData();
        chainLog.clearAll();
        DemoData.seed(eligibility, seating, finalization);
    }

    // Subsystem access for wiring, seeding and tests. Clients must go through registration() / admin().
    public EligibilityService eligibility() { return eligibility; }
    public SeatingService seating() { return seating; }
    public FinalizationService finalization() { return finalization; }
    public ChainLogRepository chainLog() { return chainLog; }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (AutoCloseable part : new AutoCloseable[]{eligibility, seating, finalization, chainLog}) {
            try {
                part.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IllegalStateException("Could not close all stores", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
