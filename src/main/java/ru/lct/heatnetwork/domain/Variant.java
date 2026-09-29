package ru.lct.heatnetwork.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.math.BigDecimal;
import org.locationtech.jts.geom.LineString;

/** One candidate network graph. Generated string IDs are stable within this variant. */
public final class Variant {
    private final String id;
    private final List<Node> nodes;
    private final List<Segment> segments;

    public Variant(String id, List<Node> nodes, List<Segment> segments) {
        this.id = Objects.requireNonNull(id, "id");
        this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        validateReferences();
    }

    private void validateReferences() {
        Set<String> nodeIds = new HashSet<>();
        Set<String> allIds = new HashSet<>();
        for (Node node : nodes) {
            if (!nodeIds.add(node.getId()) || !allIds.add(node.getId())) {
                throw new IllegalArgumentException("Duplicate variant element id: " + node.getId());
            }
        }
        for (Segment segment : segments) {
            if (!allIds.add(segment.getId())) {
                throw new IllegalArgumentException("Duplicate variant element id: " + segment.getId());
            }
            if (!nodeIds.contains(segment.getStartNodeId()) || !nodeIds.contains(segment.getEndNodeId())) {
                throw new IllegalArgumentException("Segment references a missing node: " + segment.getId());
            }
        }
    }

    public String getId() { return id; }
    public List<Node> getNodes() { return nodes; }
    public List<Segment> getSegments() { return segments; }

    public static final class Node {
        private final String id;
        private final double x;
        private final double y;
        private final String type;
        private final TypedId inputFeatureId;

        public Node(String id, double x, double y, String type) {
            this(id, x, y, type, null);
        }

        public Node(String id, double x, double y, String type, TypedId inputFeatureId) {
            this.id = Objects.requireNonNull(id, "id");
            this.x = x;
            this.y = y;
            this.type = Objects.requireNonNull(type, "type");
            this.inputFeatureId = inputFeatureId;
        }

        public String getId() { return id; }
        public double getX() { return x; }
        public double getY() { return y; }
        public String getType() { return type; }
        public TypedId getInputFeatureId() { return inputFeatureId; }
    }

    public static final class Segment {
        private final String id;
        private final String startNodeId;
        private final String endNodeId;
        private final LineString geometry;
        private final String flowFromNodeId;
        private final String flowToNodeId;
        private final BigDecimal flowTph;
        private final Integer diameterMm;
        private final String layingMethod;
        private final Set<TypedId> restrictionIds;
        private final BigDecimal multiplier;

        public Segment(String id, String startNodeId, String endNodeId) {
            this(id, startNodeId, endNodeId, null, null, null, null, null, null, Collections.emptySet(), null);
        }

        public Segment(String id, String startNodeId, String endNodeId, LineString geometry,
                String flowFromNodeId, String flowToNodeId, BigDecimal flowTph, Integer diameterMm,
                String layingMethod, Set<TypedId> restrictionIds, BigDecimal multiplier) {
            this.id = Objects.requireNonNull(id, "id");
            this.startNodeId = Objects.requireNonNull(startNodeId, "startNodeId");
            this.endNodeId = Objects.requireNonNull(endNodeId, "endNodeId");
            this.geometry = geometry == null ? null : (LineString) geometry.copy();
            this.flowFromNodeId = flowFromNodeId;
            this.flowToNodeId = flowToNodeId;
            this.flowTph = flowTph;
            this.diameterMm = diameterMm;
            this.layingMethod = layingMethod;
            this.restrictionIds = Collections.unmodifiableSet(new HashSet<>(restrictionIds));
            this.multiplier = multiplier;
        }

        public String getId() { return id; }
        public String getStartNodeId() { return startNodeId; }
        public String getEndNodeId() { return endNodeId; }
        public LineString getGeometry() { return geometry == null ? null : (LineString) geometry.copy(); }
        public String getFlowFromNodeId() { return flowFromNodeId; }
        public String getFlowToNodeId() { return flowToNodeId; }
        public BigDecimal getFlowTph() { return flowTph; }
        public Integer getDiameterMm() { return diameterMm; }
        public String getLayingMethod() { return layingMethod; }
        public Set<TypedId> getRestrictionIds() { return restrictionIds; }
        public BigDecimal getMultiplier() { return multiplier; }
    }
}
