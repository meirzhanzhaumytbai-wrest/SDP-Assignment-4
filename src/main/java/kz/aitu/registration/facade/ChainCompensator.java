package kz.aitu.registration.facade;

import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.seating.SeatingService;

import java.util.ArrayList;
import java.util.List;

/**
 * The undo engine shared by both facades.
 *
 * <p>It does NOT decide what to undo from an in-memory flag or from "which step just failed". It asks each
 * subsystem, by the chain's reference, whether it really holds committed state for the chain, and undoes
 * exactly that, newest first (C, then B, then A). Every undo is idempotent and reports whether it
 * changed anything, so:
 * <ul>
 *   <li>B failed after A committed: C and B have nothing, only A is undone.</li>
 *   <li>C failed after A and B committed: B is undone, then A.</li>
 *   <li>a crash half way through an undo: the same call can simply be repeated.</li>
 * </ul>
 */
final class ChainCompensator {

    private final EligibilityService eligibility;
    private final SeatingService seating;
    private final FinalizationService finalization;

    ChainCompensator(EligibilityService eligibility, SeatingService seating, FinalizationService finalization) {
        this.eligibility = eligibility;
        this.seating = seating;
        this.finalization = finalization;
    }

    /** @return the steps that really had something to undo, in the order they were undone */
    List<Stage> compensate(String reference) {
        List<Stage> undone = new ArrayList<>();
        if (finalization.voidRegistration(reference)) {
            undone.add(Stage.C);
        }
        if (seating.release(reference)) {
            undone.add(Stage.B);
        }
        if (eligibility.revoke(reference)) {
            undone.add(Stage.A);
        }
        return undone;
    }
}
