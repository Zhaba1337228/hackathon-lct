package ru.lct.heatnetwork.input;

public final class InputValidationException extends RuntimeException {
    private final String featureId;
    private final String fieldPath;

    public InputValidationException(String fieldPath, String message) {
        super(message);
        this.featureId = null;
        this.fieldPath = fieldPath;
    }

    public InputValidationException(String featureId, String fieldPath, String message) {
        super(message);
        this.featureId = featureId;
        this.fieldPath = fieldPath;
    }

    public InputValidationException(String fieldPath, String message, Throwable cause) {
        super(message, cause);
        this.featureId = null;
        this.fieldPath = fieldPath;
    }

    public String getFeatureId() { return featureId; }
    public String getFieldPath() { return fieldPath; }
}
