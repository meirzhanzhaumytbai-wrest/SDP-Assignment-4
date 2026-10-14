package kz.aitu.registration.finalization;

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

    public long tuition(int credits, int scholarshipPercent) {
        if (credits <= 0) {
            throw new IllegalArgumentException("credits must be positive");
        }
        if (scholarshipPercent < 0 || scholarshipPercent > 100) {
            throw new IllegalArgumentException("scholarship percent must be between 0 and 100");
        }
        return credits * ratePerCredit * (100 - scholarshipPercent) / 100;
    }

    public boolean fitsLimit(long creditLimit, long alreadyOutstanding, long newCharge) {
        return alreadyOutstanding + newCharge <= creditLimit;
    }

    public long ratePerCredit() {
        return ratePerCredit;
    }
}
