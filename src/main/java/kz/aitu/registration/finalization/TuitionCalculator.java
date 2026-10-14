package kz.aitu.registration.finalization;

/** Pure business logic: how much a course costs and whether the student's account can carry it. */
public class TuitionCalculator {

    public static final long DEFAULT_RATE_PER_CREDIT = 40_000L;

    private final long ratePerCredit;

    public TuitionCalculator() {
        this(DEFAULT_RATE_PER_CREDIT);
    }

    public TuitionCalculator(long ratePerCredit) {
        if (ratePerCredit <= 0) {
            throw new IllegalArgumentException("rate per credit must be positive");
        }
        this.ratePerCredit = ratePerCredit;
    }

    /** Credits times the rate, minus the scholarship share (0..100 percent). */
    public long tuition(int credits, int scholarshipPercent) {
        if (credits <= 0) {
            throw new IllegalArgumentException("credits must be positive");
        }
        if (scholarshipPercent < 0 || scholarshipPercent > 100) {
            throw new IllegalArgumentException("scholarship percent must be between 0 and 100");
        }
        return credits * ratePerCredit * (100 - scholarshipPercent) / 100;
    }

    /** The new charge fits when what is already owed plus the charge stays within the credit limit. */
    public boolean fitsLimit(long creditLimit, long alreadyOutstanding, long newCharge) {
        return alreadyOutstanding + newCharge <= creditLimit;
    }

    public long ratePerCredit() {
        return ratePerCredit;
    }
}
