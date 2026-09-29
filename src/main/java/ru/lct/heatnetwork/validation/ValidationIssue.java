package ru.lct.heatnetwork.validation;

/** A localized, machine-readable validation finding. */
public final class ValidationIssue {
    public enum Severity { ERROR, WARNING }
    private final String code;
    private final Severity severity;
    private final String elementId;
    private final String message;

    public ValidationIssue(String code, Severity severity, String elementId, String message) {
        if (code == null || severity == null || message == null) throw new IllegalArgumentException("Issue fields are required");
        this.code = code;
        this.severity = severity;
        this.elementId = elementId;
        this.message = message;
    }
    public String getCode() { return code; }
    public Severity getSeverity() { return severity; }
    public String getElementId() { return elementId; }
    public String getMessage() { return message; }
}
