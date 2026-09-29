package ru.lct.heatnetwork.api;

import org.springframework.http.HttpStatus;

/** Expected map API error with an explicit HTTP status and stable code. */
public final class MapViewException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public MapViewException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
