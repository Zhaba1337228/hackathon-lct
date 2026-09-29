package ru.lct.heatnetwork.output;

import java.io.IOException;

/** Raised before an export write would exceed the configured byte limit. */
public final class GeoJsonExportTooLargeException extends IOException {
    private final long limitBytes;

    public GeoJsonExportTooLargeException(long limitBytes) {
        super("GeoJSON export exceeds the configured " + limitBytes + " byte limit");
        this.limitBytes = limitBytes;
    }

    public long getLimitBytes() { return limitBytes; }
}
