package kz.aitu.registration.finalization;

import java.util.Objects;
import java.util.UUID;


public final class Invoice {

    public enum Status { ISSUED, PAID, VOIDED }

    @SuppressWarnings("serial")

    public static class IllegalTransitionException extends IllegalStateException {
        public IllegalTransitionException(Status from, Status to) {
            super("Invoice cannot move from " + from + " to " + to);
        }
    }

    private final String id;
    private final String reference;
    private final String studentId;
    private final String courseId;
    private final String term;
    private final long amount;
    private Status status;

    private Invoice(String id, String reference, String studentId, String courseId, String term, long amount,
                    Status status) {
        this.id = Objects.requireNonNull(id);
        this.reference = Objects.requireNonNull(reference);
        this.studentId = Objects.requireNonNull(studentId);
        this.courseId = Objects.requireNonNull(courseId);
        this.term = Objects.requireNonNull(term);
        if (amount < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        this.amount = amount;
        this.status = Objects.requireNonNull(status);
    }

    public static Invoice issue(String reference, String studentId, String courseId, String term, long amount) {
        return new Invoice(UUID.randomUUID().toString(), reference, studentId, courseId, term, amount, Status.ISSUED);
    }

    public static Invoice restore(String id, String reference, String studentId, String courseId, String term,
                                  long amount, Status status) {
        return new Invoice(id, reference, studentId, courseId, term, amount, status);
    }

    public void pay() {
        moveTo(Status.PAID);
    }

    public void voidInvoice() {
        moveTo(Status.VOIDED);
    }

    public boolean canMoveTo(Status target) {
        return isLegal(status, target);
    }

    public boolean isOutstanding() {
        return status == Status.ISSUED;
    }

    public boolean isActive() {
        return status != Status.VOIDED;
    }

    private void moveTo(Status target) {
        if (!isLegal(status, target)) {
            throw new IllegalTransitionException(status, target);
        }
        status = target;
    }

    private static boolean isLegal(Status from, Status to) {
        return switch (from) {
            case ISSUED -> to == Status.PAID || to == Status.VOIDED;
            case PAID -> to == Status.VOIDED;
            case VOIDED -> false;
        };
    }

    public String id() { return id; }
    public String reference() { return reference; }
    public String studentId() { return studentId; }
    public String courseId() { return courseId; }
    public String term() { return term; }
    public long amount() { return amount; }
    public Status status() { return status; }

    @Override
    public String toString() {
        return "Invoice[" + studentId + " " + courseId + "/" + term + " " + amount + " KZT, " + status + "]";
    }
}
