package kz.aitu.registration.eligibility;

import kz.aitu.registration.eligibility.EligibilityRepository.StudentStanding;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;


public class EligibilityService implements AutoCloseable {

    @SuppressWarnings("serial")

    public static class NotEligibleException extends RuntimeException {
        public enum Reason { UNKNOWN_STUDENT, UNKNOWN_COURSE, PREREQUISITE_MISSING, FINANCIAL_HOLD, ALREADY_CLEARED }

        private final Reason reason;

        public NotEligibleException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final EligibilityRepository repository;
    private final PrerequisiteChecker prerequisiteChecker;
    private final FinancialHoldChecker holdChecker;

    public EligibilityService(EligibilityRepository repository) {
        this(repository, new PrerequisiteChecker(), new FinancialHoldChecker());
    }

    public EligibilityService(EligibilityRepository repository, PrerequisiteChecker prerequisiteChecker,
                              FinancialHoldChecker holdChecker) {
        this.repository = repository;
        this.prerequisiteChecker = prerequisiteChecker;
        this.holdChecker = holdChecker;
    }

    /** Convenience for standalone use: opens the store at the given file. */
    public static EligibilityService openAt(Path databaseFile) {
        return new EligibilityService(new EligibilityRepository(databaseFile));
    }

    // ---------------------------------------------------------------- the operations of the subsystem

    /**
     * Checks eligibility and, if the student passes, persists a CLEARED clearance for the reference.
     * Calling it again with the same reference returns the clearance that already exists.
     */
    public EligibilityClearance clear(String reference, String studentId, String courseId, String term) {
        Optional<EligibilityClearance> existing = repository.findClearanceByReference(reference);
        if (existing.isPresent()) {
            return existing.get();
        }
        StudentStanding standing = repository.findStanding(studentId).orElseThrow(() ->
                new NotEligibleException(NotEligibleException.Reason.UNKNOWN_STUDENT,
                        "Unknown student " + studentId));
        Set<String> required = repository.findPrerequisites(courseId).orElseThrow(() ->
                new NotEligibleException(NotEligibleException.Reason.UNKNOWN_COURSE,
                        "Unknown course " + courseId));

        Set<String> missing = prerequisiteChecker.missing(standing.completedCourses(), required);
        if (!missing.isEmpty()) {
            throw new NotEligibleException(NotEligibleException.Reason.PREREQUISITE_MISSING,
                    "Missing prerequisites for " + courseId + ": " + String.join(", ", missing));
        }
        if (holdChecker.hasHold(standing.overdueDebt())) {
            throw new NotEligibleException(NotEligibleException.Reason.FINANCIAL_HOLD,
                    "Financial hold: overdue debt " + standing.overdueDebt() + " KZT exceeds "
                            + holdChecker.threshold() + " KZT");
        }
        if (repository.existsActiveClearance(studentId, courseId, term)) {
            throw new NotEligibleException(NotEligibleException.Reason.ALREADY_CLEARED,
                    studentId + " already holds a clearance for " + courseId + " in " + term);
        }
        EligibilityClearance clearance = EligibilityClearance.issue(reference, studentId, courseId, term);
        repository.insertClearance(clearance);
        return clearance;
    }

    /** The clearance was used by a completed registration (CLEARED -> CONSUMED). Idempotent. */
    public EligibilityClearance consume(String reference) {
        EligibilityClearance clearance = repository.findClearanceByReference(reference).orElseThrow(() ->
                new IllegalStateException("No clearance for reference " + reference));
        if (clearance.status() == EligibilityClearance.Status.CONSUMED) {
            return clearance;
        }
        clearance.consume();
        repository.updateClearanceStatus(clearance);
        return clearance;
    }

    /**
     * Undoes the clearance of a reference (compensation). Idempotent.
     *
     * @return true if something was actually revoked, false if there was nothing to undo
     */
    public boolean revoke(String reference) {
        Optional<EligibilityClearance> found = repository.findClearanceByReference(reference);
        if (found.isEmpty() || !found.get().isActive()) {
            return false;
        }
        EligibilityClearance clearance = found.get();
        clearance.revoke();
        repository.updateClearanceStatus(clearance);
        return true;
    }

    // ---------------------------------------------------------------- queries / administration

    public Optional<EligibilityClearance> findByReference(String reference) {
        return repository.findClearanceByReference(reference);
    }

    public List<EligibilityClearance> findAll() {
        return repository.findAllClearances();
    }

    public void registerStudent(String studentId, String fullName, Set<String> completedCourses, long overdueDebt) {
        repository.saveStanding(new StudentStanding(studentId, fullName, completedCourses, overdueDebt));
    }

    public void defineCourse(String courseId, Set<String> prerequisites) {
        repository.defineCourse(courseId, prerequisites);
    }

    public void setOverdueDebt(String studentId, long overdueDebt) {
        StudentStanding standing = repository.findStanding(studentId).orElseThrow(() ->
                new IllegalArgumentException("Unknown student " + studentId));
        repository.saveStanding(new StudentStanding(standing.studentId(), standing.fullName(),
                standing.completedCourses(), overdueDebt));
    }

    public Optional<StudentStanding> findStanding(String studentId) {
        return repository.findStanding(studentId);
    }

    public List<StudentStanding> allStandings() {
        return repository.findAllStandings();
    }

    public void resetAllData() {
        repository.clearAll();
    }

    @Override
    public void close() {
        repository.close();
    }
}
