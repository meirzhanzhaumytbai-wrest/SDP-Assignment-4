package kz.aitu.registration.eligibility;

/**
 * Pure business logic: a student whose overdue debt is strictly above the threshold has a financial hold
 * and may not register for anything. (Amounts are in tenge.)
 */
public class FinancialHoldChecker {

    public static final long DEFAULT_THRESHOLD = 100_000L;

    private final long threshold;

    public FinancialHoldChecker() {
        this(DEFAULT_THRESHOLD);
    }

    public FinancialHoldChecker(long threshold) {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must not be negative");
        }
        this.threshold = threshold;
    }

    public boolean hasHold(long overdueDebt) {
        return overdueDebt > threshold;
    }

    public long threshold() {
        return threshold;
    }
}
