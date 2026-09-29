package ru.lct.heatnetwork.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fail-closed result: a variant is acceptable only when it has no errors and every required check ran. */
public final class ValidationReport {
    private final List<ValidationIssue> issues;
    private final List<String> checksNotRun;

    public ValidationReport(List<ValidationIssue> issues, List<String> checksNotRun) {
        this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        this.checksNotRun = Collections.unmodifiableList(new ArrayList<>(checksNotRun));
    }
    public List<ValidationIssue> getIssues() { return issues; }
    public List<String> getChecksNotRun() { return checksNotRun; }
    public boolean isValid() { return issues.stream().noneMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR); }
    public boolean isComplete() { return checksNotRun.isEmpty(); }
    public boolean isAcceptable() { return isValid() && isComplete(); }
}
