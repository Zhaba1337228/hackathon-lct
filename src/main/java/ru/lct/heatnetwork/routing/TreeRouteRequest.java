package ru.lct.heatnetwork.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.network.RouteTreeOptimizer;
import ru.lct.heatnetwork.spatial.FeatureIndex;

/** A target-to-accepted-tree search; the new tree is never represented as an input heat network. */
public final class TreeRouteRequest {
    private final String variantId;
    private final FeatureIndex.IndexedFeature target;
    private final Point goal;
    private final Coordinate goalForward;
    private final String goalNodeId;
    private final List<LineString> treeSegments;
    private final List<FeatureIndex.IndexedFeature> inputs;
    private final Envelope searchArea;
    private final int maximumVisibilityVertices;
    private final Point mandatoryVia;

    public TreeRouteRequest(String variantId, FeatureIndex.IndexedFeature target,
            RouteTreeOptimizer.TreeCandidate tree, String trunkSegmentId, Point goal,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea, int maximumVisibilityVertices) {
        this(variantId, target, tree, trunkSegmentId, null, goal, inputs, searchArea,
                maximumVisibilityVertices, null);
    }

    public TreeRouteRequest(String variantId, FeatureIndex.IndexedFeature target,
            RouteTreeOptimizer.TreeCandidate tree, String trunkSegmentId, Point goal,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea, int maximumVisibilityVertices,
            Point mandatoryVia) {
        this(variantId, target, tree, trunkSegmentId, null, goal, inputs, searchArea,
                maximumVisibilityVertices, mandatoryVia);
    }

    public static TreeRouteRequest toChamber(String variantId, FeatureIndex.IndexedFeature target,
            RouteTreeOptimizer.TreeCandidate tree, String chamberNodeId,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea, int maximumVisibilityVertices) {
        if (tree == null || chamberNodeId == null) {
            throw new IllegalArgumentException("Accepted tree and chamber ID are required");
        }
        Variant.Node chamber = tree.getVariant().getNodes().stream()
                .filter(node -> chamberNodeId.equals(node.getId()) && "heat_chamber".equals(node.getType()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected tree chamber is missing"));
        org.locationtech.jts.geom.GeometryFactory factory = new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), 32637);
        Point goal = factory.createPoint(new Coordinate(chamber.getX(), chamber.getY()));
        return new TreeRouteRequest(variantId, target, tree, null, chamberNodeId, goal, inputs,
                searchArea, maximumVisibilityVertices, null);
    }

    private TreeRouteRequest(String variantId, FeatureIndex.IndexedFeature target,
            RouteTreeOptimizer.TreeCandidate tree, String trunkSegmentId, String chamberNodeId, Point goal,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea, int maximumVisibilityVertices,
            Point mandatoryVia) {
        if (variantId == null || variantId.trim().isEmpty() || target == null || tree == null
                || !tree.getValidation().isAcceptable()
                || (trunkSegmentId == null) == (chamberNodeId == null)
                || goal == null || goal.isEmpty()
                || goal.getSRID() != 32637 || inputs == null || searchArea == null
                || searchArea.isNull() || maximumVisibilityVertices < 2) {
            throw new IllegalArgumentException("Tree route requires a valid target, accepted tree and projected goal");
        }
        if (mandatoryVia != null && (mandatoryVia.isEmpty() || mandatoryVia.getSRID() != 32637
                || !Double.isFinite(mandatoryVia.getX()) || !Double.isFinite(mandatoryVia.getY())
                || !searchArea.covers(mandatoryVia.getCoordinate()))) {
            throw new IllegalArgumentException("Mandatory corridor point must be projected and inside the search area");
        }
        this.variantId = variantId;
        this.target = target;
        this.goal = (Point) goal.copy();
        this.goalNodeId = chamberNodeId;
        if (chamberNodeId != null) {
            long incident = tree.getVariant().getSegments().stream().filter(segment ->
                    chamberNodeId.equals(segment.getStartNodeId())
                            || chamberNodeId.equals(segment.getEndNodeId())).count();
            if (incident >= 4 || incident == 0) {
                throw new IllegalArgumentException("Tree chamber has no free connection");
            }
            Variant.Segment sourceward = tree.getVariant().getSegments().stream()
                    .filter(segment -> chamberNodeId.equals(segment.getFlowFromNodeId()))
                    .findFirst().orElse(null);
            if (sourceward == null) {
                this.goalForward = null;
            } else {
                LineString line = sourceward.getGeometry();
                Coordinate next = chamberNodeId.equals(sourceward.getStartNodeId())
                        ? line.getCoordinateN(1) : line.getCoordinateN(line.getNumPoints() - 2);
                this.goalForward = new Coordinate(next.x - goal.getX(), next.y - goal.getY());
            }
        } else {
            Variant.Segment trunk = tree.getVariant().getSegments().stream()
                    .filter(segment -> trunkSegmentId.equals(segment.getId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected tree trunk is missing"));
            LineString trunkLine = trunk.getGeometry();
            if (trunkLine == null || trunkLine.getSRID() != 32637
                    || !"base".equals(trunk.getLayingMethod()) || !trunk.getRestrictionIds().isEmpty()
                    || !trunk.getStartNodeId().equals(trunk.getFlowFromNodeId())
                    || !trunk.getEndNodeId().equals(trunk.getFlowToNodeId())
                    || trunkLine.distance(goal) > 1e-6) {
                throw new IllegalArgumentException("Tree goal requires a projected base trunk with sourceward flow");
            }
            LengthIndexedLine indexed = new LengthIndexedLine(trunkLine);
            double measure = indexed.project(goal.getCoordinate());
            if (measure < 0.25 || trunkLine.getLength() - measure < 0.25) {
                throw new IllegalArgumentException("Tree goal must be internal to the selected trunk");
            }
            LineString sourceward = (LineString) indexed.extractLine(measure, trunkLine.getLength());
            Coordinate forwardPoint = sourceward.getCoordinateN(1);
            this.goalForward = new Coordinate(forwardPoint.x - goal.getX(), forwardPoint.y - goal.getY());
        }
        List<LineString> segments = new ArrayList<>();
        for (Variant.Segment segment : tree.getVariant().getSegments()) {
            if (segment.getGeometry() == null || segment.getGeometry().getSRID() != 32637) {
                throw new IllegalArgumentException("Accepted tree contains an invalid projected segment");
            }
            segments.add((LineString) segment.getGeometry().copy());
        }
        this.treeSegments = Collections.unmodifiableList(segments);
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        this.searchArea = new Envelope(searchArea);
        this.maximumVisibilityVertices = maximumVisibilityVertices;
        this.mandatoryVia = mandatoryVia == null ? null : (Point) mandatoryVia.copy();
    }

    public String getVariantId() { return variantId; }
    public FeatureIndex.IndexedFeature getTarget() { return target; }
    public Point getTargetPoint() {
        if (!(target.getGeometry() instanceof Point)) throw new IllegalArgumentException("Target geometry must be a Point");
        return (Point) target.getGeometry();
    }
    public Point getGoalPoint() { return (Point) goal.copy(); }
    public Coordinate getGoalForward() { return goalForward == null ? null : new Coordinate(goalForward); }
    public String getGoalNodeId() { return goalNodeId; }
    public List<LineString> getTreeSegments() { return treeSegments; }
    public List<FeatureIndex.IndexedFeature> getInputs() { return inputs; }
    public Envelope getSearchArea() { return new Envelope(searchArea); }
    public int getMaximumVisibilityVertices() { return maximumVisibilityVertices; }
    public Point getMandatoryVia() { return mandatoryVia == null ? null : (Point) mandatoryVia.copy(); }
}
