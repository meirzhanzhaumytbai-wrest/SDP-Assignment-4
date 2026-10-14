package kz.aitu.registration.facade;

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
