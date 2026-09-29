package ru.lct.heatnetwork.routing;

import org.locationtech.jts.geom.LineString;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.validation.ValidationReport;

/** Explicit result of one bounded single-target search. */
public final class RouteCandidate {
    public enum Status {
        FOUND,
        FOUND_WITH_INCOMPLETE_SEARCH,
        NO_ROUTE_WITHIN_SEARCH_AREA,
        VISIBILITY_VERTEX_LIMIT_REACHED,
        SEARCH_INCOMPLETE,
        SEED_SEARCH_SKIPPED,
        NO_FEASIBLE_DIAMETER,
        INVALID_REQUEST,
        VALIDATION_REJECTED,
        TREE_BRANCH_ACCEPTED,
        TREE_BRANCH_REJECTED
    }

    private final Status status;
    private final LineString geometry;
    private final Variant variant;
    private final ValidationReport validation;
    private final String detail;

    private RouteCandidate(Status status, LineString geometry, Variant variant,
            ValidationReport validation, String detail) {
        this.status = status;
        this.geometry = geometry == null ? null : (LineString) geometry.copy();
        this.variant = variant;
        this.validation = validation;
        this.detail = detail;
    }

    static RouteCandidate found(LineString geometry, Variant variant, ValidationReport validation,
            boolean searchComplete) {
        return new RouteCandidate(searchComplete ? Status.FOUND : Status.FOUND_WITH_INCOMPLETE_SEARCH,
                geometry, variant, validation, searchComplete ? null
                        : "A valid route was found, but the bounded visibility search was not exhaustive");
    }

    static RouteCandidate failure(Status status, String detail) {
        if (status == Status.FOUND || status == Status.FOUND_WITH_INCOMPLETE_SEARCH) {
            throw new IllegalArgumentException("Use found() for successful routes");
        }
        return new RouteCandidate(status, null, null, null, detail);
    }

    static RouteCandidate rejected(LineString geometry, Variant variant, ValidationReport validation) {
        return new RouteCandidate(Status.VALIDATION_REJECTED, geometry, variant, validation,
                "Candidate failed the independent engineering validator");
    }

    public Status getStatus() { return status; }
    public boolean isFound() {
        return status == Status.FOUND || status == Status.FOUND_WITH_INCOMPLETE_SEARCH;
    }
    public LineString getGeometry() { return geometry == null ? null : (LineString) geometry.copy(); }
    public Variant getVariant() { return variant; }
    public ValidationReport getValidation() { return validation; }
    public String getDetail() { return detail; }
}
