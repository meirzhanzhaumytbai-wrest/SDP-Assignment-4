package kz.aitu.registration.facade;

import java.util.List;
import java.util.stream.Collectors;

/** Everything that can go wrong with a registration, as seen by the client of the facade. */
@SuppressWarnings("serial")
public class RegistrationException extends RuntimeException {

    public enum Kind {
        /** The request itself was unusable; nothing was started. */
        INVALID_REQUEST,
        /** Step A refused the request; nothing had been committed, so nothing needed undoing. */
        REJECTED,
        /** Step B or C failed after earlier steps had committed; the committed part was undone. */
        COMPENSATED,
        /** The undo itself failed half way; the chain stays COMPENSATING and is finished on recovery. */
        COMPENSATION_INCOMPLETE
    }

    private final Kind kind;
    private final String chainId;
    private final Stage failedStage;
    private final List<Stage> undone;

    private RegistrationException(Kind kind, String message, String chainId, Stage failedStage,
                                  List<Stage> undone, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.chainId = chainId;
        this.failedStage = failedStage;
        this.undone = List.copyOf(undone);
    }

    static RegistrationException invalid(String message) {
        return new RegistrationException(Kind.INVALID_REQUEST, message, null, null, List.of(), null);
    }

    static RegistrationException rejected(ChainRecord chain, Stage stage, Throwable cause) {
        return new RegistrationException(Kind.REJECTED,
                "Rejected at step " + stage + " (" + stage.label() + "): " + chain.failureReason()
                        + ". Nothing had been committed.", chain.id(), stage, List.of(), cause);
    }

    static RegistrationException compensated(ChainRecord chain, Stage stage, Throwable cause, List<Stage> undone) {
        return new RegistrationException(Kind.COMPENSATED,
                "Failed at step " + stage + " (" + stage.label() + "): " + chain.failureReason()
                        + ". Undone, in this order: " + describe(undone), chain.id(), stage, undone, cause);
    }

    static RegistrationException compensationIncomplete(ChainRecord chain, Stage stage, Throwable cause,
                                                        Throwable compensationFailure) {
        RegistrationException e = new RegistrationException(Kind.COMPENSATION_INCOMPLETE,
                "Failed at step " + stage + " (" + stage.label() + ") and the undo did not finish ("
                        + compensationFailure.getMessage() + "). The chain stays COMPENSATING and "
                        + "will be finished by recovery.", chain.id(), stage, List.of(), cause);
        e.addSuppressed(compensationFailure);
        return e;
    }

    private static String describe(List<Stage> steps) {
        return steps.isEmpty() ? "(nothing)" : steps.stream().map(Stage::name).collect(Collectors.joining(" then "));
    }

    public Kind kind() { return kind; }
    public String chainId() { return chainId; }
    public Stage failedStage() { return failedStage; }
    /** The steps that were undone, in the order they were undone. */
    public List<Stage> undone() { return undone; }
}
