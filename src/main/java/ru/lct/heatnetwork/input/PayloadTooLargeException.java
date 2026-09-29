package ru.lct.heatnetwork.input;

public final class PayloadTooLargeException extends RuntimeException {
    private final long limitBytes;

    public PayloadTooLargeException(long limitBytes) {
        super("Input exceeds the configured byte limit");
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() { return limitBytes; }
}
