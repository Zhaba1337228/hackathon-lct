package ru.lct.heatnetwork.input;

public final class DuplicateInputIdException extends RuntimeException {
    public DuplicateInputIdException() {
        super("Duplicate typed ID");
    }
}
