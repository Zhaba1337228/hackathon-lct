package ru.lct.heatnetwork.routing;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.network.AttachmentCandidate;
import ru.lct.heatnetwork.rules.DiameterRule;
import ru.lct.heatnetwork.rules.RestrictionRule;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.spatial.ClearanceCheck;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.spatial.IntersectionSegment;
import ru.lct.heatnetwork.spatial.SpecialCrossingBuilder;
import ru.lct.heatnetwork.validation.ValidationContext;
import ru.lct.heatnetwork.validation.ValidationReport;
import ru.lct.heatnetwork.validation.VariantValidator;

/** Bounded A* search over a sparse visibility graph for one target and one attachment candidate. */
@Component
public final class SingleTargetRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SingleTargetRouter.class);
    private static final double SEARCH_VERTEX_MARGIN_M = 0.01;
    private static final double OWN_BUILDING_CLEARANCE_MARGIN_M = 0.05;
    private static final double NUMERIC_EPSILON_M = 1e-9;
    private static final int BUFFER_QUADRANT_SEGMENTS = 8;
    private static final double COARSE_BUFFER_RADIUS_FACTOR = 1.09;
    private static final double INITIAL_CORRIDOR_RADIUS_M = 25.0;
    private static final int MAX_CORRIDOR_EXPANSIONS = 8;
    private static final double ADAPTIVE_MINIMUM_CELL_SIZE_M = 1.0;
    private static final int SPARSE_MAX_VERTICES = 1200;
    private static final int NAVIGATOR_SPARSE_MAX_VERTICES = 3000;
    private static final int SPARSE_MAX_EXACT_EDGES = 40000;

    private final SearchMode searchMode;
    private final int sparseMaxExactEdges;
    private final ClearanceCheck clearance = new ClearanceCheck();
    private final SpecialCrossingBuilder crossingBuilder = new SpecialCrossingBuilder();
    private final VariantValidator validator = new VariantValidator();

    public SingleTargetRouter() { this(SearchMode.VISIBILITY); }

    public SingleTargetRouter(SearchMode searchMode) {
        this(searchMode, SPARSE_MAX_EXACT_EDGES);
    }

    SingleTargetRouter(SearchMode searchMode, int sparseMaxExactEdges) {
        if (searchMode == null) throw new IllegalArgumentException("Route search mode is required");
        if (sparseMaxExactEdges < 1) throw new IllegalArgumentException("Sparse exact-edge budget must be positive");
        this.searchMode = searchMode;
        this.sparseMaxExactEdges = sparseMaxExactEdges;
    }

    public enum SearchMode { VISIBILITY, ADAPTIVE_QUADTREE, CONSERVATIVE_FOREST,
        CONSERVATIVE_EXHAUSTIVE, CONSERVATIVE_DIRECT_BRANCH, CONSERVATIVE_SPATIAL,
        CONSERVATIVE_TREE_NAVIGATOR }

    public RouteCandidate route(RouteRequest request) {
        return route(request, new ObstacleBufferCache());
    }

    RouteCandidate route(RouteRequest request, ObstacleBufferCache bufferCache) {
        if (request == null) return RouteCandidate.failure(RouteCandidate.Status.INVALID_REQUEST, "Route request is required");
        if (bufferCache == null) return RouteCandidate.failure(RouteCandidate.Status.INVALID_REQUEST,
                "Obstacle buffer cache is required");
        try {
            SearchRequest searchRequest = new SearchRequest(request);
            RouteContext context = context(searchRequest);
            if (context.earlyStatus != null) return RouteCandidate.failure(context.earlyStatus, context.earlyDetail);
            BigDecimal corridorFlow = request.getPlannedSectionFlowTph() == null ? context.flowTph
                    : context.flowTph.max(request.getPlannedSectionFlowTph());
            int diameter = smallestCapacityDiameter(corridorFlow);
            if (diameter == 0) {
                return RouteCandidate.failure(RouteCandidate.Status.NO_FEASIBLE_DIAMETER,
                        "No tabulated diameter can carry the planned corridor flow");
            }

            SparseBudget sparseBudget = new SparseBudget(sparseMaxExactEdges);
            for (int attempt = 0; attempt < RuleCatalog.diameters().size(); attempt++) {
                SearchOutcome search = search(searchRequest, context, diameter, bufferCache, sparseBudget);
                if (search.status != null) return RouteCandidate.failure(search.status, search.detail);
                if (!search.route.isSimple()) return RouteCandidate.failure(RouteCandidate.Status.SEARCH_INCOMPLETE,
                        "The candidate route loops back or crosses itself; another path may exist");

                java.util.Optional<DiameterRule> selected = RuleCatalog.smallestDiameter(
                        corridorFlow, BigDecimal.valueOf(search.route.getLength()));
                if (!selected.isPresent()) {
                    return RouteCandidate.failure(RouteCandidate.Status.NO_FEASIBLE_DIAMETER,
                            "Route length exceeds the tabulated maximum for every diameter");
                }
                if (selected.get().getDiameterMm() > diameter) {
                    diameter = selected.get().getDiameterMm();
                    continue;
                }

                // The future section reserves space; only the real target is connected and costed.
                int actualDiameter = RuleCatalog.smallestDiameter(context.flowTph,
                        BigDecimal.valueOf(search.route.getLength())).get().getDiameterMm();
                Variant variant = buildVariant(request, context, search.route, actualDiameter);
                ValidationReport report = validator.validate(variant, new ValidationContext(context.validationInputs));
                if (!report.isAcceptable()) return RouteCandidate.rejected(search.route, variant, report);
                return RouteCandidate.found(search.route, variant, report, !search.incomplete);
            }
            return RouteCandidate.failure(RouteCandidate.Status.NO_FEASIBLE_DIAMETER,
                    "Diameter and clearance did not reach a stable tabulated choice");
        } catch (IllegalArgumentException exception) {
            return RouteCandidate.failure(RouteCandidate.Status.INVALID_REQUEST, exception.getMessage());
        }
    }

    public BranchRoute routeToTree(TreeRouteRequest request) {
        ObstacleBufferCache cache = new ObstacleBufferCache();
        try {
            return routeToTree(request, cache);
        } finally {
            cache.clear();
        }
    }

    BranchRoute routeToTree(TreeRouteRequest request, ObstacleBufferCache cache) {
        if (request == null) return BranchRoute.failure(RouteCandidate.Status.INVALID_REQUEST, "Tree route request is required");
        if (cache == null) return BranchRoute.failure(RouteCandidate.Status.INVALID_REQUEST,
                "Obstacle buffer cache is required");
        try {
            SearchRequest searchRequest = new SearchRequest(request);
            RouteContext context = context(searchRequest);
            if (context.earlyStatus != null) return BranchRoute.failure(context.earlyStatus, context.earlyDetail);
            int diameter = smallestCapacityDiameter(context.flowTph);
            if (diameter == 0) return BranchRoute.failure(RouteCandidate.Status.NO_FEASIBLE_DIAMETER,
                    "No tabulated diameter can carry the target flow");
            SparseBudget budget = new SparseBudget(sparseMaxExactEdges);
            for (int attempt = 0; attempt < RuleCatalog.diameters().size(); attempt++) {
                SearchOutcome search = search(searchRequest, context, diameter, cache, budget);
                if (search.status != null) return BranchRoute.failure(search.status, search.detail);
                if (!search.route.isSimple()) return BranchRoute.failure(RouteCandidate.Status.SEARCH_INCOMPLETE,
                        "The candidate branch loops back or crosses itself; another path may exist");
                java.util.Optional<DiameterRule> selected = RuleCatalog.smallestDiameter(
                        context.flowTph, BigDecimal.valueOf(search.route.getLength()));
                if (!selected.isPresent()) return BranchRoute.failure(RouteCandidate.Status.NO_FEASIBLE_DIAMETER,
                        "Branch length exceeds the tabulated maximum for every diameter");
                if (selected.get().getDiameterMm() > diameter) {
                    diameter = selected.get().getDiameterMm();
                    continue;
                }
                return BranchRoute.found(search.route, diameter, !search.incomplete);
            }
            return BranchRoute.failure(RouteCandidate.Status.NO_FEASIBLE_DIAMETER,
                    "Branch diameter and clearance did not stabilize");
        } catch (IllegalArgumentException exception) {
            return BranchRoute.failure(RouteCandidate.Status.INVALID_REQUEST, exception.getMessage());
        }
    }

    private RouteContext context(SearchRequest request) {
        FeatureIndex.IndexedFeature targetFeature = request.getTarget();
        InputFeature targetInput = targetFeature.getFeature();
        Point target = request.getTargetPoint();
        if (targetInput == null || targetInput.getId() == null
                || !"oks_connection_point".equals(targetInput.getObjectType())
                || target.getSRID() != 32637 || target.isEmpty()
                || !Double.isFinite(target.getX()) || !Double.isFinite(target.getY())) {
            throw new IllegalArgumentException("Target must be a typed EPSG:32637 oks_connection_point");
        }
        JsonNode flowValue = targetInput.getProperties().getExtra().get("flow_tph");
        if (flowValue == null || !flowValue.isNumber() || flowValue.decimalValue().signum() <= 0) {
            throw new IllegalArgumentException("Target requires a positive flow_tph");
        }
        BigDecimal flow = flowValue.decimalValue();

        Map<TypedId, FeatureIndex.IndexedFeature> inputsById = new HashMap<>();
        List<FeatureIndex.IndexedFeature> validationInputs = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : request.getInputs()) {
            if (input == null || input.getFeature() == null || input.getFeature().getId() == null
                    || input.getGeometry() == null || input.getGeometry().getSRID() != 32637) {
                throw new IllegalArgumentException("Every local input must have a typed ID and projected geometry");
            }
            TypedId id = input.getFeature().getId();
            if (inputsById.put(id, input) != null) throw new IllegalArgumentException("Duplicate local input ID: " + id);
            if (!"oks_connection_point".equals(input.getFeature().getObjectType())
                    || id.equals(targetInput.getId())) validationInputs.add(input);
        }
        if (!inputsById.containsKey(targetInput.getId())) {
            inputsById.put(targetInput.getId(), targetFeature);
            validationInputs.add(targetFeature);
        }
        if (!request.getSearchArea().covers(target.getX(), target.getY())
                || !request.getSearchArea().covers(request.getGoalPoint().getX(),
                        request.getGoalPoint().getY())) {
            return new RouteContext(flow, target, null, null, validationInputs, inputsById,
                    RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                    "Target or goal lies outside the configured search area");
        }

        AttachmentCandidate attachment = request.getAttachment();
        if (attachment != null && attachment.getEndpointKind() == AttachmentCandidate.EndpointKind.EXISTING_CHAMBER) {
            if (!inputsById.containsKey(attachment.getExistingChamberId())) {
                throw new IllegalArgumentException("Local inputs must include the selected existing chamber");
            }
        }
        for (TypedId networkId : attachment == null ? Collections.<TypedId>emptyList()
                : attachment.getConnectedNetworkIds()) {
            FeatureIndex.IndexedFeature network = inputsById.get(networkId);
            if (network == null || !"heat_network".equals(network.getFeature().getObjectType())) {
                throw new IllegalArgumentException("Local inputs must include every network attached to the candidate chamber");
            }
        }

        List<RouteObstacle> obstacles = new ArrayList<>();
        for (FeatureIndex.IndexedFeature input : inputsById.values()) {
            InputFeature feature = input.getFeature();
            String type = null;
            Double width = null;
            if ("restriction".equals(feature.getObjectType())) {
                JsonNode typeValue = feature.getProperties().getExtra().get("restriction_type");
                if (typeValue == null || !typeValue.isTextual()) {
                    throw new IllegalArgumentException("Restriction requires restriction_type: " + feature.getId());
                }
                type = typeValue.textValue();
                width = numericProperty(feature, "width_m");
            } else if ("heat_network".equals(feature.getObjectType())) {
                type = "heat_network";
                JsonNode diameterValue = feature.getProperties().getExtra().get("diameter");
                if (diameterValue == null || !diameterValue.canConvertToInt()) {
                    throw new IllegalArgumentException("Heat network requires a tabulated diameter: " + feature.getId());
                }
                width = RuleCatalog.diameter(diameterValue.intValue())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown existing network diameter: " + feature.getId()))
                        .getPairWidthM().doubleValue();
            } else {
                continue;
            }
            java.util.Optional<RestrictionRule> restrictionRule = RuleCatalog.restriction(type);
            if (!restrictionRule.isPresent()) throw new IllegalArgumentException("Unknown restriction type: " + type);
            RestrictionRule rule = restrictionRule.get();
            Geometry geometry = input.getGeometry();
            if (geometry.isEmpty() || !geometry.isValid()) {
                throw new IllegalArgumentException("Invalid local obstacle geometry: " + feature.getId());
            }
            if (geometry.getDimension() == 1 && ("road".equals(type) || "tram_tracks".equals(type))
                    && (width == null || !Double.isFinite(width) || width <= 0)) {
                throw new IllegalArgumentException("Linear road/tram obstacle requires positive width_m: " + feature.getId());
            }
            if ("gas_pipeline".equals(type) && width == null) width = 0.40;
            if ("power_cable".equals(type) && width == null) width = 0.20;
            obstacles.add(new RouteObstacle(feature.getId(), type, geometry, width,
                    rule.getCrossing() == RestrictionRule.Crossing.SPECIAL_PASSAGE));
        }
        obstacles.sort(Comparator.comparing(obstacle -> obstacle.id, ru.lct.heatnetwork.domain.TypedIdOrder.INSTANCE));

        List<RouteObstacle> ownBuildings = new ArrayList<>();
        for (RouteObstacle obstacle : obstacles) {
            if ("oks".equals(obstacle.type) && obstacle.geometry.contains(target)) ownBuildings.add(obstacle);
        }
        if (ownBuildings.size() > 1) throw new IllegalArgumentException("Target is inside multiple oks restrictions");
        if (attachment != null && !inputsById.containsKey(attachment.getNetworkId())) {
            throw new IllegalArgumentException("Local inputs must include the selected existing heat network");
        }
        RouteObstacle terminalNetwork = attachment == null ? null : obstacleById(obstacles, attachment.getNetworkId());
        if (attachment != null && (terminalNetwork == null || !"heat_network".equals(terminalNetwork.type)
                || terminalNetwork.geometry.distance(attachment.getEndpoint()) > 0.25)) {
            throw new IllegalArgumentException("Attachment endpoint must coincide with its existing heat network within 0.25 m");
        }
        return new RouteContext(flow, target, ownBuildings.isEmpty() ? null : ownBuildings.get(0),
                terminalNetwork, validationInputs, inputsById, null, null, obstacles,
                request.getTreeSegments(), request.getGoalForward());
    }

    private SearchOutcome search(SearchRequest request, RouteContext context, int diameterMm,
            ObstacleBufferCache bufferCache, SparseBudget sparseBudget) {
        Point target = context.target;
        Point goal = request.getGoalPoint();
        Point mandatoryVia = request.getMandatoryVia();
        if (target.distance(goal) <= NUMERIC_EPSILON_M) {
            return SearchOutcome.failure(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                    "Target and attachment coincide; a positive-length connection was not generated");
        }
        double pairWidth = RuleCatalog.diameter(diameterMm).get().getPairWidthM().doubleValue();
        Point graphStart = target;
        if (context.ownBuilding != null) {
            double ownClearance = clearance.requiredCenterlineDistanceM("oks", diameterMm,
                    RuleCatalog.diameter(diameterMm).get().getPairWidthM(), null);
            graphStart = ownBuildingEscape(target, context.ownBuilding.geometry, ownClearance,
                    request.getSearchArea(), request.isTreeSearch() ? OWN_BUILDING_CLEARANCE_MARGIN_M : 0.0);
            LineString approach = line(target.getCoordinate(), graphStart.getCoordinate(), target.getFactory());
            try {
                new ClearanceCheck().ownOksFinalApproach((LineString) approach.reverse(),
                        context.ownBuilding.geometry, target, context.ownBuilding.id, diameterMm);
            } catch (IllegalArgumentException exception) {
                return SearchOutcome.failure(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                        "The required own-building approach is invalid: " + exception.getMessage());
            }
            if (!edgeAllowed(approach, context, diameterMm, null, null, context.ownBuilding.id)) {
                return SearchOutcome.failure(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                        "The required straight final approach from the target conflicts with another obstacle");
            }
        }

        Envelope outerEnvelope = request.getSearchArea();
        if (!outerEnvelope.covers(graphStart.getX(), graphStart.getY())
                || !outerEnvelope.covers(goal.getX(), goal.getY())
                || (mandatoryVia != null && !outerEnvelope.covers(mandatoryVia.getCoordinate()))) {
            return SearchOutcome.failure(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                    "The local search area does not contain the required approach or attachment");
        }
        if (mandatoryVia != null && (mandatoryVia.distance(target) <= NUMERIC_EPSILON_M
                || mandatoryVia.distance(goal) <= NUMERIC_EPSILON_M)) {
            return SearchOutcome.failure(RouteCandidate.Status.INVALID_REQUEST,
                    "Mandatory corridor point must be internal to the route");
        }

        LineString direct = line(graphStart.getCoordinate(), goal.getCoordinate(), target.getFactory());
        if (mandatoryVia != null && graphStart.distance(mandatoryVia) > NUMERIC_EPSILON_M
                && (context.ownBuilding == null || legalTurn(target.getCoordinate(),
                        graphStart.getCoordinate(), mandatoryVia.getCoordinate()))
                && legalTurn(graphStart.getCoordinate(), mandatoryVia.getCoordinate(), goal.getCoordinate())) {
            LineString firstLeg = line(graphStart.getCoordinate(), mandatoryVia.getCoordinate(), target.getFactory());
            LineString secondLeg = line(mandatoryVia.getCoordinate(), goal.getCoordinate(), target.getFactory());
            if (edgeAllowed(firstLeg, context, diameterMm, false, goal, null)
                    && edgeAllowed(secondLeg, context, diameterMm, true, goal, null)) {
                List<Coordinate> coordinates = new ArrayList<>();
                coordinates.add(new Coordinate(target.getCoordinate()));
                if (target.distance(graphStart) > NUMERIC_EPSILON_M) {
                    coordinates.add(new Coordinate(graphStart.getCoordinate()));
                }
                coordinates.add(new Coordinate(mandatoryVia.getCoordinate()));
                coordinates.add(new Coordinate(goal.getCoordinate()));
                LineString throughCorridor = target.getFactory().createLineString(
                        coordinates.toArray(new Coordinate[0]));
                throughCorridor.setSRID(32637);
                return SearchOutcome.found(throughCorridor, false);
            }
        }
        if (mandatoryVia == null
                && (context.ownBuilding == null || legalTurn(target.getCoordinate(), graphStart.getCoordinate(), goal.getCoordinate()))
                && edgeAllowed(direct, context, diameterMm, true, goal, null)) {
            if (context.ownBuilding == null) return SearchOutcome.found(direct, false);
            List<Coordinate> coordinates = new ArrayList<>();
            coordinates.add(new Coordinate(target.getCoordinate()));
            if (target.distance(graphStart) > NUMERIC_EPSILON_M) {
                coordinates.add(new Coordinate(graphStart.getCoordinate()));
            }
            if (graphStart.distance(goal) > NUMERIC_EPSILON_M) {
                coordinates.add(new Coordinate(goal.getCoordinate()));
            }
            LineString completeDirect = target.getFactory().createLineString(coordinates.toArray(new Coordinate[0]));
            completeDirect.setSRID(32637);
            return SearchOutcome.found(completeDirect, false);
        }

        if (searchMode == SearchMode.CONSERVATIVE_TREE_NAVIGATOR && request.isTreeSearch()
                && mandatoryVia == null) {
            LineString local = localBranchConnector(request, context, diameterMm, graphStart, goal);
            if (local != null) return SearchOutcome.found(local, true);
        }

        Geometry outerArea = target.getFactory().toGeometry(outerEnvelope);
        outerArea.setSRID(32637);
        LineString corridorCenterline = mandatoryVia == null ? direct : target.getFactory().createLineString(
                new Coordinate[] {new Coordinate(graphStart.getCoordinate()),
                        new Coordinate(mandatoryVia.getCoordinate()), new Coordinate(goal.getCoordinate())});
        corridorCenterline.setSRID(32637);
        List<Geometry> searchAreas = localSearchAreas(corridorCenterline, outerArea);
        SearchOutcome last = SearchOutcome.failure(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                "No validated visibility-graph route was found inside the configured search area");
        for (Geometry area : searchAreas) {
            boolean fullSearchArea = area.covers(outerArea);
            SearchOutcome attempt = searchMode == SearchMode.ADAPTIVE_QUADTREE && mandatoryVia == null
                    ? searchWithinAdaptiveArea(request, context, diameterMm, graphStart, goal, area, bufferCache)
                    : searchWithinArea(request, context, diameterMm, graphStart, goal, area, bufferCache,
                            sparseBudget);
            if (attempt.status == null) {
                if (searchMode == SearchMode.CONSERVATIVE_TREE_NAVIGATOR && request.isTreeSearch()
                        && mandatoryVia == null) {
                    ObstacleSpatialIndex index = new ObstacleSpatialIndex(context.obstacles, diameterMm);
                    LineString shorter = shortenLocalDetour(attempt.route, graphStart, outerEnvelope, candidate -> {
                        int first = context.ownBuilding == null ? 0 : 1;
                        for (int i = first; i < candidate.getNumPoints() - 1; i++) {
                            LineString edge = line(candidate.getCoordinateN(i), candidate.getCoordinateN(i + 1),
                                    target.getFactory());
                            if (!edgeAllowed(edge, context, diameterMm, i == candidate.getNumPoints() - 2,
                                    goal, null, index)) return false;
                        }
                        return true;
                    });
                    return SearchOutcome.found(shorter, true);
                }
                return SearchOutcome.found(attempt.route, attempt.incomplete || !fullSearchArea);
            }
            if (attempt.status == RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED) return attempt;
            last = attempt;
            if (fullSearchArea) return last;
        }
        return last;
    }

    /** At most 240 small templates; free turning points need not be obstacle vertices. */
    private LineString localBranchConnector(SearchRequest request, RouteContext context, int diameterMm,
            Point start, Point goal) {
        Coordinate s = start.getCoordinate(), g = goal.getCoordinate();
        Coordinate outward = context.ownBuilding == null
                ? new Coordinate(g.x - s.x, g.y - s.y)
                : new Coordinate(s.x - context.target.getX(), s.y - context.target.getY());
        double norm = Math.hypot(outward.x, outward.y);
        if (norm <= NUMERIC_EPSILON_M) return null;
        double ux = outward.x / norm, uy = outward.y / norm;
        Coordinate forward = context.goalForward == null ? new Coordinate(g.x - s.x, g.y - s.y)
                : context.goalForward;
        double forwardNorm = Math.hypot(forward.x, forward.y);
        if (forwardNorm <= NUMERIC_EPSILON_M) return null;
        double fx = forward.x / forwardNorm, fy = forward.y / forwardNorm;
        List<Coordinate> entries = new ArrayList<>();
        entries.add(new Coordinate(g));
        for (double lead : new double[] {1.0, 5.0, 15.0}) {
            entries.add(new Coordinate(g.x - fx * lead, g.y - fy * lead));
            for (int side : new int[] {-1, 1}) {
                // Slightly sourceward arrival avoids a numerically obtuse exact right angle.
                entries.add(new Coordinate(g.x - fx * 0.05 - fy * lead * side,
                        g.y - fy * 0.05 + fx * lead * side));
            }
        }
        List<LineString> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (double advance : new double[] {0.0, 1.0, 5.0, 15.0}) {
            Coordinate a = new Coordinate(s.x + ux * advance, s.y + uy * advance);
            for (Coordinate entry : entries) {
                double along = (entry.x - a.x) * ux + (entry.y - a.y) * uy;
                Coordinate firstCorner = new Coordinate(a.x + ux * along, a.y + uy * along);
                Coordinate secondCorner = new Coordinate(entry.x - ux * along, entry.y - uy * along);
                for (Coordinate corner : new Coordinate[] {null, firstCorner, secondCorner}) {
                    List<Coordinate> path = new ArrayList<>();
                    appendDistinct(path, context.target.getCoordinate());
                    appendDistinct(path, s);
                    appendDistinct(path, a);
                    if (corner != null) appendDistinct(path, corner);
                    appendDistinct(path, entry);
                    appendDistinct(path, g);
                    addLocalConnector(candidates, seen, path, start, request.getSearchArea());
                    // Centimetre chamfers preserve the entry/exit directions and turn bounds.
                    List<Coordinate> chamfered = new ArrayList<>();
                    appendDistinct(chamfered, path.get(0));
                    for (int i = 1; i < path.size() - 1; i++) {
                        Coordinate before = path.get(i - 1), at = path.get(i), after = path.get(i + 1);
                        if (at.distance(s) < 1e-8) { appendDistinct(chamfered, at); continue; }
                        double cornerCosine = ((before.x - at.x) * (after.x - at.x)
                                + (before.y - at.y) * (after.y - at.y))
                                / (at.distance(before) * at.distance(after));
                        if (cornerCosine > 1e-8) {
                            // Do not turn a real hairpin into two tiny artificial bends.
                            appendDistinct(chamfered, at);
                            continue;
                        }
                        double cut = Math.min(0.01, Math.min(at.distance(before), at.distance(after)) / 4.0);
                        appendDistinct(chamfered, new Coordinate(at.x + (before.x - at.x) * cut / at.distance(before),
                                at.y + (before.y - at.y) * cut / at.distance(before)));
                        appendDistinct(chamfered, new Coordinate(at.x + (after.x - at.x) * cut / at.distance(after),
                                at.y + (after.y - at.y) * cut / at.distance(after)));
                    }
                    appendDistinct(chamfered, g);
                    addLocalConnector(candidates, seen, chamfered, start, request.getSearchArea());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(LineString::getLength));
        ObstacleSpatialIndex index = new ObstacleSpatialIndex(context.obstacles, diameterMm);
        for (LineString candidate : candidates) {
            boolean allowed = true;
            // The mandatory straight own-building exit was checked by search() already.
            int first = context.ownBuilding == null ? 0 : 1;
            for (int i = first; i < candidate.getNumPoints() - 1; i++) {
                LineString edge = line(candidate.getCoordinateN(i), candidate.getCoordinateN(i + 1),
                        start.getFactory());
                if (!edgeAllowed(edge, context, diameterMm, i == candidate.getNumPoints() - 2,
                        goal, null, index)) { allowed = false; break; }
            }
            if (allowed) return candidate;
        }
        return null;
    }

    private static void appendDistinct(List<Coordinate> path, Coordinate point) {
        if (path.isEmpty() || path.get(path.size() - 1).distance(point) > 1e-8) {
            path.add(new Coordinate(point));
        }
    }

    /** Replace a detouring prefix, retaining the checked tail and the fixed building exit. */
    static LineString shortenLocalDetour(LineString route, Point start, Envelope area,
            java.util.function.Predicate<LineString> allowed) {
        Coordinate[] original = route.getCoordinates();
        Coordinate s = start.getCoordinate(), target = original[0];
        double norm = s.distance(target);
        if (norm < 1e-8 || original.length < 4) return route;
        double ux = (s.x - target.x) / norm, uy = (s.y - target.y) / norm;
        LineString best = route;
        List<LineString> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (double advance : new double[] {0, 1, 2, 5, 15}) {
            Coordinate a = new Coordinate(s.x + ux * advance, s.y + uy * advance);
            for (Coordinate direction : new Coordinate[] {new Coordinate(ux, uy),
                    new Coordinate(-uy, ux), new Coordinate(uy, -ux)}) {
                for (int i = 2; i < Math.min(original.length - 1, 98); i++) {
                    Coordinate b = original[i], c = original[i + 1];
                    double ex = c.x - b.x, ey = c.y - b.y;
                    double denominator = direction.x * ey - direction.y * ex;
                    if (Math.abs(denominator) < 1e-9) continue;
                    double dx = b.x - a.x, dy = b.y - a.y;
                    double ray = (dx * ey - dy * ex) / denominator;
                    double fraction = (dx * direction.y - dy * direction.x) / denominator;
                    if (ray <= 1e-8 || fraction < 0 || fraction > 1) continue;
                    Coordinate join = new Coordinate(b.x + fraction * ex, b.y + fraction * ey);
                    List<Coordinate> path = new ArrayList<>();
                    appendDistinct(path, target);
                    appendDistinct(path, s);
                    appendDistinct(path, a);
                    appendDistinct(path, join);
                    for (int tail = i + 1; tail < original.length; tail++) appendDistinct(path, original[tail]);
                    addLocalConnector(candidates, seen, path, start, area);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(LineString::getLength));
        for (LineString candidate : candidates) {
            if (candidate.getLength() + 0.01 >= best.getLength()) break;
            if (allowed.test(candidate)) return candidate;
        }
        return best;
    }

    private static void addLocalConnector(List<LineString> candidates, Set<String> seen,
            List<Coordinate> path, Point start, Envelope area) {
        if (!legalPolylineTurns(path, null) || path.stream().anyMatch(point -> !area.covers(point))) return;
        LineString line = start.getFactory().createLineString(path.toArray(new Coordinate[0]));
        if (line.isSimple() && seen.add(line.toText())) candidates.add(line);
    }

    private SearchOutcome searchWithinAdaptiveArea(SearchRequest request, RouteContext context,
            int diameterMm, Point graphStart, Point goal, Geometry area, ObstacleBufferCache bufferCache) {
        long startedNanos = System.nanoTime();
        Envelope areaEnvelope = area.getEnvelopeInternal();
        List<RouteObstacle> relevantObstacles = new ArrayList<>();
        List<Geometry> blockedRegions = new ArrayList<>();
        List<Geometry> refinementRegions = new ArrayList<>();
        List<Geometry> specialRegions = new ArrayList<>();
        for (RouteObstacle obstacle : context.obstacles) {
            double threshold = clearance.requiredCenterlineDistanceM(obstacle.type, diameterMm,
                    RuleCatalog.diameter(diameterMm).get().getPairWidthM(), obstacle.widthM);
            Envelope expandedEnvelope = new Envelope(obstacle.geometry.getEnvelopeInternal());
            expandedEnvelope.expandBy(threshold + SEARCH_VERTEX_MARGIN_M);
            boolean requiredApproachObstacle = context.ownBuilding != null
                    && obstacle.id.equals(context.ownBuilding.id);
            boolean requiredTerminalObstacle = context.terminalNetwork != null
                    && obstacle.id.equals(context.terminalNetwork.id);
            if (!requiredApproachObstacle && !requiredTerminalObstacle
                    && !expandedEnvelope.intersects(areaEnvelope)) continue;
            Geometry expanded = bufferCache.buffer(obstacle.geometry, threshold + SEARCH_VERTEX_MARGIN_M,
                    BUFFER_QUADRANT_SEGMENTS);
            relevantObstacles.add(obstacle);
            if (!expanded.getEnvelopeInternal().intersects(areaEnvelope)) continue;
            refinementRegions.add(expanded);
            if (obstacle.special) specialRegions.add(obstacle.geometry);
            else blockedRegions.add(expanded);
        }

        RouteContext searchContext = new RouteContext(context.flowTph, context.target,
                context.ownBuilding, context.terminalNetwork, context.validationInputs,
                context.inputsById, null, null, relevantObstacles, context.treeSegments,
                context.goalForward);
        ObstacleSpatialIndex obstacleIndex = new ObstacleSpatialIndex(searchContext.obstacles, diameterMm);
        int base = request.getMaximumVisibilityVertices();
        int maximumCells = Math.max(2048, Math.min(12000, base * 32));
        int maximumEdgeChecks = Math.max(10000, Math.min(120000, base * base));
        AdaptiveQuadtreePathfinder.SearchResult search = new AdaptiveQuadtreePathfinder().search(
                area, graphStart.getCoordinate(), goal.getCoordinate(), context.target.getFactory(),
                blockedRegions, refinementRegions, specialRegions, maximumCells, maximumEdgeChecks,
                ADAPTIVE_MINIMUM_CELL_SIZE_M,
                edge -> {
                    Coordinate[] coordinates = edge.getCoordinates();
                    boolean goalEdge = coordinates[0].equals2D(goal.getCoordinate())
                            || coordinates[coordinates.length - 1].equals2D(goal.getCoordinate());
                    return edgeAllowed(edge, searchContext, diameterMm, goalEdge, goal, null, obstacleIndex);
                },
                SingleTargetRouter::legalTurn);
        long elapsed = elapsedMillis(startedNanos);
        if (search.getStatus() != AdaptiveQuadtreePathfinder.Status.FOUND
                && search.getStatus() != AdaptiveQuadtreePathfinder.Status.INCOMPLETE) {
            RouteCandidate.Status status = search.getStatus() == AdaptiveQuadtreePathfinder.Status.INCOMPLETE
                    ? RouteCandidate.Status.SEARCH_INCOMPLETE : RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA;
            LOGGER.info("Adaptive route attempt target={} request={} result={} elapsedMs={} cells={} graphNodes={} graphEdges={} exactChecks={} unresolvedCells={} balanceViolations={}",
                    request.getTarget().getFeature().getId(), request.getVariantId(), status, elapsed,
                    search.getCells(), search.getNodes(), search.getEdges(), search.getEdgeChecks(),
                    search.getUnresolvedCells(), search.getBalanceViolations());
            return SearchOutcome.failure(status, search.getDetail());
        }
        LineString candidate = search.getRoute();
        if (candidate == null) {
            LOGGER.info("Adaptive route attempt target={} request={} result={} elapsedMs={} cells={} graphNodes={} graphEdges={} exactChecks={} unresolvedCells={} balanceViolations={}",
                    request.getTarget().getFeature().getId(), request.getVariantId(),
                    RouteCandidate.Status.SEARCH_INCOMPLETE, elapsed, search.getCells(), search.getNodes(),
                    search.getEdges(), search.getEdgeChecks(), search.getUnresolvedCells(), search.getBalanceViolations());
            return SearchOutcome.failure(RouteCandidate.Status.SEARCH_INCOMPLETE, search.getDetail());
        }
        List<Coordinate> graphPath = new ArrayList<>(java.util.Arrays.asList(candidate.getCoordinates()));
        graphPath = simplify(graphPath, searchContext, diameterMm, context.target.getCoordinate(),
                context.ownBuilding != null, goal, area, context.target.getFactory(), obstacleIndex, null);
        List<Coordinate> routeCoordinates = new ArrayList<>();
        routeCoordinates.add(new Coordinate(context.target.getCoordinate()));
        for (Coordinate coordinate : graphPath) {
            if (routeCoordinates.get(routeCoordinates.size() - 1).distance(coordinate) > NUMERIC_EPSILON_M) {
                routeCoordinates.add(new Coordinate(coordinate));
            }
        }
        if (routeCoordinates.get(routeCoordinates.size() - 1).distance(goal.getCoordinate()) > 0.25) {
            return SearchOutcome.failure(RouteCandidate.Status.SEARCH_INCOMPLETE,
                    "Adaptive graph path did not terminate at the attachment candidate");
        }
        LineString route = context.target.getFactory().createLineString(routeCoordinates.toArray(new Coordinate[0]));
        route.setSRID(32637);
        if (route.isEmpty() || route.getLength() <= 0 || !route.isValid()) {
            return SearchOutcome.failure(RouteCandidate.Status.SEARCH_INCOMPLETE,
                    "Adaptive graph produced invalid route geometry");
        }
        if (!legalPolylineTurns(route)) {
            return SearchOutcome.failure(RouteCandidate.Status.SEARCH_INCOMPLETE,
                    "Adaptive graph produced a route with an internal turn over 90 degrees");
        }
        LOGGER.info("Adaptive route attempt target={} request={} result={} elapsedMs={} cells={} graphNodes={} graphEdges={} exactChecks={} unresolvedCells={} balanceViolations={} routeLengthM={}",
                request.getTarget().getFeature().getId(), request.getVariantId(),
                search.getStatus() == AdaptiveQuadtreePathfinder.Status.FOUND
                        ? RouteCandidate.Status.FOUND : RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH,
                elapsed, search.getCells(), search.getNodes(), search.getEdges(), search.getEdgeChecks(),
                search.getUnresolvedCells(), search.getBalanceViolations(), route.getLength());
        return SearchOutcome.found(route, search.getStatus() == AdaptiveQuadtreePathfinder.Status.INCOMPLETE);
    }

    private SearchOutcome searchWithinArea(SearchRequest request, RouteContext context, int diameterMm,
            Point graphStart, Point goal, Geometry area, ObstacleBufferCache bufferCache,
            SparseBudget sparseBudget) {
        long candidateBuildStartedNanos = System.nanoTime();
        Point target = context.target;
        Point mandatoryVia = request.getMandatoryVia();

        List<Coordinate> vertices = new ArrayList<>();
        Map<String, Integer> vertexIds = new HashMap<>();
        addVertex(vertices, vertexIds, graphStart.getCoordinate());
        addVertex(vertices, vertexIds, goal.getCoordinate());
        if (mandatoryVia != null) addVertex(vertices, vertexIds, mandatoryVia.getCoordinate());
        int viaIndex = mandatoryVia == null ? -1 : vertexIds.get(coordinateKey(mandatoryVia.getCoordinate()));
        List<ObstacleVertices> obstacleVertices = new ArrayList<>();
        List<RouteObstacle> relevantObstacles = new ArrayList<>();
        Envelope areaEnvelope = area.getEnvelopeInternal();
        for (RouteObstacle obstacle : context.obstacles) {
            double threshold = clearance.requiredCenterlineDistanceM(obstacle.type, diameterMm,
                    RuleCatalog.diameter(diameterMm).get().getPairWidthM(), obstacle.widthM);
            Envelope expandedEnvelope = new Envelope(obstacle.geometry.getEnvelopeInternal());
            expandedEnvelope.expandBy(threshold + SEARCH_VERTEX_MARGIN_M);
            boolean requiredApproachObstacle = context.ownBuilding != null
                    && obstacle.id.equals(context.ownBuilding.id);
            boolean requiredTerminalObstacle = context.terminalNetwork != null
                    && obstacle.id.equals(context.terminalNetwork.id);
            if (!requiredApproachObstacle && !requiredTerminalObstacle
                    && !expandedEnvelope.intersects(areaEnvelope)) continue;
            Geometry expanded = bufferCache.buffer(obstacle.geometry, threshold + SEARCH_VERTEX_MARGIN_M,
                    BUFFER_QUADRANT_SEGMENTS);
            relevantObstacles.add(obstacle);
            if (!expanded.getEnvelopeInternal().intersects(areaEnvelope)) continue;
            List<Coordinate> points = clippedBoundaryCoordinates(expanded, area);
            if (requiredApproachObstacle) addOwnBuildingSteeringVertices(points, expanded, area);
            if (points.isEmpty()) continue;
            obstacleVertices.add(new ObstacleVertices(obstacle.id, obstacle.geometry,
                    threshold + SEARCH_VERTEX_MARGIN_M, expanded, points));
        }

        int uniqueVertexCount = uniqueVertexCount(graphStart, goal, mandatoryVia, obstacleVertices);
        int verticesBeforeHullReduction = uniqueVertexCount;
        boolean approximatedBoundaries = false;
        if ((searchMode == SearchMode.CONSERVATIVE_FOREST || searchMode == SearchMode.CONSERVATIVE_EXHAUSTIVE
                || searchMode == SearchMode.CONSERVATIVE_DIRECT_BRANCH
                || searchMode == SearchMode.CONSERVATIVE_SPATIAL
                || searchMode == SearchMode.CONSERVATIVE_TREE_NAVIGATOR)
                && uniqueVertexCount > request.getMaximumVisibilityVertices()) {
            List<ObstacleVertices> largestFirst = new ArrayList<>(obstacleVertices);
            largestFirst.sort(Comparator.comparingInt((ObstacleVertices item) -> item.coordinates.size()).reversed()
                    .thenComparing(item -> item.id, ru.lct.heatnetwork.domain.TypedIdOrder.INSTANCE));
            for (ObstacleVertices item : largestFirst) {
                Geometry coarse = conservativeNavigationBuffer(item.source, item.expanded,
                        item.bufferRadiusM, bufferCache);
                if (coarse == null) continue;
                List<Coordinate> coarseCoordinates = clippedBoundaryCoordinates(coarse, area);
                if (context.ownBuilding != null && item.id.equals(context.ownBuilding.id)) {
                    addOwnBuildingSteeringVertices(coarseCoordinates, coarse, area);
                }
                if (coarseCoordinates.size() >= item.coordinates.size()) continue;
                item.expanded = coarse;
                item.coordinates = coarseCoordinates;
                approximatedBoundaries = true;
                uniqueVertexCount = uniqueVertexCount(graphStart, goal, mandatoryVia, obstacleVertices);
                if (uniqueVertexCount <= request.getMaximumVisibilityVertices()) break;
            }
        }
        if (uniqueVertexCount > request.getMaximumVisibilityVertices()) {
            List<ObstacleVertices> largestFirst = new ArrayList<>(obstacleVertices);
            largestFirst.sort(Comparator.comparingInt((ObstacleVertices item) -> item.coordinates.size()).reversed()
                    .thenComparing(item -> item.id, ru.lct.heatnetwork.domain.TypedIdOrder.INSTANCE));
            for (ObstacleVertices item : largestFirst) {
                List<Coordinate> hullCoordinates = clippedBoundaryCoordinates(item.expanded.convexHull(), area);
                if (hullCoordinates.size() >= item.coordinates.size()) continue;
                item.coordinates = hullCoordinates;
                approximatedBoundaries = true;
                uniqueVertexCount = uniqueVertexCount(graphStart, goal, mandatoryVia, obstacleVertices);
                if (uniqueVertexCount <= request.getMaximumVisibilityVertices()) break;
            }
        }
        boolean sparseFallback = (searchMode == SearchMode.CONSERVATIVE_SPATIAL
                || searchMode == SearchMode.CONSERVATIVE_TREE_NAVIGATOR)
                && uniqueVertexCount > request.getMaximumVisibilityVertices();
        int sparseVertexLimit = searchMode == SearchMode.CONSERVATIVE_TREE_NAVIGATOR
                ? NAVIGATOR_SPARSE_MAX_VERTICES : SPARSE_MAX_VERTICES;
        if (uniqueVertexCount > request.getMaximumVisibilityVertices()
                && (!sparseFallback || uniqueVertexCount > sparseVertexLimit)) {
            List<ObstacleVertices> largestFirst = new ArrayList<>(obstacleVertices);
            largestFirst.sort(Comparator.comparingInt((ObstacleVertices item) -> item.coordinates.size()).reversed()
                    .thenComparing(item -> item.id, ru.lct.heatnetwork.domain.TypedIdOrder.INSTANCE));
            String largestObstacles = largestFirst.stream().limit(5)
                    .map(item -> item.id + ":" + item.coordinates.size())
                    .collect(java.util.stream.Collectors.joining(","));
            TypedId targetId = request.getTarget().getFeature().getId();
            LOGGER.info("Visibility route attempt target={} request={} result={} phase=boundary-reduction elapsedMs={} beforeHull={} afterHull={} limit={} obstacles={} largest={}",
                    targetId, request.getVariantId(), sparseFallback ? RouteCandidate.Status.SEARCH_INCOMPLETE
                            : RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED,
                    elapsedMillis(candidateBuildStartedNanos), verticesBeforeHullReduction, uniqueVertexCount,
                    request.getMaximumVisibilityVertices(), obstacleVertices.size(), largestObstacles);
            return SearchOutcome.failure(sparseFallback ? RouteCandidate.Status.SEARCH_INCOMPLETE
                            : RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED,
                    "Relevant obstacle boundaries contain " + uniqueVertexCount
                            + " vertices after convex-hull reduction; configured limit is "
                            + (sparseFallback ? sparseVertexLimit : request.getMaximumVisibilityVertices()));
        }
        for (ObstacleVertices item : obstacleVertices) {
            for (Coordinate coordinate : item.coordinates) addVertex(vertices, vertexIds, coordinate);
        }
        if (vertices.size() > request.getMaximumVisibilityVertices()
                && (!sparseFallback || vertices.size() > sparseVertexLimit)) {
            LOGGER.info("Visibility route attempt target={} request={} result={} phase=vertex-materialization elapsedMs={} beforeHull={} afterHull={} graphVertices={} graphEdges=0 obstacles={}",
                    request.getTarget().getFeature().getId(), request.getVariantId(),
                    sparseFallback ? RouteCandidate.Status.SEARCH_INCOMPLETE
                            : RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED,
                    elapsedMillis(candidateBuildStartedNanos), verticesBeforeHullReduction, uniqueVertexCount,
                    vertices.size(), obstacleVertices.size());
            return SearchOutcome.failure(sparseFallback ? RouteCandidate.Status.SEARCH_INCOMPLETE
                            : RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED,
                    "Relevant obstacle boundaries exceed the configured visibility-vertex limit");
        }

        int count = vertices.size();
        long candidateBuildFinishedNanos = System.nanoTime();
        long candidateBuildMillis = (candidateBuildFinishedNanos - candidateBuildStartedNanos) / 1_000_000L;
        long graphBuildStartedNanos = candidateBuildFinishedNanos;
        // Obstacle vertices are clipped through area.covers(point); in a convex area, covered endpoints imply
        // that every visibility segment between them is covered as well.
        boolean segmentCoverageIsGuaranteed = canSkipAreaEdgeCoverage(area, graphStart, goal)
                && (mandatoryVia == null || area.covers(mandatoryVia));
        RouteContext searchContext = new RouteContext(context.flowTph, target, context.ownBuilding,
                context.terminalNetwork, context.validationInputs, context.inputsById, null, null,
                relevantObstacles, context.treeSegments, context.goalForward);
        ObstacleSpatialIndex obstacleIndex = new ObstacleSpatialIndex(searchContext.obstacles, diameterMm);
        int graphEdgeCount;
        int settledStateCount;
        long graphBuildMillis;
        long pathSearchMillis;
        List<Coordinate> graphPath;
        boolean sparseBudgetExhausted = false;
        boolean sparseStateLimitReached = false;
        if (sparseFallback) {
            SparseGraphResult sparse = searchSparseGraph(vertices, area, segmentCoverageIsGuaranteed,
                    searchContext, diameterMm, goal, target, obstacleIndex, sparseBudget, viaIndex);
            graphEdgeCount = sparse.graphEdges;
            settledStateCount = sparse.settledStates;
            graphBuildMillis = sparse.graphBuildMillis;
            pathSearchMillis = sparse.pathSearchMillis;
            graphPath = sparse.path;
            sparseBudgetExhausted = sparse.budgetExhausted;
            sparseStateLimitReached = sparse.stateLimitReached;
        } else {
            long preparedNanos = System.nanoTime() - graphBuildStartedNanos;
            DenseGraphResult dense = searchDenseGraph(vertices, context.ownBuilding == null
                    ? null : target.getCoordinate(), viaIndex, (first, second) -> {
                LineString edge = line(vertices.get(first), vertices.get(second), target.getFactory());
                return edge.getLength() > NUMERIC_EPSILON_M
                        && (segmentCoverageIsGuaranteed || area.covers(edge))
                        && edgeAllowed(edge, searchContext, diameterMm, first == 1 || second == 1,
                                goal, null, obstacleIndex);
            });
            graphBuildMillis = (preparedNanos + dense.graphNanos) / 1_000_000L;
            pathSearchMillis = dense.searchNanos / 1_000_000L;
            graphPath = dense.path;
            settledStateCount = dense.settledStates;
            graphEdgeCount = dense.graphEdges;
        }
        if (graphPath == null) {
            RouteCandidate.Status status = sparseFallback || approximatedBoundaries
                    ? RouteCandidate.Status.SEARCH_INCOMPLETE
                    : RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA;
            logSearchAttempt(request, status, candidateBuildStartedNanos, candidateBuildMillis,
                    graphBuildMillis, pathSearchMillis, verticesBeforeHullReduction,
                    uniqueVertexCount, count, graphEdgeCount, obstacleVertices.size(), settledStateCount, "n/a");
            return sparseFallback
                    ? SearchOutcome.failure(status, sparseStateLimitReached
                            ? "Sparse path-state budget exhausted before finding a route"
                            : sparseBudgetExhausted
                                    ? "Sparse exact-edge budget exhausted before finding a route"
                                    : "No route was found in the bounded sparse neighbor graph")
                    : approximatedBoundaries
                    ? SearchOutcome.failure(status,
                            "No route was found after conservative obstacle envelopes reduced visibility candidates")
                    : SearchOutcome.failure(status,
                            "No validated visibility-graph route was found inside the configured search area");
        }

        graphPath = simplify(graphPath, searchContext, diameterMm, target.getCoordinate(),
                context.ownBuilding != null, goal, area, target.getFactory(), obstacleIndex,
                mandatoryVia == null ? null : mandatoryVia.getCoordinate());
        List<Coordinate> routeCoordinates = new ArrayList<>();
        routeCoordinates.add(new Coordinate(target.getCoordinate()));
        for (Coordinate coordinate : graphPath) {
            if (routeCoordinates.get(routeCoordinates.size() - 1).distance(coordinate) > NUMERIC_EPSILON_M) {
                routeCoordinates.add(new Coordinate(coordinate));
            }
        }
        if (routeCoordinates.get(routeCoordinates.size() - 1).distance(goal.getCoordinate()) > 0.25) {
            logSearchAttempt(request, RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                    candidateBuildStartedNanos, candidateBuildMillis, graphBuildMillis,
                    pathSearchMillis, verticesBeforeHullReduction, uniqueVertexCount,
                    count, graphEdgeCount, obstacleVertices.size(), settledStateCount, "n/a");
            return SearchOutcome.failure(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                    "Search result does not terminate at the attachment candidate");
        }
        if (mandatoryVia != null && routeCoordinates.stream()
                .noneMatch(coordinate -> coordinate.distance(mandatoryVia.getCoordinate()) <= NUMERIC_EPSILON_M)) {
            return SearchOutcome.failure(RouteCandidate.Status.SEARCH_INCOMPLETE,
                    "Visibility route did not retain its mandatory corridor point");
        }
        LineString route = target.getFactory().createLineString(routeCoordinates.toArray(new Coordinate[0]));
        route.setSRID(32637);
        if (route.isEmpty() || route.getLength() <= 0 || !route.isValid()) {
            logSearchAttempt(request, RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                    candidateBuildStartedNanos, candidateBuildMillis, graphBuildMillis,
                    pathSearchMillis, verticesBeforeHullReduction, uniqueVertexCount,
                    count, graphEdgeCount, obstacleVertices.size(), settledStateCount, "n/a");
            return SearchOutcome.failure(RouteCandidate.Status.NO_ROUTE_WITHIN_SEARCH_AREA,
                    "Visibility search produced invalid route geometry");
        }
        if (!legalPolylineTurns(route)) {
            return SearchOutcome.failure(RouteCandidate.Status.SEARCH_INCOMPLETE,
                    "Visibility search produced a route with an internal turn over 90 degrees");
        }
        RouteCandidate.Status foundStatus = sparseFallback || approximatedBoundaries
                ? RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH : RouteCandidate.Status.FOUND;
        logSearchAttempt(request, foundStatus, candidateBuildStartedNanos,
                candidateBuildMillis, graphBuildMillis, pathSearchMillis,
                verticesBeforeHullReduction, uniqueVertexCount, count, graphEdgeCount,
                obstacleVertices.size(), settledStateCount, Double.toString(route.getLength()));
        return SearchOutcome.found(route, sparseFallback || approximatedBoundaries);
    }

    private SparseGraphResult searchSparseGraph(List<Coordinate> vertices, Geometry area,
            boolean segmentCoverageIsGuaranteed, RouteContext context, int diameterMm,
            Point goal, Point target, ObstacleSpatialIndex obstacleIndex, SparseBudget budget,
            int viaIndex) {
        if (budget.exhausted()) return new SparseGraphResult(null, 0, 0, 0, 0, true, false);
        long rankingStarted = System.nanoTime();
        Map<Long, Integer> ranks = sparseNeighborRanks(vertices);
        if (viaIndex >= 0) {
            int startLow = Math.min(0, viaIndex), startHigh = Math.max(0, viaIndex);
            int goalLow = Math.min(1, viaIndex), goalHigh = Math.max(1, viaIndex);
            ranks.merge(((long) startLow << 32) | startHigh, 0, Math::min);
            ranks.merge(((long) goalLow << 32) | goalHigh, 0, Math::min);
        }
        int count = vertices.size();
        List<List<SparseCandidate>> neighbors = new ArrayList<>(count);
        for (int i = 0; i < count; i++) neighbors.add(new ArrayList<>());
        for (Map.Entry<Long, Integer> entry : ranks.entrySet()) {
            int first = (int) (entry.getKey() >>> 32);
            int second = (int) (entry.getKey().longValue() & 0xffffffffL);
            double distance = vertices.get(first).distance(vertices.get(second));
            neighbors.get(first).add(new SparseCandidate(second, entry.getValue(), distance));
            neighbors.get(second).add(new SparseCandidate(first, entry.getValue(), distance));
        }
        Comparator<SparseCandidate> candidateOrder = Comparator
                .comparingInt((SparseCandidate candidate) -> candidate.rank)
                .thenComparingDouble(candidate -> candidate.distance)
                .thenComparingInt(candidate -> candidate.index);
        for (List<SparseCandidate> adjacent : neighbors) adjacent.sort(candidateOrder);
        long graphBuildNanos = System.nanoTime() - rankingStarted;
        long pathSearchNanos = 0;
        BitSet checked = new BitSet(count * count);
        List<List<Integer>> adjacency = new ArrayList<>(count);
        for (int i = 0; i < count; i++) adjacency.add(new ArrayList<>());
        int[] expandedDensity = new int[count];
        int[] graphEdges = {0};
        int settledStates = 0;
        for (int density : new int[] {2, 4, 8}) {
            final int selectedDensity = density;
            long[] checkedNanos = {0};
            IntConsumer expand = current -> {
                if (expandedDensity[current] >= selectedDensity || budget.exhausted()) return;
                long started = System.nanoTime();
                for (SparseCandidate candidate : neighbors.get(current)) {
                    if (candidate.rank >= selectedDensity) break;
                    int first = Math.min(current, candidate.index);
                    int second = Math.max(current, candidate.index);
                    int pairIndex = first * count + second;
                    if (checked.get(pairIndex)) continue;
                    LineString edge = line(vertices.get(first), vertices.get(second), target.getFactory());
                    if (edge.getLength() <= NUMERIC_EPSILON_M
                            || (!segmentCoverageIsGuaranteed && !area.covers(edge))) {
                        checked.set(pairIndex);
                        continue;
                    }
                    if (!budget.consume()) break;
                    checked.set(pairIndex);
                    boolean goalEdge = first == 1 || second == 1;
                    if (edgeAllowed(edge, context, diameterMm, goalEdge, goal, null, obstacleIndex)) {
                        adjacency.get(first).add(second);
                        adjacency.get(second).add(first);
                        graphEdges[0]++;
                    }
                }
                expandedDensity[current] = selectedDensity;
                checkedNanos[0] += System.nanoTime() - started;
            };
            long pathStarted = System.nanoTime();
            PathSearchResult path = shortestPath(adjacency, vertices,
                    context.ownBuilding == null ? null : target.getCoordinate(), 250000, expand,
                    viaIndex);
            long elapsed = System.nanoTime() - pathStarted;
            graphBuildNanos += checkedNanos[0];
            pathSearchNanos += Math.max(0, elapsed - checkedNanos[0]);
            settledStates += path.settledStates;
            if (path.path != null) {
                return new SparseGraphResult(path.path, graphEdges[0], settledStates,
                        graphBuildNanos / 1_000_000L, pathSearchNanos / 1_000_000L,
                        budget.exhausted(), false);
            }
            if (path.stateLimitReached) {
                return new SparseGraphResult(null, graphEdges[0], settledStates,
                        graphBuildNanos / 1_000_000L, pathSearchNanos / 1_000_000L,
                        budget.exhausted(), true);
            }
            if (budget.exhausted()) break;
        }
        return new SparseGraphResult(null, graphEdges[0], settledStates,
                graphBuildNanos / 1_000_000L, pathSearchNanos / 1_000_000L,
                budget.exhausted(), false);
    }

    /** Minimum rank (within an angular sector) of each undirected candidate pair. */
    static Map<Long, Integer> sparseNeighborRanks(List<Coordinate> vertices) {
        if (vertices == null || vertices.size() < 2) throw new IllegalArgumentException("At least two vertices are required");
        Map<Long, Integer> pairs = new TreeMap<>();
        Envelope bounds = new Envelope();
        STRtree spatial = new STRtree();
        for (int index = 0; index < vertices.size(); index++) {
            Coordinate coordinate = vertices.get(index);
            bounds.expandToInclude(coordinate);
            spatial.insert(new Envelope(coordinate), index);
        }
        spatial.build();
        double diagonal = Math.hypot(bounds.getWidth(), bounds.getHeight());
        double initialRadius = Math.max(1.0,
                Math.sqrt(Math.max(1.0, bounds.getWidth() * bounds.getHeight()) / vertices.size()));
        Comparator<SparseNeighbor> nearestFirst = Comparator
                .comparingDouble((SparseNeighbor neighbor) -> neighbor.distanceSquared)
                .thenComparingInt(neighbor -> neighbor.index);
        for (int first = 0; first < vertices.size(); first++) {
            List<PriorityQueue<SparseNeighbor>> sectors = new ArrayList<>(8);
            for (int sector = 0; sector < 8; sector++) {
                sectors.add(new PriorityQueue<>(nearestFirst.reversed()));
            }
            Coordinate from = vertices.get(first);
            List<Integer> nearby;
            if (vertices.size() <= 64) {
                nearby = new ArrayList<>(vertices.size());
                for (int index = 0; index < vertices.size(); index++) nearby.add(index);
            } else {
                double radius = initialRadius;
                do {
                    List<?> matches = spatial.query(new Envelope(from.x - radius, from.x + radius,
                            from.y - radius, from.y + radius));
                    nearby = new ArrayList<>(matches.size());
                    for (Object match : matches) nearby.add((Integer) match);
                    if (nearby.size() >= 65 || radius >= diagonal) break;
                    radius *= 2.0;
                } while (true);
            }
            for (int second : nearby) {
                if (first == second) continue;
                Coordinate to = vertices.get(second);
                double dx = to.x - from.x, dy = to.y - from.y;
                double distanceSquared = dx * dx + dy * dy;
                if (distanceSquared <= NUMERIC_EPSILON_M * NUMERIC_EPSILON_M) continue;
                int sector = (int) Math.floor((Math.atan2(dy, dx) + Math.PI) * (4.0 / Math.PI));
                sector = Math.min(7, Math.max(0, sector));
                PriorityQueue<SparseNeighbor> nearest = sectors.get(sector);
                SparseNeighbor candidate = new SparseNeighbor(second, distanceSquared);
                if (nearest.size() < 8) nearest.add(candidate);
                else if (nearestFirst.compare(candidate, nearest.peek()) < 0) {
                    nearest.remove();
                    nearest.add(candidate);
                }
            }
            for (PriorityQueue<SparseNeighbor> queue : sectors) {
                List<SparseNeighbor> sorted = new ArrayList<>(queue);
                sorted.sort(nearestFirst);
                for (int rank = 0; rank < sorted.size(); rank++) {
                    int second = sorted.get(rank).index;
                    int low = Math.min(first, second), high = Math.max(first, second);
                    long key = ((long) low << 32) | high;
                    pairs.merge(key, rank, Math::min);
                }
            }
        }
        pairs.merge(1L, 0, Math::min); // Always test the start-goal pair.
        return pairs;
    }

    static DenseGraphResult searchDenseGraph(List<Coordinate> vertices, Coordinate fixedApproach,
            int viaIndex, java.util.function.BiPredicate<Integer, Integer> allowed) {
        int count = vertices.size();
        List<List<Integer>> adjacency = new ArrayList<>(count);
        for (int i = 0; i < count; i++) adjacency.add(new ArrayList<>());
        BitSet checked = new BitSet(count * count);
        boolean[] expanded = new boolean[count];
        int[] graphEdges = {0}, checks = {0};
        long[] graphNanos = {0};
        IntConsumer expand = current -> {
            if (expanded[current]) return;
            long started = System.nanoTime();
            for (int next = 0; next < count; next++) {
                if (current == next) continue;
                int first = Math.min(current, next), second = Math.max(current, next);
                int pair = first * count + second;
                if (checked.get(pair)) continue;
                checked.set(pair);
                checks[0]++;
                if (allowed.test(first, second)) {
                    adjacency.get(first).add(second);
                    adjacency.get(second).add(first);
                    graphEdges[0]++;
                }
            }
            // Match the eager graph's stable neighbor order even when an edge was discovered earlier.
            Collections.sort(adjacency.get(current));
            expanded[current] = true;
            graphNanos[0] += System.nanoTime() - started;
        };
        long started = System.nanoTime();
        PathSearchResult result = shortestPath(adjacency, vertices, fixedApproach,
                Integer.MAX_VALUE, expand, viaIndex);
        long elapsed = System.nanoTime() - started;
        return new DenseGraphResult(result.path, result.settledStates, graphEdges[0], checks[0],
                graphNanos[0], Math.max(0, elapsed - graphNanos[0]));
    }

    static final class DenseGraphResult {
        final List<Coordinate> path;
        final int settledStates, graphEdges, exactChecks;
        final long graphNanos, searchNanos;
        DenseGraphResult(List<Coordinate> path, int settledStates, int graphEdges, int exactChecks,
                long graphNanos, long searchNanos) {
            this.path = path; this.settledStates = settledStates; this.graphEdges = graphEdges;
            this.exactChecks = exactChecks; this.graphNanos = graphNanos; this.searchNanos = searchNanos;
        }
    }

    private static PathSearchResult shortestPath(List<List<Integer>> adjacency,
            List<Coordinate> vertices, Coordinate fixedApproach) {
        return shortestPath(adjacency, vertices, fixedApproach, -1);
    }

    private static PathSearchResult shortestPath(List<List<Integer>> adjacency,
            List<Coordinate> vertices, Coordinate fixedApproach, int viaIndex) {
        return shortestPath(adjacency, vertices, fixedApproach, Integer.MAX_VALUE, null, viaIndex);
    }

    private static PathSearchResult shortestPath(List<List<Integer>> adjacency,
            List<Coordinate> vertices, Coordinate fixedApproach, int maximumStates, IntConsumer expand,
            int viaIndex) {
        int count = vertices.size();
        PriorityQueue<SearchState> open = new PriorityQueue<>(Comparator
                .comparingDouble((SearchState state) -> state.estimate).thenComparingDouble(state -> state.cost));
        Map<Long, Double> bestCost = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        boolean initialVia = viaIndex == 0;
        double initialEstimate = viaIndex < 0 || initialVia
                ? vertices.get(0).distance(vertices.get(1))
                : vertices.get(0).distance(vertices.get(viaIndex))
                        + vertices.get(viaIndex).distance(vertices.get(1));
        SearchState initial = new SearchState(-1, 0, initialVia, 0.0, initialEstimate, count);
        open.add(initial);
        bestCost.put(initial.key, 0.0);
        int settled = 0;
        while (!open.isEmpty()) {
            SearchState current = open.remove();
            if (current.cost > bestCost.getOrDefault(current.key, Double.POSITIVE_INFINITY) + NUMERIC_EPSILON_M) continue;
            settled++;
            if (current.current == 1 && (viaIndex < 0 || current.passedVia)) {
                return new PathSearchResult(reconstruct(current.key, parent, vertices, count), settled, false);
            }
            if (expand != null) expand.accept(current.current);
            for (Integer next : adjacency.get(current.current)) {
                if (next == 1 && viaIndex >= 0 && !current.passedVia && next != viaIndex) continue;
                Coordinate previousCoordinate = current.previous < 0 ? fixedApproach : vertices.get(current.previous);
                if (previousCoordinate != null && !legalTurn(previousCoordinate,
                        vertices.get(current.current), vertices.get(next))) continue;
                double cost = current.cost + vertices.get(current.current).distance(vertices.get(next));
                boolean passedVia = current.passedVia || next == viaIndex;
                double remaining = viaIndex < 0 || passedVia
                        ? vertices.get(next).distance(vertices.get(1))
                        : vertices.get(next).distance(vertices.get(viaIndex))
                                + vertices.get(viaIndex).distance(vertices.get(1));
                SearchState candidate = new SearchState(current.current, next, passedVia, cost,
                        cost + remaining, count);
                if (cost + NUMERIC_EPSILON_M >= bestCost.getOrDefault(candidate.key, Double.POSITIVE_INFINITY)) continue;
                if (!bestCost.containsKey(candidate.key) && bestCost.size() >= maximumStates) {
                    return new PathSearchResult(null, settled, true);
                }
                bestCost.put(candidate.key, cost);
                parent.put(candidate.key, current.key);
                open.add(candidate);
            }
        }
        return new PathSearchResult(null, settled, false);
    }

    private static void logSearchAttempt(SearchRequest request, RouteCandidate.Status status,
            long startedNanos, long candidateBuildMillis, long graphBuildMillis, long pathSearchMillis,
            int verticesBeforeHullReduction, int verticesAfterHullReduction, int graphVertices,
            int graphEdges, int obstacleCount, int settledStates, String routeLengthM) {
        LOGGER.info("Visibility route attempt target={} request={} result={} elapsedMs={} candidateBuildMs={} graphBuildMs={} pathSearchMs={} beforeHull={} afterHull={} graphVertices={} graphEdges={} obstacles={} settledStates={} routeLengthM={}",
                request.getTarget().getFeature().getId(), request.getVariantId(), status,
                elapsedMillis(startedNanos), candidateBuildMillis, graphBuildMillis, pathSearchMillis,
                verticesBeforeHullReduction, verticesAfterHullReduction, graphVertices, graphEdges,
                obstacleCount, settledStates, routeLengthM);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    static boolean canSkipAreaEdgeCoverage(Geometry area, Point start, Point goal) {
        return area != null && start != null && goal != null && !area.isEmpty()
                && area.convexHull().equalsTopo(area) && area.covers(start) && area.covers(goal);
    }

    private static List<Geometry> localSearchAreas(LineString centerline, Geometry outerArea) {
        List<Geometry> areas = new ArrayList<>();
        double radius = INITIAL_CORRIDOR_RADIUS_M;
        for (int expansion = 0; expansion < MAX_CORRIDOR_EXPANSIONS; expansion++) {
            Geometry corridor = centerline.buffer(radius, BUFFER_QUADRANT_SEGMENTS).intersection(outerArea);
            if (!corridor.isEmpty()) {
                corridor.setSRID(32637);
                areas.add(corridor);
                if (corridor.covers(outerArea)) return areas;
            }
            radius *= 2.0;
        }
        if (areas.isEmpty() || !areas.get(areas.size() - 1).covers(outerArea)) {
            areas.add((Geometry) outerArea.copy());
        }
        return areas;
    }

    static List<Coordinate> clippedBoundaryCoordinates(Geometry candidate, Geometry area) {
        Geometry clippedBoundary = candidate.getBoundary().intersection(area);
        List<Coordinate> coordinates = new ArrayList<>();
        for (Coordinate coordinate : clippedBoundary.getCoordinates()) {
            if (area.covers(candidate.getFactory().createPoint(coordinate))) {
                coordinates.add(new Coordinate(coordinate));
            }
        }
        return coordinates;
    }

    private static void addOwnBuildingSteeringVertices(List<Coordinate> points, Geometry expanded, Geometry area) {
        Envelope bounds = expanded.getEnvelopeInternal();
        Coordinate[] corners = {
                new Coordinate(bounds.getMinX(), bounds.getMinY()),
                new Coordinate(bounds.getMinX(), bounds.getMaxY()),
                new Coordinate(bounds.getMaxX(), bounds.getMinY()),
                new Coordinate(bounds.getMaxX(), bounds.getMaxY())
        };
        for (Coordinate corner : corners) {
            if (area.covers(expanded.getFactory().createPoint(corner))) points.add(corner);
        }
    }

    static Geometry conservativeNavigationBuffer(Geometry source, Geometry exact, double radiusM,
            ObstacleBufferCache bufferCache) {
        Geometry coarse = bufferCache.buffer(source, radiusM * COARSE_BUFFER_RADIUS_FACTOR, 2);
        return coarse.covers(exact) ? coarse : null;
    }

    private boolean edgeAllowed(LineString edge, RouteContext context, int diameterMm,
            Boolean goalEdge, Point goal, TypedId ownOksExempt) {
        return edgeAllowed(edge, context, diameterMm, goalEdge, goal, ownOksExempt, null);
    }

    private boolean edgeAllowed(LineString edge, RouteContext context, int diameterMm,
            Boolean goalEdge, Point goal, TypedId ownOksExempt, ObstacleSpatialIndex obstacleIndex) {
        if (Boolean.TRUE.equals(goalEdge) && context.goalForward != null
                && !legalTreeGoalApproach(edge, goal, context.goalForward)) return false;
        Envelope edgeEnvelope = edge.getEnvelopeInternal();
        List<SpecialCrossingBuilder.CrossingRestriction> crossings = new ArrayList<>();
        List<RouteObstacle> crossingCandidates = obstacleIndex == null
                ? context.obstacles : obstacleIndex.crossingCandidates(edgeEnvelope);
        for (RouteObstacle obstacle : crossingCandidates) {
            if (!obstacle.special) continue;
            if (!crossingEnvelopeMayIntersect(edgeEnvelope, obstacle)) continue;
            if (goalEdge != null && goalEdge && context.terminalNetwork != null
                    && obstacle.id.equals(context.terminalNetwork.id)
                    && isTerminalTouch(edge, goal, obstacle.geometry)) continue;
            if (context.terminalNetwork != null && obstacle.id.equals(context.terminalNetwork.id)
                    && intersectsBeyondTerminal(edge, goal, obstacle.geometry)) {
                return false;
            }
            crossings.add(new SpecialCrossingBuilder.CrossingRestriction(obstacle.id, obstacle.type,
                    obstacle.geometry, obstacle.widthM));
        }
        List<IntersectionSegment> passageParts = Collections.emptyList();
        if (!crossings.isEmpty()) {
            try {
                passageParts = crossingBuilder.build(edge, crossings, diameterMm, true, true);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        BigDecimal pipeWidth = RuleCatalog.diameter(diameterMm).get().getPairWidthM();
        List<RouteObstacle> clearanceCandidates = obstacleIndex == null
                ? context.obstacles : obstacleIndex.clearanceCandidates(edgeEnvelope);
        for (RouteObstacle obstacle : clearanceCandidates) {
            if (ownOksExempt != null && ownOksExempt.equals(obstacle.id)) continue;
            if (goalEdge != null && goalEdge && context.terminalNetwork != null
                    && obstacle.id.equals(context.terminalNetwork.id)
                    && isTerminalTouch(edge, goal, obstacle.geometry)) continue;
            double required = clearance.requiredCenterlineDistanceM(obstacle.type, diameterMm,
                    pipeWidth, obstacle.widthM);
            Envelope clearanceEnvelope = new Envelope(edgeEnvelope);
            clearanceEnvelope.expandBy(required + SEARCH_VERTEX_MARGIN_M);
            if (!clearanceEnvelope.intersects(obstacle.geometry.getEnvelopeInternal())) continue;
            List<IntersectionSegment> passages = obstacle.special ? passageParts : Collections.emptyList();
            if (!clearance.respectsClearance(edge, obstacle.geometry, required, obstacle.id, passages)) return false;
        }
        for (LineString segment : context.treeSegments) {
            if (!edgeEnvelope.intersects(segment.getEnvelopeInternal())) continue;
            Geometry intersection = edge.intersection(segment);
            if (intersection.isEmpty()) continue;
            if (goalEdge != null && goalEdge && goal != null && intersection.getDimension() == 0
                    && intersection.getNumPoints() == 1
                    && intersection.getCoordinate().distance(goal.getCoordinate()) <= 1e-6
                    && (edge.getCoordinateN(0).distance(goal.getCoordinate()) <= 1e-6
                        || edge.getCoordinateN(edge.getNumPoints() - 1).distance(goal.getCoordinate()) <= 1e-6)) {
                continue;
            }
            return false;
        }
        return true;
    }

    static boolean legalTreeGoalApproach(LineString edge, Point goal, Coordinate forward) {
        if (goal == null || edge.getNumPoints() < 2) return false;
        Coordinate first = edge.getCoordinateN(0);
        Coordinate last = edge.getCoordinateN(edge.getNumPoints() - 1);
        Coordinate previous;
        if (first.distance(goal.getCoordinate()) <= 1e-6) previous = last;
        else if (last.distance(goal.getCoordinate()) <= 1e-6) previous = first;
        else return false;
        double approachX = goal.getX() - previous.x;
        double approachY = goal.getY() - previous.y;
        return legalJunctionAngle(-approachX, -approachY, forward.x, forward.y);
    }

    private static boolean crossingEnvelopeMayIntersect(Envelope edgeEnvelope, RouteObstacle obstacle) {
        return edgeEnvelope.intersects(crossingCandidateEnvelope(obstacle));
    }

    private static Envelope crossingCandidateEnvelope(RouteObstacle obstacle) {
        Envelope crossingEnvelope = new Envelope(obstacle.geometry.getEnvelopeInternal());
        if (obstacle.geometry.getDimension() == 1
                && ("road".equals(obstacle.type) || "tram_tracks".equals(obstacle.type))) {
            crossingEnvelope.expandBy(obstacle.widthM / 2.0);
        }
        crossingEnvelope.expandBy(SEARCH_VERTEX_MARGIN_M);
        return crossingEnvelope;
    }

    private Variant buildVariant(RouteRequest request, RouteContext context, LineString route, int diameterMm) {
        List<SpecialCrossingBuilder.CrossingRestriction> crossings = new ArrayList<>();
        for (RouteObstacle obstacle : context.obstacles) {
            if (obstacle.special && !obstacle.id.equals(context.terminalNetwork.id)) {
                crossings.add(new SpecialCrossingBuilder.CrossingRestriction(obstacle.id, obstacle.type,
                        obstacle.geometry, obstacle.widthM));
            }
        }
        List<IntersectionSegment> parts = crossingBuilder.build(route, crossings, diameterMm, true, true);
        String targetNodeId = request.getVariantId() + ":target";
        String attachmentNodeId = request.getVariantId() + ":attachment";
        TypedId attachmentInputId = request.getAttachment().getEndpointKind()
                == AttachmentCandidate.EndpointKind.EXISTING_CHAMBER
                ? request.getAttachment().getExistingChamberId() : request.getAttachment().getNetworkId();
        List<Variant.Node> nodes = new ArrayList<>();
        List<Variant.Segment> segments = new ArrayList<>();
        nodes.add(new Variant.Node(targetNodeId, context.target.getX(), context.target.getY(),
                "oks_connection_point", request.getTarget().getFeature().getId()));
        Point attachment = request.getAttachment().getEndpoint();
        nodes.add(new Variant.Node(attachmentNodeId, attachment.getX(), attachment.getY(),
                "heat_chamber", attachmentInputId));

        LengthIndexedLine indexed = new LengthIndexedLine(route);
        String fromNodeId = targetNodeId;
        for (int i = 0; i < parts.size(); i++) {
            IntersectionSegment part = parts.get(i);
            String toNodeId = i == parts.size() - 1 ? attachmentNodeId
                    : request.getVariantId() + ":technical:" + i;
            if (i < parts.size() - 1) {
                Coordinate coordinate = indexed.extractPoint(part.getEndMeasureM());
                nodes.add(new Variant.Node(toNodeId, coordinate.x, coordinate.y, "technical_node"));
            }
            LineString geometry = (LineString) indexed.extractLine(part.getStartMeasureM(), part.getEndMeasureM());
            String method = part.getMethod() == IntersectionSegment.Method.SPECIAL ? "special" : "base";
            segments.add(new Variant.Segment(request.getVariantId() + ":segment:" + i,
                    fromNodeId, toNodeId, geometry, fromNodeId, toNodeId, context.flowTph, diameterMm,
                    method, part.getRestrictionIds(), part.getMultiplier()));
            fromNodeId = toNodeId;
        }
        return new Variant(request.getVariantId(), nodes, segments);
    }

    /** Use the same mandatory own-building exit when ranking possible new-tree attachments. */
    Point goalRankingOrigin(FeatureIndex.IndexedFeature targetFeature,
            List<FeatureIndex.IndexedFeature> inputs, Envelope searchArea) {
        if (!(targetFeature.getGeometry() instanceof Point)) return null;
        Point target = (Point) targetFeature.getGeometry();
        JsonNode flow = targetFeature.getFeature().getProperties().getExtra().get("flow_tph");
        if (flow == null || !flow.isNumber() || flow.decimalValue().signum() <= 0) return target;
        int diameter = smallestCapacityDiameter(flow.decimalValue());
        if (diameter == 0) return target;
        Geometry ownBuilding = null;
        for (FeatureIndex.IndexedFeature input : inputs) {
            if (!"restriction".equals(input.getFeature().getObjectType())) continue;
            JsonNode type = input.getFeature().getProperties().getExtra().get("restriction_type");
            if (type == null || !"oks".equals(type.asText())
                    || !input.getGeometry().contains(target)) continue;
            if (ownBuilding != null) return target;
            ownBuilding = input.getGeometry();
        }
        if (ownBuilding == null) return target;
        double requiredClearance = clearance.requiredCenterlineDistanceM("oks", diameter,
                RuleCatalog.diameter(diameter).get().getPairWidthM(), null);
        try {
            return ownBuildingEscape(target, ownBuilding, requiredClearance, searchArea,
                    OWN_BUILDING_CLEARANCE_MARGIN_M);
        } catch (IllegalArgumentException exception) {
            return target;
        }
    }

    private Point ownBuildingEscape(Point target, Geometry ownBuilding, double requiredClearanceM,
            Envelope searchArea, double clearanceMarginM) {
        // Keep the searched leg outside the validator's exact clearance boundary.
        double searchClearanceM = requiredClearanceM + clearanceMarginM;
        Coordinate nearestBoundary = DistanceOp.nearestPoints(target, ownBuilding.getBoundary())[1];
        double dx = nearestBoundary.x - target.getX();
        double dy = nearestBoundary.y - target.getY();
        double length = Math.hypot(dx, dy);
        if (length <= NUMERIC_EPSILON_M) throw new IllegalArgumentException("Target has no outward direction from its own oks");
        dx /= length;
        dy /= length;
        double maximum = Math.hypot(searchArea.getWidth(), searchArea.getHeight());
        double previous = 0;
        double clearDistance = -1;
        for (double distance = 0.25; distance <= maximum + 0.25; distance += 0.25) {
            double x = nearestBoundary.x + dx * distance;
            double y = nearestBoundary.y + dy * distance;
            if (!searchArea.covers(x, y)) break;
            Point candidate = target.getFactory().createPoint(new Coordinate(x, y));
            if (candidate.distance(ownBuilding) + NUMERIC_EPSILON_M >= searchClearanceM) {
                double violating = previous;
                double clear = distance;
                for (int i = 0; i < 40; i++) {
                    double middle = (violating + clear) / 2.0;
                    Point test = target.getFactory().createPoint(new Coordinate(
                            nearestBoundary.x + dx * middle, nearestBoundary.y + dy * middle));
                    if (test.distance(ownBuilding) + NUMERIC_EPSILON_M >= searchClearanceM) clear = middle;
                    else violating = middle;
                }
                clearDistance = clear;
                break;
            }
            previous = distance;
        }
        if (clearDistance < 0) throw new IllegalArgumentException("Search area ends before own-building clearance is restored");
        Point escape = target.getFactory().createPoint(new Coordinate(
                nearestBoundary.x + dx * clearDistance, nearestBoundary.y + dy * clearDistance));
        escape.setSRID(32637);
        return escape;
    }

    private static List<Coordinate> reconstruct(long goalState, Map<Long, Long> parent,
            List<Coordinate> vertices, int count) {
        List<Coordinate> reversed = new ArrayList<>();
        long state = goalState;
        while (true) {
            int current = (int) ((state / 2L) % count);
            reversed.add(new Coordinate(vertices.get(current)));
            Long previous = parent.get(state);
            if (previous == null) break;
            state = previous;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private List<Coordinate> simplify(List<Coordinate> route, RouteContext context, int diameterMm,
            Coordinate target, boolean hasFixedApproach, Point goal, Geometry area,
            org.locationtech.jts.geom.GeometryFactory factory, ObstacleSpatialIndex obstacleIndex,
            Coordinate mandatoryVia) {
        int protectedIndex = -1;
        if (mandatoryVia != null) {
            for (int i = 0; i < route.size(); i++) {
                if (route.get(i).distance(mandatoryVia) <= NUMERIC_EPSILON_M) {
                    protectedIndex = i;
                    break;
                }
            }
        }
        return simplifyPath(route, hasFixedApproach ? target : null, (from, to) -> {
            LineString edge = line(from, to, factory);
            if (!area.covers(edge)) return false;
            boolean goalEdge = to.distance(goal.getCoordinate()) <= NUMERIC_EPSILON_M;
            return edgeAllowed(edge, context, diameterMm, goalEdge, goal, null, obstacleIndex);
        }, protectedIndex);
    }

    static List<Coordinate> simplifyPath(List<Coordinate> route, Coordinate fixedApproach,
            java.util.function.BiPredicate<Coordinate, Coordinate> edgeAllowed) {
        return simplifyPath(route, fixedApproach, edgeAllowed, -1);
    }

    private static List<Coordinate> simplifyPath(List<Coordinate> route, Coordinate fixedApproach,
            java.util.function.BiPredicate<Coordinate, Coordinate> edgeAllowed, int protectedIndex) {
        if (route.size() <= 2) return route;
        List<Coordinate> simplified = new ArrayList<>();
        int current = 0;
        simplified.add(new Coordinate(route.get(0)));
        while (current < route.size() - 1) {
            int next = current + 1;
            for (int candidate = route.size() - 1; candidate > current + 1; candidate--) {
                if (current < protectedIndex && protectedIndex < candidate) continue;
                Coordinate from = route.get(current), to = route.get(candidate);
                if (simplified.size() > 1 && !legalTurn(simplified.get(simplified.size() - 2), from, to)) continue;
                if (current == 0 && fixedApproach != null && !legalTurn(fixedApproach, from, to)) continue;
                if (candidate < route.size() - 1 && !legalTurn(from, to, route.get(candidate + 1))) continue;
                if (edgeAllowed.test(from, to)) {
                    next = candidate;
                    break;
                }
            }
            simplified.add(new Coordinate(route.get(next)));
            current = next;
        }
        return legalPolylineTurns(simplified, fixedApproach) ? simplified : route;
    }

    static boolean legalPolylineTurns(List<Coordinate> route, Coordinate fixedApproach) {
        if (route == null || route.size() < 2) return false;
        if (fixedApproach != null && !legalTurn(fixedApproach, route.get(0), route.get(1))) return false;
        for (int i = 1; i < route.size() - 1; i++) {
            if (!legalTurn(route.get(i - 1), route.get(i), route.get(i + 1))) return false;
        }
        return true;
    }

    private static boolean legalPolylineTurns(LineString route) {
        return legalPolylineTurns(java.util.Arrays.asList(route.getCoordinates()), null);
    }

    private static boolean isTerminalTouch(LineString edge, Point goal, Geometry network) {
        if (goal == null) return false;
        Point start = edge.getStartPoint();
        Point end = edge.getEndPoint();
        boolean endsAtGoal = end.distance(goal) <= NUMERIC_EPSILON_M;
        boolean startsAtGoal = start.distance(goal) <= NUMERIC_EPSILON_M;
        if (!endsAtGoal && !startsAtGoal) return false;
        Geometry intersection = edge.intersection(network);
        return (endsAtGoal ? end : start).distance(network) <= 0.25
                && intersection.difference(goal.buffer(0.25)).isEmpty();
    }

    private static boolean intersectsBeyondTerminal(LineString edge, Point goal, Geometry network) {
        if (edge.disjoint(network)) return false;
        return !isTerminalTouch(edge, goal, network);
    }

    private static void addVertex(List<Coordinate> vertices, Map<String, Integer> ids, Coordinate coordinate) {
        String key = coordinateKey(coordinate);
        if (ids.containsKey(key)) return;
        ids.put(key, vertices.size());
        vertices.add(new Coordinate(coordinate));
    }

    private static int uniqueVertexCount(Point start, Point goal, Point mandatoryVia,
            List<ObstacleVertices> obstacles) {
        Set<String> keys = new HashSet<>();
        keys.add(coordinateKey(start.getCoordinate()));
        keys.add(coordinateKey(goal.getCoordinate()));
        if (mandatoryVia != null) keys.add(coordinateKey(mandatoryVia.getCoordinate()));
        for (ObstacleVertices obstacle : obstacles) {
            for (Coordinate coordinate : obstacle.coordinates) keys.add(coordinateKey(coordinate));
        }
        return keys.size();
    }

    private static String coordinateKey(Coordinate coordinate) {
        return Double.toHexString(coordinate.x) + ":" + Double.toHexString(coordinate.y);
    }

    private static LineString line(Coordinate from, Coordinate to,
            org.locationtech.jts.geom.GeometryFactory factory) {
        LineString result = factory.createLineString(new Coordinate[] {new Coordinate(from), new Coordinate(to)});
        result.setSRID(32637);
        return result;
    }

    static boolean legalTurn(Coordinate previous, Coordinate current, Coordinate next) {
        return legalJunctionAngle(previous.x - current.x, previous.y - current.y,
                next.x - current.x, next.y - current.y);
    }

    private static boolean legalJunctionAngle(double ax, double ay, double bx, double by) {
        double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (lengths == 0) return false;
        double cosine = Math.max(-1.0, Math.min(1.0, (ax * bx + ay * by) / lengths));
        return Math.toDegrees(Math.acos(cosine)) >= 90.0 - 1e-9;
    }

    private static int smallestCapacityDiameter(BigDecimal flowTph) {
        for (DiameterRule rule : RuleCatalog.diameters()) {
            if (flowTph.compareTo(rule.getCapacityTph()) <= 0) return rule.getDiameterMm();
        }
        return 0;
    }

    private static Double numericProperty(InputFeature feature, String name) {
        JsonNode value = feature.getProperties().getExtra().get(name);
        return value != null && value.isNumber() && Double.isFinite(value.doubleValue())
                ? value.doubleValue() : null;
    }

    private static RouteObstacle obstacleById(List<RouteObstacle> obstacles, TypedId id) {
        for (RouteObstacle obstacle : obstacles) if (obstacle.id.equals(id)) return obstacle;
        return null;
    }

    private static final class SparseCandidate {
        private final int index;
        private final int rank;
        private final double distance;
        private SparseCandidate(int index, int rank, double distance) {
            this.index = index;
            this.rank = rank;
            this.distance = distance;
        }
    }

    private static final class SparseNeighbor {
        private final int index;
        private final double distanceSquared;
        private SparseNeighbor(int index, double distanceSquared) {
            this.index = index;
            this.distanceSquared = distanceSquared;
        }
    }

    private static final class SparseBudget {
        private final int maximum;
        private int evaluated;
        private SparseBudget(int maximum) { this.maximum = maximum; }
        private boolean exhausted() { return evaluated >= maximum; }
        private boolean consume() {
            if (exhausted()) return false;
            evaluated++;
            return true;
        }
    }

    private static final class PathSearchResult {
        private final List<Coordinate> path;
        private final int settledStates;
        private final boolean stateLimitReached;
        private PathSearchResult(List<Coordinate> path, int settledStates, boolean stateLimitReached) {
            this.path = path;
            this.settledStates = settledStates;
            this.stateLimitReached = stateLimitReached;
        }
    }

    private static final class SparseGraphResult {
        private final List<Coordinate> path;
        private final int graphEdges;
        private final int settledStates;
        private final long graphBuildMillis;
        private final long pathSearchMillis;
        private final boolean budgetExhausted;
        private final boolean stateLimitReached;
        private SparseGraphResult(List<Coordinate> path, int graphEdges, int settledStates,
                long graphBuildMillis, long pathSearchMillis, boolean budgetExhausted,
                boolean stateLimitReached) {
            this.path = path;
            this.graphEdges = graphEdges;
            this.settledStates = settledStates;
            this.graphBuildMillis = graphBuildMillis;
            this.pathSearchMillis = pathSearchMillis;
            this.budgetExhausted = budgetExhausted;
            this.stateLimitReached = stateLimitReached;
        }
    }

    private static final class SearchState {
        private final int previous;
        private final int current;
        private final boolean passedVia;
        private final double cost;
        private final double estimate;
        private final long key;
        private SearchState(int previous, int current, boolean passedVia, double cost,
                double estimate, int vertices) {
            this.previous = previous;
            this.current = current;
            this.passedVia = passedVia;
            this.cost = cost;
            this.estimate = estimate;
            this.key = (((long) previous + 1L) * vertices + current) * 2L + (passedVia ? 1L : 0L);
        }
    }

    private static final class SearchOutcome {
        private final LineString route;
        private final RouteCandidate.Status status;
        private final String detail;
        private final boolean incomplete;
        private SearchOutcome(LineString route, RouteCandidate.Status status, String detail, boolean incomplete) {
            this.route = route;
            this.status = status;
            this.detail = detail;
            this.incomplete = incomplete;
        }
        private static SearchOutcome found(LineString route, boolean incomplete) {
            return new SearchOutcome(route, null, null, incomplete);
        }
        private static SearchOutcome failure(RouteCandidate.Status status, String detail) {
            return new SearchOutcome(null, status, detail, status == RouteCandidate.Status.SEARCH_INCOMPLETE
                    || status == RouteCandidate.Status.VISIBILITY_VERTEX_LIMIT_REACHED);
        }
    }

    private static final class ObstacleVertices {
        private final TypedId id;
        private final Geometry source;
        private final double bufferRadiusM;
        private Geometry expanded;
        private List<Coordinate> coordinates;
        private ObstacleVertices(TypedId id, Geometry source, double bufferRadiusM,
                Geometry expanded, List<Coordinate> coordinates) {
            this.id = id;
            this.source = source;
            this.bufferRadiusM = bufferRadiusM;
            this.expanded = expanded;
            this.coordinates = coordinates;
        }
    }

    private final class ObstacleSpatialIndex {
        private final STRtree clearanceTree = new STRtree();
        private final STRtree crossingTree = new STRtree();

        private ObstacleSpatialIndex(List<RouteObstacle> obstacles, int diameterMm) {
            BigDecimal pipeWidth = RuleCatalog.diameter(diameterMm).get().getPairWidthM();
            for (int i = 0; i < obstacles.size(); i++) {
                RouteObstacle obstacle = obstacles.get(i);
                IndexedObstacle indexed = new IndexedObstacle(obstacle, i);
                double required = clearance.requiredCenterlineDistanceM(obstacle.type, diameterMm,
                        pipeWidth, obstacle.widthM);
                Envelope clearanceEnvelope = new Envelope(obstacle.geometry.getEnvelopeInternal());
                clearanceEnvelope.expandBy(required + SEARCH_VERTEX_MARGIN_M);
                clearanceTree.insert(clearanceEnvelope, indexed);
                if (obstacle.special) crossingTree.insert(crossingCandidateEnvelope(obstacle), indexed);
            }
            clearanceTree.build();
            crossingTree.build();
        }

        private List<RouteObstacle> clearanceCandidates(Envelope edgeEnvelope) {
            return candidates(clearanceTree, edgeEnvelope);
        }

        private List<RouteObstacle> crossingCandidates(Envelope edgeEnvelope) {
            return candidates(crossingTree, edgeEnvelope);
        }

        private List<RouteObstacle> candidates(STRtree tree, Envelope envelope) {
            List<?> matches = tree.query(envelope);
            List<IndexedObstacle> ordered = new ArrayList<>(matches.size());
            for (Object match : matches) ordered.add((IndexedObstacle) match);
            ordered.sort(Comparator.comparingInt(item -> item.order));
            List<RouteObstacle> result = new ArrayList<>(ordered.size());
            for (IndexedObstacle item : ordered) result.add(item.obstacle);
            return result;
        }
    }

    private static final class IndexedObstacle {
        private final RouteObstacle obstacle;
        private final int order;

        private IndexedObstacle(RouteObstacle obstacle, int order) {
            this.obstacle = obstacle;
            this.order = order;
        }
    }

    private static final class RouteContext {
        private final BigDecimal flowTph;
        private final Point target;
        private final RouteObstacle ownBuilding;
        private final RouteObstacle terminalNetwork;
        private final List<FeatureIndex.IndexedFeature> validationInputs;
        private final Map<TypedId, FeatureIndex.IndexedFeature> inputsById;
        private final RouteCandidate.Status earlyStatus;
        private final String earlyDetail;
        private final List<RouteObstacle> obstacles;
        private final List<LineString> treeSegments;
        private final Coordinate goalForward;
        private RouteContext(BigDecimal flowTph, Point target, RouteObstacle ownBuilding,
                RouteObstacle terminalNetwork, List<FeatureIndex.IndexedFeature> validationInputs,
                Map<TypedId, FeatureIndex.IndexedFeature> inputsById, RouteCandidate.Status earlyStatus,
                String earlyDetail) {
            this(flowTph, target, ownBuilding, terminalNetwork, validationInputs, inputsById,
                    earlyStatus, earlyDetail, Collections.emptyList(), Collections.emptyList(), null);
        }
        private RouteContext(BigDecimal flowTph, Point target, RouteObstacle ownBuilding,
                RouteObstacle terminalNetwork, List<FeatureIndex.IndexedFeature> validationInputs,
                Map<TypedId, FeatureIndex.IndexedFeature> inputsById, RouteCandidate.Status earlyStatus,
                String earlyDetail, List<RouteObstacle> obstacles, List<LineString> treeSegments,
                Coordinate goalForward) {
            this.flowTph = flowTph;
            this.target = target;
            this.ownBuilding = ownBuilding;
            this.terminalNetwork = terminalNetwork;
            this.validationInputs = validationInputs;
            this.inputsById = inputsById;
            this.earlyStatus = earlyStatus;
            this.earlyDetail = earlyDetail;
            this.obstacles = obstacles;
            this.treeSegments = treeSegments;
            this.goalForward = goalForward == null ? null : new Coordinate(goalForward);
        }
    }

    private static final class SearchRequest {
        private final RouteRequest standalone;
        private final TreeRouteRequest tree;
        private SearchRequest(RouteRequest standalone) { this.standalone = standalone; this.tree = null; }
        private SearchRequest(TreeRouteRequest tree) { this.standalone = null; this.tree = tree; }
        private boolean isTreeSearch() { return tree != null; }
        private String getVariantId() { return standalone != null ? standalone.getVariantId() : tree.getVariantId(); }
        private FeatureIndex.IndexedFeature getTarget() {
            return standalone != null ? standalone.getTarget() : tree.getTarget();
        }
        private Point getTargetPoint() { return standalone != null ? standalone.getTargetPoint() : tree.getTargetPoint(); }
        private Point getGoalPoint() {
            return standalone != null ? standalone.getAttachment().getEndpoint() : tree.getGoalPoint();
        }
        private AttachmentCandidate getAttachment() {
            return standalone == null ? null : standalone.getAttachment();
        }
        private List<LineString> getTreeSegments() {
            return standalone != null ? Collections.emptyList() : tree.getTreeSegments();
        }
        private Coordinate getGoalForward() {
            return standalone != null ? null : tree.getGoalForward();
        }
        private List<FeatureIndex.IndexedFeature> getInputs() {
            return standalone != null ? standalone.getInputs() : tree.getInputs();
        }
        private Envelope getSearchArea() {
            return standalone != null ? standalone.getSearchArea() : tree.getSearchArea();
        }
        private int getMaximumVisibilityVertices() {
            return standalone != null ? standalone.getMaximumVisibilityVertices() : tree.getMaximumVisibilityVertices();
        }
        private Point getMandatoryVia() {
            return standalone != null ? standalone.getMandatoryVia() : tree.getMandatoryVia();
        }
    }

    public static final class BranchRoute {
        private final RouteCandidate.Status status;
        private final LineString geometry;
        private final int diameterMm;
        private final String detail;
        private BranchRoute(RouteCandidate.Status status, LineString geometry, int diameterMm, String detail) {
            this.status = status;
            this.geometry = geometry == null ? null : (LineString) geometry.copy();
            this.diameterMm = diameterMm;
            this.detail = detail;
        }
        private static BranchRoute found(LineString geometry, int diameterMm, boolean complete) {
            return new BranchRoute(complete ? RouteCandidate.Status.FOUND
                    : RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH, geometry, diameterMm, null);
        }
        private static BranchRoute failure(RouteCandidate.Status status, String detail) {
            return new BranchRoute(status, null, 0, detail);
        }
        public boolean isFound() {
            return status == RouteCandidate.Status.FOUND
                    || status == RouteCandidate.Status.FOUND_WITH_INCOMPLETE_SEARCH;
        }
        public RouteCandidate.Status getStatus() { return status; }
        public LineString getGeometry() { return geometry == null ? null : (LineString) geometry.copy(); }
        public int getDiameterMm() { return diameterMm; }
        public String getDetail() { return detail; }
    }

    private static final class RouteObstacle {
        private final TypedId id;
        private final String type;
        private final Geometry geometry;
        private final Double widthM;
        private final boolean special;
        private RouteObstacle(TypedId id, String type, Geometry geometry, Double widthM, boolean special) {
            this.id = id;
            this.type = type;
            this.geometry = geometry;
            this.widthM = widthM;
            this.special = special;
        }
    }
}
