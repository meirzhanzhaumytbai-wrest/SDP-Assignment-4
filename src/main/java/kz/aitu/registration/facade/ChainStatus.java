package kz.aitu.registration.facade;


public enum ChainStatus {
    STARTED, PENDING_B, PENDING_C, COMPLETED, COMPENSATING, COMPENSATED, REJECTED;

    public boolean isInterrupted() {
        return this == STARTED || this == PENDING_B || this == PENDING_C || this == COMPENSATING;
    }
}
