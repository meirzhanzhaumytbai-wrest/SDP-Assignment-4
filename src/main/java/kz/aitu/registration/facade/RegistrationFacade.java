package kz.aitu.registration.facade;

import kz.aitu.registration.eligibility.EligibilityClearance;
import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.finalization.Invoice;
import kz.aitu.registration.seating.SeatReservation;
import kz.aitu.registration.seating.SeatingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


public class RegistrationFacade {

    private final EligibilityService eligibility;
    private final SeatingService seating;
    private final FinalizationService finalization;
    private final ChainLogRepository chainLog;
    private final ChainCompensator compensator;

    public RegistrationFacade(EligibilityService eligibility, SeatingService seating,
                              FinalizationService finalization, ChainLogRepository chainLog) {
        this.eligibility = eligibility;
        this.seating = seating;
        this.finalization = finalization;
        this.chainLog = chainLog;
        this.compensator = new ChainCompensator(eligibility, seating, finalization);
    }

    /**
     * Registers the student for the course.
     *
     * @throws RegistrationException with kind INVALID_REQUEST, REJECTED (A said no, nothing to undo),
     *         COMPENSATED (B or C failed, the committed part was undone) or COMPENSATION_INCOMPLETE
     */
    public RegistrationReceipt register(RegistrationRequest request) {
        validate(request);
        ChainRecord chain = ChainRecord.start(request);
        chainLog.save(chain);                                   // intent is durable before anything is touched

        // ---------------- step A: eligibility, reserves the right to the course
        EligibilityClearance clearance;
        try {
            clearance = eligibility.clear(chain.id(), request.studentId(), request.courseId(), request.term());
        } catch (EligibilityService.NotEligibleException refused) {
            chain.markRejected(Stage.A, describe(refused));      // nothing committed, nothing to undo
            chainLog.save(chain);
            throw RegistrationException.rejected(chain, Stage.A, refused);
        } catch (RuntimeException unexpected) {
            throw failAndCompensate(chain, Stage.A, unexpected);
        }
        chain.markClearanceHeld(clearance.id());
        chainLog.save(chain);                                   // PENDING_B

        // ---------------- step B: seating, holds a seat in the section
        SeatReservation seat;
        try {
            seat = seating.allocate(chain.id(), request.studentId(), request.courseId(), request.term());
        } catch (RuntimeException failure) {
            throw failAndCompensate(chain, Stage.B, failure);   // A committed -> A will be undone
        }
        chain.markSeatHeld(seat.id());
        chainLog.save(chain);                                   // PENDING_C

        // ---------------- step C: finalization, invoice + transcript entry
        Invoice invoice;
        try {
            invoice = finalization.finalizeRegistration(chain.id(), request.studentId(), request.courseId(),
                    request.term());
        } catch (RuntimeException failure) {
            throw failAndCompensate(chain, Stage.C, failure);   // A and B committed -> B, then A
        }

        // ---------------- confirm phase: the seat becomes final and the clearance is used up
        try {
            seating.confirm(chain.id());
            eligibility.consume(chain.id());
        } catch (RuntimeException failure) {
            throw failAndCompensate(chain, Stage.C, failure);   // C committed too -> C, then B, then A
        }
        chain.markCompleted(invoice.id());
        chainLog.save(chain);
        return new RegistrationReceipt(chain.id(), chain.studentId(), chain.courseId(), chain.term(),
                invoice.amount(), chain.status());
    }

    /**
     * Run once after every start-up. Looks at each chain the log shows as interrupted and at what the
     * subsystems really hold for it: if A, B and C all committed it rolls the chain forward to COMPLETED,
     * otherwise it rolls back whatever was committed.
     */
    public List<RecoveryReport> recoverInterruptedChains() {
        List<RecoveryReport> reports = new ArrayList<>();
        for (ChainRecord chain : chainLog.findInterrupted()) {
            ChainStatus before = chain.status();
            try {
                reports.add(recover(chain, before));
            } catch (RuntimeException failure) {
                reports.add(new RecoveryReport(chain.id(), before, chain.status(),
                        "recovery failed, will be retried next time: " + failure.getMessage()));
            }
        }
        return reports;
    }

    private RecoveryReport recover(ChainRecord chain, ChainStatus before) {
        String reference = chain.id();
        Optional<EligibilityClearance> clearance = eligibility.findByReference(reference)
                .filter(EligibilityClearance::isActive);
        Optional<SeatReservation> seat = seating.findByReference(reference)
                .filter(SeatReservation::occupiesSeat);
        Optional<Invoice> invoice = finalization.findInvoiceByReference(reference).filter(Invoice::isActive);

        if (before != ChainStatus.COMPENSATING && clearance.isPresent() && seat.isPresent() && invoice.isPresent()) {
            chain.recordClearance(clearance.get().id());
            chain.recordSeat(seat.get().id());
            seating.confirm(reference);
            eligibility.consume(reference);
            chain.markCompleted(invoice.get().id());
            chainLog.save(chain);
            return new RecoveryReport(reference, before, chain.status(),
                    "rolled forward: A, B and C had all committed; B confirmed, A consumed");
        }

        chain.beginCompensation(null, "interrupted before it completed; rolled back after the restart");
        chainLog.save(chain);
        List<Stage> undone = compensator.compensate(reference);
        chain.markCompensated(undone);
        chainLog.save(chain);
        return new RecoveryReport(reference, before, chain.status(),
                "rolled back: " + (undone.isEmpty() ? "nothing had been committed" : "undid " + undone));
    }

    // ------------------------------------------------------------------------------------ internals

    private RegistrationException failAndCompensate(ChainRecord chain, Stage stage, RuntimeException cause) {
        chain.beginCompensation(stage, describe(cause));
        chainLog.save(chain);                                   // COMPENSATING is durable before the first undo
        List<Stage> undone;
        try {
            undone = compensator.compensate(chain.id());
        } catch (RuntimeException compensationFailure) {
            return RegistrationException.compensationIncomplete(chain, stage, cause, compensationFailure);
        }
        chain.markCompensated(undone);
        chainLog.save(chain);
        return RegistrationException.compensated(chain, stage, cause, undone);
    }

    private static void validate(RegistrationRequest request) {
        if (request == null || !request.isComplete()) {
            throw RegistrationException.invalid("Student id, course id and term are all required");
        }
        if (request.studentId().length() > 32 || request.courseId().length() > 32 || request.term().length() > 16) {
            throw RegistrationException.invalid("Student id / course id (max 32) or term (max 16) is too long");
        }
    }

    private static String describe(RuntimeException failure) {
        if (failure instanceof EligibilityService.NotEligibleException e) {
            return e.reason() + ": " + e.getMessage();
        }
        if (failure instanceof SeatingService.NoSeatException e) {
            return e.reason() + ": " + e.getMessage();
        }
        if (failure instanceof FinalizationService.BillingRejectedException e) {
            return e.reason() + ": " + e.getMessage();
        }
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
