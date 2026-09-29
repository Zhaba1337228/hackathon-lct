package ru.lct.heatnetwork.jobs;

public final class JobCalculationException extends RuntimeException {
    public enum Kind { INVALID_REQUEST, STATE_CONFLICT, QUEUE_FULL, RESULT_UNAVAILABLE, RESULT_EXPIRED }

    private final Kind kind;

    public JobCalculationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
