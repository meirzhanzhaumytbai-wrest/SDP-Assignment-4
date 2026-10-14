package kz.aitu.registration.facade;

/** The three steps of the registration chain. */
public enum Stage {
    A("eligibility"),
    B("seating"),
    C("finalization");

    private final String label;

    Stage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
