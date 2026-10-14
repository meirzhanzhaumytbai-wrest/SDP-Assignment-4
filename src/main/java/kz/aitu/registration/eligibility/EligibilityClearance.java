package kz.aitu.registration.eligibility;

import java.util.Objects;
import java.util.UUID;


public final class EligibilityClearance {

    public enum Status { CLEARED, CONSUMED, REVOKED }

    @SuppressWarnings("serial")

    public static class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(Status from, Status to) {
            super("Clearance cannot move from " + from + " to " + to);
        }
    }

    private final String id;
    private final String reference;
    private final String studentId;
    private final String courseId;
    private final String term;
    private Status status;

    private EligibilityClearance(String id, String reference, String studentId, String courseId,
                                 String term, Status status) {
        this.id = Objects.requireNonNull(id);
        this.reference = Objects.requireNonNull(reference);
        this.studentId = Objects.requireNonNull(studentId);
        this.courseId = Objects.requireNonNull(courseId);
        this.term = Objects.requireNonNull(term);
        this.status = Objects.requireNonNull(status);
    }

    /** A brand new clearance, always starts as CLEARED. */
    public static EligibilityClearance issue(String reference, String studentId, String courseId, String term) {
        return new EligibilityClearance(UUID.randomUUID().toString(), reference, studentId, courseId, term,
                Status.CLEARED);
    }

    /** Re-creates a clearance read back from the store (no rules are applied, the store is trusted). */
    public static EligibilityClearance restore(String id, String reference, String studentId, String courseId,
                                               String term, Status status) {
        return new EligibilityClearance(id, reference, studentId, courseId, term, status);
    }

    public void consume() {
        moveTo(Status.CONSUMED);
    }

    public void revoke() {
        moveTo(Status.REVOKED);
    }

    public boolean canMoveTo(Status target) {
        return isLegal(status, target);
    }

    /** CLEARED and CONSUMED both still occupy the student's right to the course. */
    public boolean isActive() {
        return status != Status.REVOKED;
    }

    private void moveTo(Status target) {
        if (!isLegal(status, target)) {
            throw new IllegalTransitionException(status, target);
        }
        status = target;
    }

    private static boolean isLegal(Status from, Status to) {
        return switch (from) {
            case CLEARED -> to == Status.CONSUMED || to == Status.REVOKED;
            case CONSUMED -> to == Status.REVOKED;
            case REVOKED -> false;
        };
    }

    public String id() { return id; }
    public String reference() { return reference; }
    public String studentId() { return studentId; }
    public String courseId() { return courseId; }
    public String term() { return term; }
    public Status status() { return status; }

    @Override
    public String toString() {
        return "Clearance[" + studentId + " -> " + courseId + " / " + term + ", " + status + "]";
    }
}
