package kz.aitu.registration.facade;

/**
 * Progress of one registration chain, persisted in the facade's own store so that it survives a restart.
 *
 * <pre>
 *   STARTED     intent logged, nothing is known to be committed yet
 *   PENDING_B   A committed, B is next
 *   PENDING_C   A and B committed, C is next
 *   COMPLETED   A, B, C committed and confirmed
 *   COMPENSATING an undo is in progress (a crash here is finished after the restart)
 *   COMPENSATED  everything that had been committed is undone
 *   REJECTED     failed at the very first step, nothing had been committed
 * </pre>
 */
public enum ChainStatus {
    STARTED, PENDING_B, PENDING_C, COMPLETED, COMPENSATING, COMPENSATED, REJECTED;

    /** True if the chain was interrupted and needs a decision after a restart. */
    public boolean isInterrupted() {
        return this == STARTED || this == PENDING_B || this == PENDING_C || this == COMPENSATING;
    }
}
