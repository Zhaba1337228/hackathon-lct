package ru.lct.heatnetwork.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.math.BigDecimal;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.spatial.FeatureIndex;

/** One target, one attachment choice and the local projected input snapshot for a bounded search. */
public final class RouteRequest {
    private final String variantId;
    private final FeatureIndex.IndexedFeature target;
    private final AttachmentCandidate attachment;
    private final List<FeatureIndex.IndexedFeature> inputs;
    private final Envelope searchArea;
    private final int maximumVisibilityVertices;
    private final Point mandatoryVia;
    private final BigDecimal plannedSectionFlowTph;

    public RouteRequest(String variantId, FeatureIndex.IndexedFeature target, AttachmentCandidate attachment,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea, int maximumVisibilityVertices) {
        this(variantId, target, attachment, inputs, searchArea, maximumVisibilityVertices, null);
    }

    public RouteRequest(String variantId, FeatureIndex.IndexedFeature target, AttachmentCandidate attachment,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea, int maximumVisibilityVertices,
            Point mandatoryVia) {
        this(variantId, target, attachment, inputs, searchArea, maximumVisibilityVertices, mandatoryVia, null);
    }

    public RouteRequest(String variantId, FeatureIndex.IndexedFeature target, AttachmentCandidate attachment,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea, int maximumVisibilityVertices,
            Point mandatoryVia, BigDecimal plannedSectionFlowTph) {
        if (variantId == null || variantId.trim().isEmpty() || target == null || attachment == null
                || inputs == null || searchArea == null || searchArea.isNull() || maximumVisibilityVertices < 2) {
            throw new IllegalArgumentException("Route request requires a target, attachment, projected context and search limits");
        }
        if (mandatoryVia != null && (mandatoryVia.isEmpty() || mandatoryVia.getSRID() != 32637
                || !Double.isFinite(mandatoryVia.getX()) || !Double.isFinite(mandatoryVia.getY())
                || !searchArea.covers(mandatoryVia.getCoordinate()))) {
            throw new IllegalArgumentException("Mandatory corridor point must be projected and inside the search area");
        }
        if (plannedSectionFlowTph != null && plannedSectionFlowTph.signum() <= 0) {
            throw new IllegalArgumentException("Planned section flow must be positive");
        }
        this.variantId = variantId;
        this.target = target;
        this.attachment = attachment;
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        this.searchArea = new Envelope(searchArea);
        this.maximumVisibilityVertices = maximumVisibilityVertices;
        this.mandatoryVia = mandatoryVia == null ? null : (Point) mandatoryVia.copy();
        this.plannedSectionFlowTph = plannedSectionFlowTph;
    }

    public String getVariantId() { return variantId; }
    public FeatureIndex.IndexedFeature getTarget() { return target; }
    public AttachmentCandidate getAttachment() { return attachment; }
    public List<FeatureIndex.IndexedFeature> getInputs() { return inputs; }
    public Envelope getSearchArea() { return new Envelope(searchArea); }
    public int getMaximumVisibilityVertices() { return maximumVisibilityVertices; }
    public Point getMandatoryVia() { return mandatoryVia == null ? null : (Point) mandatoryVia.copy(); }
    public BigDecimal getPlannedSectionFlowTph() { return plannedSectionFlowTph; }

    public Point getTargetPoint() {
        if (!(target.getGeometry() instanceof Point)) throw new IllegalArgumentException("Target geometry must be a Point");
        return (Point) target.getGeometry();
    }
}
