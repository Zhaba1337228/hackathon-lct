package ru.lct.heatnetwork.validation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.network.FlowAggregator;
import ru.lct.heatnetwork.network.NetworkEdge;
import ru.lct.heatnetwork.rules.DiameterRule;
import ru.lct.heatnetwork.rules.DiameterSelector;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.rules.RestrictionRule;
import ru.lct.heatnetwork.spatial.ClearanceCheck;
import ru.lct.heatnetwork.spatial.ClearanceExemption;
import ru.lct.heatnetwork.spatial.ClearanceRange;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.spatial.IntersectionSegment;
import ru.lct.heatnetwork.spatial.SpecialCrossingBuilder;

/** Independent structural validator; it never promotes an unchecked variant to acceptable. */
public final class VariantValidator {
    private static final double RIGHT_ANGLE_EPSILON_DEGREES = 1e-7;
    private static final List<String> SPATIAL_CHECKS_NOT_YET_AVAILABLE = Collections.unmodifiableList(Arrays.asList(
            "restriction_crossings_and_special_passages", "new_segment_intersections"));

    public ValidationReport validate(Variant variant) {
        return validate(variant, null);
    }

    public ValidationReport validate(Variant variant, ValidationContext context) {
        if (variant == null) throw new IllegalArgumentException("Variant is required");
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, Variant.Node> nodes = new HashMap<>();
        Map<String, List<Variant.Segment>> incident = new HashMap<>();
        for (Variant.Node node : variant.getNodes()) {
            nodes.put(node.getId(), node);
            if (!Double.isFinite(node.getX()) || !Double.isFinite(node.getY())) {
                issue(issues, "NODE_COORDINATE_INVALID", node.getId(), "Координаты узла должны быть конечными числами.");
            }
            incident.put(node.getId(), new ArrayList<>());
        }
        DisjointSet components = new DisjointSet(nodes.keySet());
        for (Variant.Segment segment : variant.getSegments()) {
            Variant.Node start = nodes.get(segment.getStartNodeId());
            Variant.Node end = nodes.get(segment.getEndNodeId());
            if (start == null || end == null) {
                issue(issues, "SEGMENT_NODE_MISSING", segment.getId(), "Участок ссылается на отсутствующий узел.");
                continue;
            }
            incident.get(start.getId()).add(segment);
            incident.get(end.getId()).add(segment);
            if (!components.union(start.getId(), end.getId())) {
                issue(issues, "NETWORK_CYCLE", segment.getId(), "Новый граф содержит цикл.");
            }
        }
        validateDegreeAndTurns(nodes, incident, issues);
        validateTargetsReachAttachment(nodes, incident, components, issues);
        List<String> notRun = new ArrayList<>(SPATIAL_CHECKS_NOT_YET_AVAILABLE);
        boolean segmentDataComplete = validateSegmentGeometryAndRules(variant.getSegments(), nodes, issues);
        if (!segmentDataComplete) notRun.add("segment_geometry_and_diameter");
        boolean inputContextComplete = context != null;
        Map<String, BigDecimal> demandByNode = new HashMap<>();
        Map<TypedId, FeatureIndex.IndexedFeature> inputById = new HashMap<>();
        if (context != null) {
            for (FeatureIndex.IndexedFeature feature : context.getFeatures()) {
                if (feature != null && feature.getFeature() != null && feature.getFeature().getId() != null) {
                    inputById.put(feature.getFeature().getId(), feature);
                }
            }
            inputContextComplete = validateInputLinks(nodes, incident, inputById, demandByNode, issues);
        }
        if (!segmentDataComplete || !validatePathLengths(variant.getSegments(), nodes, issues)) {
            notRun.add("flow_diameter_and_path_length");
        }
        if (segmentDataComplete && context != null && inputContextComplete) {
            validateFlowsAndSelection(variant.getSegments(), nodes, demandByNode, issues);
        } else if (!notRun.contains("flow_diameter_and_path_length")) {
            notRun.add("flow_aggregation_and_minimal_diameter");
        }
        if (context == null) notRun.add("input_feature_links");
        if (segmentDataComplete && context != null) {
            validateSpatialRules(variant.getSegments(), nodes, incident, inputById, issues);
            notRun.remove("restriction_crossings_and_special_passages");
            notRun.remove("new_segment_intersections");
        }
        return new ValidationReport(issues, notRun);
    }

    private static void validateSpatialRules(List<Variant.Segment> segments, Map<String, Variant.Node> nodes,
            Map<String, List<Variant.Segment>> incident,
            Map<TypedId, FeatureIndex.IndexedFeature> inputById, List<ValidationIssue> issues) {
        List<SpatialObstacle> obstacles = new ArrayList<>();
        List<SpecialCrossingBuilder.CrossingRestriction> special = new ArrayList<>();
        for (FeatureIndex.IndexedFeature indexed : inputById.values()) {
            InputFeature feature = indexed.getFeature();
            if (indexed.getGeometry() == null || indexed.getGeometry().getSRID() != 32637) continue;
            String type = null;
            Double width = numericProperty(feature, "width_m");
            if ("restriction".equals(feature.getObjectType())) {
                com.fasterxml.jackson.databind.JsonNode value = feature.getProperties().getExtra().get("restriction_type");
                type = value != null && value.isTextual() ? value.textValue() : null;
                if (indexed.getGeometry().getDimension() == 2) width = null;
            } else if ("heat_network".equals(feature.getObjectType())) {
                type = "heat_network";
                com.fasterxml.jackson.databind.JsonNode diameter = feature.getProperties().getExtra().get("diameter");
                if (diameter != null && diameter.canConvertToInt()) {
                    width = RuleCatalog.diameter(diameter.intValue()).map(r -> r.getPairWidthM().doubleValue()).orElse(null);
                }
            }
            if (type == null) continue;
            RestrictionRule rule = RuleCatalog.restriction(type).orElse(null);
            if (rule == null) continue;
            obstacles.add(new SpatialObstacle(feature.getId(), type, indexed.getGeometry(), width));
            if (rule.getCrossing() == RestrictionRule.Crossing.SPECIAL_PASSAGE) {
                special.add(new SpecialCrossingBuilder.CrossingRestriction(feature.getId(), type,
                        indexed.getGeometry(), width));
            }
        }

        validateNewSegmentIntersections(segments, nodes, issues);
        Map<String, NetworkEdge> edgeByConsumer = new HashMap<>();
        Map<String, Variant.Segment> segmentById = new HashMap<>();
        for (Variant.Segment segment : segments) {
            edgeByConsumer.put(segment.getFlowFromNodeId(), new NetworkEdge(segment.getId(),
                    segment.getFlowFromNodeId(), segment.getFlowToNodeId(), segment.getGeometry().getLength(),
                    segment.getDiameterMm()));
            segmentById.put(segment.getId(), segment);
        }
        ClearanceCheck clearance = new ClearanceCheck();
        SpecialCrossingBuilder crossingBuilder = new SpecialCrossingBuilder();
        Set<String> pathCheckedSegments = new HashSet<>();
        for (Variant.Node target : nodes.values()) {
            if (!"oks_connection_point".equals(target.getType())) continue;
            List<Variant.Segment> targetToSource = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String current = target.getId();
            while (edgeByConsumer.containsKey(current)) {
                if (!visited.add(current)) break;
                Variant.Segment segment = segmentById.get(edgeByConsumer.get(current).getId());
                targetToSource.add(segment);
                current = segment.getFlowToNodeId();
            }
            if (targetToSource.isEmpty() || !visited.add("end:" + current)) continue;
            List<PathPiece> pieces = pathPiecesSourceToTarget(targetToSource);
            LineString wholeRoute = combinePieces(pieces);
            FeatureIndex.IndexedFeature targetFeature = target.getInputFeatureId() == null
                    ? null : inputById.get(target.getInputFeatureId());
            Point targetPoint = targetFeature != null && targetFeature.getGeometry() instanceof Point
                    ? (Point) targetFeature.getGeometry() : wholeRoute.getEndPoint();
            List<SpatialObstacle> ownBuildings = new ArrayList<>();
            for (SpatialObstacle obstacle : obstacles) {
                if ("oks".equals(obstacle.type) && obstacle.geometry.contains(targetPoint)) ownBuildings.add(obstacle);
            }
            if (ownBuildings.size() > 1) {
                issue(issues, "TARGET_OWN_OKS_AMBIGUOUS", target.getId(),
                        "Точка подключения находится внутри нескольких ограничений oks; собственное здание неоднозначно.");
            }
            SpatialObstacle ownBuilding = ownBuildings.size() == 1 ? ownBuildings.get(0) : null;
            ClearanceExemption ownExemption = null;
            if (ownBuilding != null) {
                try {
                    ownExemption = clearance.ownOksFinalApproach(wholeRoute, ownBuilding.geometry, targetPoint,
                            ownBuilding.id, targetToSource.get(0).getDiameterMm());
                } catch (IllegalArgumentException exception) {
                    issue(issues, "OWN_OKS_APPROACH_INVALID", target.getId(), exception.getMessage());
                }
            }
            for (PathPiece piece : pieces) {
                Variant.Segment segment = piece.segment;
                List<SpecialCrossingBuilder.CrossingRestriction> applicableCrossings = new ArrayList<>();
                for (SpecialCrossingBuilder.CrossingRestriction restriction : special) {
                    SpatialObstacle obstacle = obstacleById(obstacles, restriction);
                    if (obstacle != null && isTerminalNetworkAttachment(piece, targetToSource, nodes, obstacle)) continue;
                    applicableCrossings.add(restriction);
                }
                List<IntersectionSegment> crossingParts = Collections.emptyList();
                try {
                    crossingParts = crossingBuilder.build(piece.geometry, applicableCrossings,
                            segment.getDiameterMm(), true, true);
                    validatePassageMetadata(segment, crossingParts, obstacles, issues);
                } catch (IllegalArgumentException exception) {
                    issue(issues, "SPECIAL_PASSAGE_INVALID", segment.getId(), exception.getMessage());
                }
                for (SpatialObstacle obstacle : obstacles) {
                    if (isTerminalNetworkAttachment(piece, targetToSource, nodes, obstacle)) continue;
                    RestrictionRule rule = RuleCatalog.restriction(obstacle.type).orElse(null);
                    if (rule == null) continue;
                    double obstacleWidth = obstacle.widthM == null ? defaultObstacleWidth(obstacle.type)
                            : obstacle.widthM.doubleValue();
                    BigDecimal pipeWidth = RuleCatalog.diameter(segment.getDiameterMm()).get().getPairWidthM();
                    double threshold = clearance.requiredCenterlineDistanceM(obstacle.type,
                            segment.getDiameterMm(), pipeWidth,
                            obstacleWidth > 0 ? obstacleWidth : null);
                    List<ClearanceRange> ranges = new ArrayList<>(crossingParts);
                    if (ownExemption != null && ownBuilding != null && ownBuilding.id.equals(obstacle.id)) {
                        double localStart = Math.max(0, ownExemption.getStartMeasureM() - piece.startMeasureM);
                        double localEnd = Math.min(piece.geometry.getLength(),
                                ownExemption.getEndMeasureM() - piece.startMeasureM);
                        if (localEnd - localStart > 1e-9) {
                            double stableStart = clearance.stabilizeExemptionStart(piece.geometry,
                                    obstacle.geometry, threshold, localStart);
                            ranges.add(new ClearanceExemption(ownBuilding.id, stableStart, localEnd));
                        }
                    }
                    if (!clearance.respectsClearance(piece.geometry, obstacle.geometry, threshold, obstacle.id, ranges)) {
                        issue(issues, "SPATIAL_CLEARANCE_VIOLATION", segment.getId(),
                                "Нарушен нормативный отступ до " + obstacle.type + " (id=" + obstacle.id + ").");
                    }
                }
                pathCheckedSegments.add(segment.getId());
            }
        }
        for (Variant.Segment segment : segments) {
            if (!pathCheckedSegments.contains(segment.getId())) {
                issue(issues, "SEGMENT_NOT_ON_TARGET_PATH", segment.getId(),
                        "Участок не входит ни в один проверенный путь от цели к присоединению.");
            }
        }
        validateExistingChamberDegree(nodes, incident, obstacles, inputById, issues);
    }

    private static void validatePassageMetadata(Variant.Segment segment, List<IntersectionSegment> parts,
            List<SpatialObstacle> obstacles, List<ValidationIssue> issues) {
        Set<TypedId> expected = new HashSet<>();
        for (IntersectionSegment part : parts) expected.addAll(part.getRestrictionIds());
        Set<Set<TypedId>> activeSets = new HashSet<>();
        for (IntersectionSegment part : parts) activeSets.add(part.getRestrictionIds());
        if (activeSets.size() > 1) {
            issue(issues, "SEGMENT_SPECIAL_SPLIT_REQUIRED", segment.getId(),
                    "Участок пересекает границу способа прокладки или активных ограничений и должен быть разделён.");
        }
        Set<TypedId> actual = segment.getRestrictionIds() == null ? Collections.emptySet() : segment.getRestrictionIds();
        if (!expected.equals(actual)) {
            issue(issues, "SEGMENT_RESTRICTION_SET_MISMATCH", segment.getId(),
                    "Набор ограничений участка не совпадает с геометрически найденными спецпроходами.");
        }
        boolean specialMethod = !expected.isEmpty();
        if (specialMethod != "special".equals(segment.getLayingMethod())) {
            issue(issues, "SEGMENT_LAYING_METHOD_MISMATCH", segment.getId(),
                    "Способ прокладки не соответствует пересекаемым ограничениям.");
        }
        BigDecimal multiplier = RuleCatalog.baseMultiplier();
        for (TypedId id : expected) {
            SpatialObstacle obstacle = obstacleById(obstacles, id);
            RestrictionRule rule = obstacle == null ? null : RuleCatalog.restriction(obstacle.type).orElse(null);
            if (rule != null) multiplier = multiplier.max(rule.getSpecialMultiplier());
        }
        if (segment.getMultiplier() == null || segment.getMultiplier().compareTo(multiplier) != 0) {
            issue(issues, "SEGMENT_MULTIPLIER_INVALID", segment.getId(), "Не указан корректный коэффициент участка.");
        }
    }

    private static void validateNewSegmentIntersections(List<Variant.Segment> segments,
            Map<String, Variant.Node> nodes, List<ValidationIssue> issues) {
        for (int i = 0; i < segments.size(); i++) {
            Variant.Segment first = segments.get(i);
            if (first.getGeometry() == null) continue;
            for (int j = i + 1; j < segments.size(); j++) {
                Variant.Segment second = segments.get(j);
                if (second.getGeometry() == null || !first.getGeometry().intersects(second.getGeometry())) continue;
                Set<String> common = new HashSet<>(Arrays.asList(first.getStartNodeId(), first.getEndNodeId()));
                common.retainAll(Arrays.asList(second.getStartNodeId(), second.getEndNodeId()));
                Geometry intersection = first.getGeometry().intersection(second.getGeometry());
                boolean onlyAtCommonNode = false;
                for (String nodeId : common) {
                    Variant.Node node = nodes.get(nodeId);
                    if (node == null) continue;
                    Point point = first.getGeometry().getFactory().createPoint(
                            new org.locationtech.jts.geom.Coordinate(node.getX(), node.getY()));
                    if (insideNodeTolerance(intersection, point, 0.25)) onlyAtCommonNode = true;
                }
                if (!onlyAtCommonNode) issue(issues, "NEW_SEGMENTS_INTERSECT", second.getId(),
                        "Новые линейные участки пересекаются вне общего узла.");
            }
        }
    }

    private static void validateExistingChamberDegree(Map<String, Variant.Node> nodes,
            Map<String, List<Variant.Segment>> incident, List<SpatialObstacle> obstacles,
            Map<TypedId, FeatureIndex.IndexedFeature> inputById,
            List<ValidationIssue> issues) {
        for (Variant.Node node : nodes.values()) {
            if (!"heat_chamber".equals(node.getType()) || node.getInputFeatureId() == null) continue;
            FeatureIndex.IndexedFeature attachment = inputById.get(node.getInputFeatureId());
            if (attachment == null || !("heat_network".equals(attachment.getFeature().getObjectType())
                    || "heat_chamber".equals(attachment.getFeature().getObjectType()))) continue;
            Point point = attachment.getGeometry().getFactory().createPoint(
                    new org.locationtech.jts.geom.Coordinate(node.getX(), node.getY()));
            int existing = 0;
            for (SpatialObstacle obstacle : obstacles) {
                if (!"heat_network".equals(obstacle.type) || obstacle.geometry.distance(point) > 0.25) continue;
                if (obstacle.geometry instanceof LineString) {
                    LineString line = (LineString) obstacle.geometry;
                    boolean endpoint = point.distance(line.getStartPoint()) <= 0.25
                            || point.distance(line.getEndPoint()) <= 0.25;
                    existing += endpoint ? 1 : 2;
                }
            }
            if (existing + incident.get(node.getId()).size() > 4) issue(issues,
                    "CHAMBER_TOTAL_CONNECTION_LIMIT", node.getId(),
                    "С учётом существующих линий к камере примыкает более четырёх участков.");
        }
    }

    private static boolean isTerminalNetworkAttachment(PathPiece piece, List<Variant.Segment> targetToSource,
            Map<String, Variant.Node> nodes, SpatialObstacle obstacle) {
        if (!"heat_network".equals(obstacle.type) || targetToSource.isEmpty()
                || !piece.segment.getId().equals(targetToSource.get(targetToSource.size() - 1).getId())) return false;
        Variant.Node endpoint = nodes.get(piece.segment.getFlowToNodeId());
        if (endpoint == null || endpoint.getInputFeatureId() == null) return false;
        if (endpoint.getInputFeatureId().equals(obstacle.id)) return true;
        Point end = piece.geometry.getStartPoint();
        Geometry intersection = piece.geometry.intersection(obstacle.geometry);
        return end.distance(obstacle.geometry) <= 0.25 && insideNodeTolerance(intersection, end, 0.25);
    }

    private static boolean insideNodeTolerance(Geometry intersection, Point node, double toleranceM) {
        // Both operands are one-dimensional: a segment whose endpoints lie inside this circle
        // lies entirely inside it, including mixed point-and-line GeometryCollections.
        for (org.locationtech.jts.geom.Coordinate coordinate : intersection.getCoordinates()) {
            if (coordinate.distance(node.getCoordinate()) > toleranceM) return false;
        }
        return true;
    }

    private static SpatialObstacle obstacleById(List<SpatialObstacle> obstacles,
            SpecialCrossingBuilder.CrossingRestriction restriction) {
        for (SpatialObstacle obstacle : obstacles) if (obstacle.id.equals(restriction.getRestrictionId())) return obstacle;
        return null;
    }

    private static SpatialObstacle obstacleById(List<SpatialObstacle> obstacles, TypedId id) {
        for (SpatialObstacle obstacle : obstacles) if (obstacle.id.equals(id)) return obstacle;
        return null;
    }

    private static double defaultObstacleWidth(String type) {
        if ("gas_pipeline".equals(type)) return 0.40;
        if ("power_cable".equals(type)) return 0.20;
        return 0;
    }

    private static Double numericProperty(InputFeature feature, String name) {
        com.fasterxml.jackson.databind.JsonNode value = feature.getProperties().getExtra().get(name);
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                ? value.doubleValue() : null;
    }

    private static List<PathPiece> pathPiecesSourceToTarget(List<Variant.Segment> targetToSource) {
        List<PathPiece> pieces = new ArrayList<>();
        double measure = 0;
        for (int i = targetToSource.size() - 1; i >= 0; i--) {
            Variant.Segment segment = targetToSource.get(i);
            LineString oriented = orientedGeometry(segment, segment.getFlowToNodeId(), segment.getFlowFromNodeId());
            pieces.add(new PathPiece(segment, oriented, measure));
            measure += oriented.getLength();
        }
        return pieces;
    }

    private static LineString orientedGeometry(Variant.Segment segment, String desiredStart, String desiredEnd) {
        LineString geometry = segment.getGeometry();
        org.locationtech.jts.geom.Coordinate[] coordinates = geometry.getCoordinates();
        boolean alreadyOriented = desiredStart.equals(segment.getStartNodeId())
                && desiredEnd.equals(segment.getEndNodeId());
        if (!alreadyOriented) {
            for (int left = 0, right = coordinates.length - 1; left < right; left++, right--) {
                org.locationtech.jts.geom.Coordinate swap = coordinates[left];
                coordinates[left] = coordinates[right];
                coordinates[right] = swap;
            }
        }
        LineString result = geometry.getFactory().createLineString(coordinates);
        result.setSRID(geometry.getSRID());
        return result;
    }

    private static LineString combinePieces(List<PathPiece> pieces) {
        List<org.locationtech.jts.geom.Coordinate> coordinates = new ArrayList<>();
        for (PathPiece piece : pieces) {
            for (org.locationtech.jts.geom.Coordinate coordinate : piece.geometry.getCoordinates()) {
                if (coordinates.isEmpty() || !coordinates.get(coordinates.size() - 1).equals2D(coordinate)) {
                    coordinates.add(coordinate);
                }
            }
        }
        LineString route = pieces.get(0).geometry.getFactory().createLineString(
                coordinates.toArray(new org.locationtech.jts.geom.Coordinate[0]));
        route.setSRID(pieces.get(0).geometry.getSRID());
        return route;
    }

    private static final class SpatialObstacle {
        private final TypedId id;
        private final String type;
        private final Geometry geometry;
        private final Double widthM;
        private SpatialObstacle(TypedId id, String type, Geometry geometry, Double widthM) {
            this.id = id; this.type = type; this.geometry = geometry; this.widthM = widthM;
        }
    }

    private static final class PathPiece {
        private final Variant.Segment segment;
        private final LineString geometry;
        private final double startMeasureM;
        private PathPiece(Variant.Segment segment, LineString geometry, double startMeasureM) {
            this.segment = segment; this.geometry = geometry; this.startMeasureM = startMeasureM;
        }
    }

    private static boolean validateSegmentGeometryAndRules(List<Variant.Segment> segments,
            Map<String, Variant.Node> nodes, List<ValidationIssue> issues) {
        boolean complete = !segments.isEmpty();
        for (Variant.Segment segment : segments) {
            Geometry geometry = segment.getGeometry();
            if (geometry == null) {
                complete = false;
                continue;
            }
            if (!(geometry instanceof org.locationtech.jts.geom.LineString) || geometry.getSRID() != 32637) {
                issue(issues, "SEGMENT_GEOMETRY_INVALID", segment.getId(),
                        "Для независимой проверки требуется LineString в EPSG:32637.");
                complete = false;
                continue;
            }
            if (geometry.isEmpty() || !geometry.isValid() || geometry.getLength() <= 0) {
                issue(issues, "SEGMENT_GEOMETRY_INVALID", segment.getId(), "Геометрия участка должна быть непустой и иметь длину.");
                complete = false;
            }
            if (!geometry.isEmpty() && !geometry.isSimple()) {
                issue(issues, "SEGMENT_GEOMETRY_NOT_SIMPLE", segment.getId(),
                        "Участок трубы не должен пересекать себя или возвращаться в уже пройденную точку.");
                complete = false;
            }
            if (!geometry.isEmpty() && hasInternalTurnOverLimit((LineString) geometry)) {
                issue(issues, "TURN_ANGLE_OVER_90", segment.getId(),
                        "Изменение направления внутри геометрии участка превышает 90 градусов.");
            }
            Variant.Node start = nodes.get(segment.getStartNodeId()), end = nodes.get(segment.getEndNodeId());
            Point first = geometry.getFactory().createPoint(geometry.getCoordinates()[0]);
            Point last = geometry.getFactory().createPoint(geometry.getCoordinates()[geometry.getNumPoints() - 1]);
            if (start != null && first.distance(geometry.getFactory().createPoint(
                    new org.locationtech.jts.geom.Coordinate(start.getX(), start.getY()))) > 0.25
                    || end != null && last.distance(geometry.getFactory().createPoint(
                            new org.locationtech.jts.geom.Coordinate(end.getX(), end.getY()))) > 0.25) {
                issue(issues, "SEGMENT_ENDPOINT_MISMATCH", segment.getId(),
                        "Координаты геометрии не совпадают с координатами узлов участка в допуске 0,25 м.");
            }
            if (segment.getFlowTph() == null || segment.getFlowTph().signum() <= 0
                    || segment.getDiameterMm() == null || !RuleCatalog.diameter(segment.getDiameterMm()).isPresent()
                    || segment.getLayingMethod() == null || segment.getMultiplier() == null
                    || segment.getRestrictionIds() == null) {
                issue(issues, "SEGMENT_RULE_DATA_MISSING", segment.getId(),
                        "Нужны положительный расход, табличный ДУ и параметры прокладки.");
                complete = false;
            }
            if (segment.getFlowFromNodeId() == null || segment.getFlowToNodeId() == null
                    || !isOppositeEndpoints(segment.getFlowFromNodeId(), segment.getFlowToNodeId(), segment)) {
                issue(issues, "SEGMENT_FLOW_DIRECTION_MISSING", segment.getId(),
                        "Направление расчётного потока должно ссылаться на оба конца участка.");
                complete = false;
            }
        }
        return complete;
    }

    private static boolean hasInternalTurnOverLimit(LineString line) {
        org.locationtech.jts.geom.Coordinate[] coordinates = line.getCoordinates();
        for (int i = 1; i < coordinates.length - 1; i++) {
            org.locationtech.jts.geom.Coordinate previous = coordinates[i - 1];
            org.locationtech.jts.geom.Coordinate current = coordinates[i];
            org.locationtech.jts.geom.Coordinate next = coordinates[i + 1];
            double ax = previous.x - current.x, ay = previous.y - current.y;
            double bx = next.x - current.x, by = next.y - current.y;
            double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
            if (lengths == 0) return true;
            double cosine = Math.max(-1, Math.min(1, (ax * bx + ay * by) / lengths));
            if (Math.toDegrees(Math.acos(cosine)) < 90.0 - RIGHT_ANGLE_EPSILON_DEGREES) return true;
        }
        return false;
    }

    private static boolean isOppositeEndpoints(String from, String to, Variant.Segment segment) {
        return !from.equals(to) && (from.equals(segment.getStartNodeId()) && to.equals(segment.getEndNodeId())
                || from.equals(segment.getEndNodeId()) && to.equals(segment.getStartNodeId()));
    }

    private static boolean validatePathLengths(List<Variant.Segment> segments,
            Map<String, Variant.Node> nodes, List<ValidationIssue> issues) {
        try {
            List<NetworkEdge> edges = new ArrayList<>();
            for (Variant.Segment segment : segments) {
                if (segment.getGeometry() == null || segment.getDiameterMm() == null
                        || segment.getFlowFromNodeId() == null || segment.getFlowToNodeId() == null) return false;
                edges.add(new NetworkEdge(segment.getId(), segment.getFlowFromNodeId(), segment.getFlowToNodeId(),
                        segment.getGeometry().getLength(), segment.getDiameterMm()));
            }
            List<String> targets = new ArrayList<>();
            for (Variant.Node node : nodes.values()) if ("oks_connection_point".equals(node.getType())) targets.add(node.getId());
            List<PathLengthViolation> violations = new PathLengthValidator().validate(edges, targets);
            for (PathLengthViolation violation : violations) issue(issues, "PATH_LENGTH_EXCEEDED",
                    violation.getTargetNode(), "ДУ " + violation.getDiameterMm() + " мм: путь "
                            + violation.getActualLengthM() + " м превышает " + violation.getMaximumLengthM() + " м.");
            return true;
        } catch (IllegalArgumentException exception) {
            issue(issues, "PATH_RULES_INVALID", null, exception.getMessage());
            return true;
        }
    }

    private static boolean validateInputLinks(Map<String, Variant.Node> nodes,
            Map<String, List<Variant.Segment>> incident, Map<TypedId, FeatureIndex.IndexedFeature> inputById,
            Map<String, BigDecimal> demandByNode, List<ValidationIssue> issues) {
        Set<TypedId> representedTargets = new HashSet<>();
        boolean complete = true;
        for (Variant.Node node : nodes.values()) {
            boolean target = "oks_connection_point".equals(node.getType());
            List<Variant.Segment> edges = incident.get(node.getId());
            boolean attachment = !target && ((edges.size() == 1
                    && ("heat_chamber".equals(node.getType()) || "technical_node".equals(node.getType())))
                    || isLinkedRootAttachment(node, edges));
            if (!target && !attachment && node.getInputFeatureId() == null) continue;
            TypedId inputId = node.getInputFeatureId();
            FeatureIndex.IndexedFeature indexed = inputId == null ? null : inputById.get(inputId);
            if (indexed == null) {
                issue(issues, target ? "TARGET_INPUT_LINK_MISSING" : "ATTACHMENT_INPUT_LINK_MISSING", node.getId(),
                        "Узел должен ссылаться на соответствующий входной объект.");
                complete = false;
                continue;
            }
            InputFeature feature = indexed.getFeature();
            String expectedType = target ? "oks_connection_point" : feature.getObjectType();
            if (target && !"oks_connection_point".equals(feature.getObjectType())) {
                issue(issues, "TARGET_INPUT_TYPE_INVALID", node.getId(), "Цель должна ссылаться на oks_connection_point.");
                complete = false;
                continue;
            }
            if (attachment && !("heat_network".equals(expectedType) || "heat_chamber".equals(expectedType))) {
                issue(issues, "ATTACHMENT_INPUT_TYPE_INVALID", node.getId(),
                        "Точка присоединения должна ссылаться на существующую сеть или камеру.");
                complete = false;
                continue;
            }
            if (attachment && !"heat_chamber".equals(node.getType())) {
                issue(issues, "ATTACHMENT_NODE_TYPE_INVALID", node.getId(),
                        "Присоединение к существующей сети или камере должно заканчиваться камерой.");
                complete = false;
            }
            if (target) {
                representedTargets.add(inputId);
                BigDecimal flow = decimalProperty(feature, "flow_tph");
                if (flow == null || flow.signum() <= 0) {
                    issue(issues, "TARGET_FLOW_MISSING", node.getId(), "Не найден положительный расход входной цели.");
                    complete = false;
                } else {
                    demandByNode.put(node.getId(), flow);
                }
            }
            Geometry inputGeometry = indexed.getGeometry();
            if (inputGeometry == null || inputGeometry.getSRID() != 32637
                    || inputGeometry.distance(inputGeometry.getFactory().createPoint(
                            new org.locationtech.jts.geom.Coordinate(node.getX(), node.getY()))) > 0.25) {
                issue(issues, target ? "TARGET_COORDINATE_MISMATCH" : "ATTACHMENT_COORDINATE_MISMATCH",
                        node.getId(), "Координаты узла отличаются от связанного входного объекта более чем на 0,25 м.");
                complete = false;
            }
        }
        for (FeatureIndex.IndexedFeature indexed : inputById.values()) {
            InputFeature feature = indexed.getFeature();
            if ("oks_connection_point".equals(feature.getObjectType())
                    && !representedTargets.contains(feature.getId())) {
                issue(issues, "TARGET_OMITTED", feature.getId().toString(), "Вариант не содержит узел для входной цели.");
                complete = false;
            }
        }
        return complete;
    }

    private static BigDecimal decimalProperty(InputFeature feature, String name) {
        com.fasterxml.jackson.databind.JsonNode value = feature.getProperties().getExtra().get(name);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static void validateFlowsAndSelection(List<Variant.Segment> segments,
            Map<String, Variant.Node> nodes, Map<String, BigDecimal> demandByNode,
            List<ValidationIssue> issues) {
        try {
            List<NetworkEdge> edges = new ArrayList<>();
            Map<String, Variant.Segment> segmentsById = new HashMap<>();
            for (Variant.Segment segment : segments) {
                edges.add(new NetworkEdge(segment.getId(), segment.getFlowFromNodeId(), segment.getFlowToNodeId(),
                        segment.getGeometry().getLength(), segment.getDiameterMm()));
                segmentsById.put(segment.getId(), segment);
            }
            Map<String, BigDecimal> flows = new FlowAggregator().aggregate(edges, demandByNode);
            Map<String, DiameterRule> minimum = new DiameterSelector().selectForTree(edges, flows,
                    targetNodeIds(nodes));
            for (Variant.Segment segment : segments) {
                BigDecimal expected = flows.get(segment.getId());
                if (expected == null || expected.compareTo(segment.getFlowTph()) != 0) {
                    issue(issues, "SEGMENT_FLOW_MISMATCH", segment.getId(), "Расход участка не равен сумме расходов целей ниже по графу.");
                }
                DiameterRule rule = RuleCatalog.diameter(segment.getDiameterMm()).orElse(null);
                if (rule == null || segment.getFlowTph().compareTo(rule.getCapacityTph()) > 0) {
                    issue(issues, "SEGMENT_CAPACITY_EXCEEDED", segment.getId(), "Расход превышает табличную пропускную способность ДУ.");
                } else if (minimum.get(segment.getId()).getDiameterMm() != segment.getDiameterMm()) {
                    issue(issues, "SEGMENT_DIAMETER_NOT_MINIMAL", segment.getId(), "Назначен не минимальный табличный диаметр, допустимый по расходу и длине.");
                }
            }
        } catch (IllegalArgumentException exception) {
            issue(issues, "FLOW_RULES_INVALID", null, exception.getMessage());
        }
    }

    private static List<String> targetNodeIds(Map<String, Variant.Node> nodes) {
        List<String> targets = new ArrayList<>();
        for (Variant.Node node : nodes.values()) if ("oks_connection_point".equals(node.getType())) targets.add(node.getId());
        return targets;
    }

    private static void validateDegreeAndTurns(Map<String, Variant.Node> nodes,
            Map<String, List<Variant.Segment>> incident, List<ValidationIssue> issues) {
        for (Variant.Node node : nodes.values()) {
            List<Variant.Segment> edges = incident.get(node.getId());
            if ("oks_connection_point".equals(node.getType()) && edges.size() != 1) {
                issue(issues, "TARGET_DEGREE", node.getId(), "К каждой целевой точке должен примыкать ровно один участок.");
            }
            if (edges.size() > 2 && !"heat_chamber".equals(node.getType())) {
                issue(issues, "BRANCH_WITHOUT_CHAMBER", node.getId(), "Ветвление нового графа допускается только в тепловой камере.");
            }
            if ("heat_chamber".equals(node.getType()) && edges.size() > 4) {
                issue(issues, "CHAMBER_CONNECTION_LIMIT", node.getId(), "К камере примыкает более четырёх участков.");
            }
            if (edges.size() == 2 && !isLinkedRootAttachment(node, edges)
                    && isTurnOverLimit(node, edges, nodes)) {
                issue(issues, "TURN_ANGLE_OVER_90", node.getId(), "Изменение направления трассы превышает 90 градусов.");
            }
            if (edges.size() > 2 && "heat_chamber".equals(node.getType())
                    && !isLinkedRootAttachment(node, edges)) {
                Variant.Segment sourceward = null;
                for (Variant.Segment edge : edges) {
                    if (node.getId().equals(edge.getFlowFromNodeId())) {
                        if (sourceward != null) {
                            sourceward = null;
                            break;
                        }
                        sourceward = edge;
                    }
                }
                if (sourceward != null) {
                    for (Variant.Segment incoming : edges) {
                        if (node.getId().equals(incoming.getFlowToNodeId())
                                && isTurnOverLimit(node, Arrays.asList(incoming, sourceward), nodes)) {
                            issue(issues, "TURN_ANGLE_OVER_90", node.getId(),
                                    "Изменение направления трассы превышает 90 градусов.");
                            break;
                        }
                    }
                }
            }
        }
    }

    private static boolean isTurnOverLimit(Variant.Node node, List<Variant.Segment> edges,
            Map<String, Variant.Node> nodes) {
        org.locationtech.jts.geom.Coordinate first = adjacentCoordinate(edges.get(0), node, nodes);
        org.locationtech.jts.geom.Coordinate second = adjacentCoordinate(edges.get(1), node, nodes);
        if (first == null || second == null) return false;
        double ax = first.x - node.getX(), ay = first.y - node.getY();
        double bx = second.x - node.getX(), by = second.y - node.getY();
        double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (lengths == 0) return true;
        double cosine = Math.max(-1, Math.min(1, (ax * bx + ay * by) / lengths));
        return Math.toDegrees(Math.acos(cosine)) < 90.0 - RIGHT_ANGLE_EPSILON_DEGREES;
    }

    private static org.locationtech.jts.geom.Coordinate adjacentCoordinate(Variant.Segment segment,
            Variant.Node node, Map<String, Variant.Node> nodes) {
        LineString geometry = segment.getGeometry();
        if (geometry != null && geometry.getNumPoints() >= 2) {
            org.locationtech.jts.geom.Coordinate[] coordinates = geometry.getCoordinates();
            if (segment.getStartNodeId().equals(node.getId())) return coordinates[1];
            if (segment.getEndNodeId().equals(node.getId())) return coordinates[coordinates.length - 2];
        }
        Variant.Node other = otherNode(segment, node, nodes);
        return other == null ? null : new org.locationtech.jts.geom.Coordinate(other.getX(), other.getY());
    }

    private static Variant.Node otherNode(Variant.Segment segment, Variant.Node node,
            Map<String, Variant.Node> nodes) {
        String id = segment.getStartNodeId().equals(node.getId()) ? segment.getEndNodeId() : segment.getStartNodeId();
        return nodes.get(id);
    }

    private static void validateTargetsReachAttachment(Map<String, Variant.Node> nodes,
            Map<String, List<Variant.Segment>> incident, DisjointSet components, List<ValidationIssue> issues) {
        Map<String, Integer> attachmentsByComponent = new HashMap<>();
        for (Variant.Node node : nodes.values()) {
            boolean target = "oks_connection_point".equals(node.getType());
            boolean attachmentType = "heat_chamber".equals(node.getType()) || "technical_node".equals(node.getType());
            List<Variant.Segment> edges = incident.get(node.getId());
            if (!target && attachmentType && (edges.size() == 1 || isLinkedRootAttachment(node, edges))) {
                attachmentsByComponent.merge(components.find(node.getId()), 1, Integer::sum);
            }
        }
        for (Variant.Node node : nodes.values()) {
            if (!"oks_connection_point".equals(node.getType())) continue;
            if (incident.get(node.getId()).isEmpty()) {
                issue(issues, "TARGET_UNCONNECTED", node.getId(), "Целевая точка не подключена к графу.");
            } else {
                int attachments = attachmentsByComponent.getOrDefault(components.find(node.getId()), 0);
                if (attachments != 1) issue(issues, "TARGET_ATTACHMENT_COUNT", node.getId(),
                        "Для цели требуется ровно одна точка присоединения к существующей сети, найдено: " + attachments + ".");
            }
        }
    }

    private static boolean isLinkedRootAttachment(Variant.Node node, List<Variant.Segment> edges) {
        if (!"heat_chamber".equals(node.getType()) || node.getInputFeatureId() == null || edges.isEmpty()) {
            return false;
        }
        for (Variant.Segment edge : edges) {
            if (!node.getId().equals(edge.getFlowToNodeId())) return false;
        }
        return true;
    }

    private static void issue(List<ValidationIssue> issues, String code, String element,
            String message) {
        issues.add(new ValidationIssue(code, ValidationIssue.Severity.ERROR, element, message));
    }

    private static final class DisjointSet {
        private final Map<String, String> parent = new HashMap<>();
        private DisjointSet(Set<String> nodes) { for (String node : nodes) parent.put(node, node); }
        private String find(String node) {
            String root = parent.get(node);
            if (root == null) return node;
            if (!root.equals(node)) parent.put(node, find(root));
            return parent.get(node);
        }
        private boolean union(String first, String second) {
            String a = find(first), b = find(second);
            if (a.equals(b)) return false;
            parent.put(a, b);
            return true;
        }
    }
}
