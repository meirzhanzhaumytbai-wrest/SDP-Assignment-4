package kz.aitu.registration.facade;

import kz.aitu.registration.eligibility.EligibilityRepository.StudentStanding;
import kz.aitu.registration.eligibility.EligibilityService;
import kz.aitu.registration.finalization.FinalizationRepository.Account;
import kz.aitu.registration.finalization.FinalizationService;
import kz.aitu.registration.finalization.TranscriptEntry;
import kz.aitu.registration.seating.SeatingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Facade 2: the narrow administrative workflow. Inspect what the three subsystems really hold for a
 * chain, compensate a chain by hand, and stage situations (full section, low credit limit, closed billing
 * office) so a failure can be provoked on demand.
 */
public class RegistrationAdminFacade {

    private final EligibilityService eligibility;
    private final SeatingService seating;
    private final FinalizationService finalization;
    private final ChainLogRepository chainLog;
    private final ChainCompensator compensator;

    public RegistrationAdminFacade(EligibilityService eligibility, SeatingService seating,
                                   FinalizationService finalization, ChainLogRepository chainLog) {
        this.eligibility = eligibility;
        this.seating = seating;
        this.finalization = finalization;
        this.chainLog = chainLog;
        this.compensator = new ChainCompensator(eligibility, seating, finalization);
    }

    // ------------------------------------------------------------------------------ inspection

    public List<ChainRecord> listChains() {
        return chainLog.findAll();
    }

    /** @param chainIdOrPrefix the full chain id, or any prefix that identifies exactly one chain */
    public ChainInspection inspect(String chainIdOrPrefix) {
        ChainRecord chain = resolve(chainIdOrPrefix);
        String id = chain.id();
        return new ChainInspection(chain,
                eligibility.findByReference(id).map(c -> c.status().name()).orElse("-"),
                seating.findByReference(id).map(s -> s.status().name()).orElse("-"),
                finalization.findInvoiceByReference(id).map(i -> i.status().name()).orElse("-"),
                finalization.findTranscriptByReference(id).map(t -> t.status().name()).orElse("-"));
    }

    public List<StudentOverview> listStudents() {
        List<StudentOverview> students = new ArrayList<>();
        for (StudentStanding standing : eligibility.allStandings()) {
            Optional<Account> account = finalization.findAccount(standing.studentId());
            students.add(new StudentOverview(standing.studentId(), standing.fullName(),
                    String.join(", ", standing.completedCourses()), standing.overdueDebt(),
                    account.map(Account::creditLimit).orElse(0L),
                    account.map(Account::scholarshipPercent).orElse(0),
                    finalization.outstandingOf(standing.studentId())));
        }
        return students;
    }

    public List<SectionOverview> listSections() {
        return seating.allSections().stream()
                .map(s -> new SectionOverview(s.courseId(), s.term(), s.capacity(),
                        seating.seatsInUse(s.courseId(), s.term()), s.open()))
                .toList();
    }

    public List<String> transcriptOf(String studentId) {
        return finalization.transcriptOf(studentId).stream()
                .map(TranscriptEntry::toString)
                .toList();
    }

    // ------------------------------------------------------------------------------ manual compensation

    /**
     * Undoes whatever the three subsystems hold for the chain: C, then B, then A. Works for a stuck chain
     * and for a COMPLETED one (a cancelled registration). Repeating it on a chain that is already
     * COMPENSATING simply finishes the job.
     *
     * @throws IllegalStateException if the chain is REJECTED or already COMPENSATED
     */
    public ChainRecord compensate(String chainIdOrPrefix) {
        ChainRecord chain = resolve(chainIdOrPrefix);
        chain.beginCompensation(null, "manual compensation by an administrator");
        chainLog.save(chain);
        List<Stage> undone = compensator.compensate(chain.id());
        chain.markCompensated(undone);
        chainLog.save(chain);
        return chain;
    }

    // ------------------------------------------------------------------------------ staging scenarios

    public void setSectionCapacity(String courseId, String term, int capacity) {
        seating.setCapacity(courseId, term, capacity);
    }

    public void setSectionOpen(String courseId, String term, boolean open) {
        seating.setOpen(courseId, term, open);
    }

    public void setCreditLimit(String studentId, long creditLimit) {
        finalization.setCreditLimit(studentId, creditLimit);
    }

    public void setOverdueDebt(String studentId, long overdueDebt) {
        eligibility.setOverdueDebt(studentId, overdueDebt);
    }

    public void setBillingWindowOpen(boolean open) {
        finalization.setBillingWindowOpen(open);
    }

    public boolean isBillingWindowOpen() {
        return finalization.isBillingWindowOpen();
    }

    private ChainRecord resolve(String chainIdOrPrefix) {
        if (chainIdOrPrefix == null || chainIdOrPrefix.isBlank()) {
            throw new IllegalArgumentException("A chain id is required");
        }
        String wanted = chainIdOrPrefix.trim();
        Optional<ChainRecord> exact = chainLog.find(wanted);
        if (exact.isPresent()) {
            return exact.get();
        }
        List<ChainRecord> matches = chainLog.findAll().stream()
                .filter(c -> c.id().startsWith(wanted))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        throw new IllegalArgumentException(matches.isEmpty()
                ? "Unknown chain " + wanted
                : "Chain prefix " + wanted + " is ambiguous (" + matches.size() + " matches)");
    }
}
