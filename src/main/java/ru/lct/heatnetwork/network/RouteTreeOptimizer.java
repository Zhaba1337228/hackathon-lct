package ru.lct.heatnetwork.network;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.TopologyException;
import org.locationtech.jts.linearref.LengthIndexedLine;
import com.fasterxml.jackson.databind.JsonNode;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.rules.DiameterRule;
import ru.lct.heatnetwork.rules.DiameterSelector;
import ru.lct.heatnetwork.rules.RuleCatalog;
import ru.lct.heatnetwork.rules.RestrictionRule;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.spatial.IntersectionSegment;
import ru.lct.heatnetwork.spatial.SpecialCrossingBuilder;
import ru.lct.heatnetwork.validation.ValidationContext;
import ru.lct.heatnetwork.validation.ValidationReport;
import ru.lct.heatnetwork.validation.VariantValidator;

/** Combines target paths into a forest, sharing only identical pipe pieces at common nodes. */
public final class RouteTreeOptimizer {
    private final FlowAggregator flowAggregator = new FlowAggregator();
    private final DiameterSelector diameterSelector = new DiameterSelector();
    private final VariantValidator validator = new VariantValidator();
    private final SpecialCrossingBuilder crossingBuilder = new SpecialCrossingBuilder();

    public OptimizationResult optimize(String variantId, List<Variant> routes,
            List<AttachmentCandidate> attachments, List<FeatureIndex.IndexedFeature> validationInputs) {
        if (variantId == null || variantId.trim().isEmpty() || routes == null || attachments == null
                || validationInputs == null || routes.isEmpty() || routes.size() != attachments.size()) {
            return OptimizationResult.failure("Routes, matching attachment candidates and validation inputs are required");
        }
        try {
            List<Variant> preparedRoutes = insertSharedBranchCameras(routes, attachments, validationInputs);
            Variant combined = combine(variantId, preparedRoutes, attachments);
            validateAttachmentCapacity(combined, attachments);
            return finalizeCandidate(combined, validationInputs);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return OptimizationResult.failure(exception.getMessage());
        }
    }

    /** Rebuilds two accepted forests from target-to-root paths, retaining every path in the primary. */
    public OptimizationResult combineValidatedTrees(String variantId, TreeCandidate primary,
            TreeCandidate donor, List<AttachmentCandidate> originalAttachments,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        if (variantId == null || variantId.trim().isEmpty() || primary == null || donor == null
                || originalAttachments == null || validationInputs == null
                || !primary.validation.isAcceptable() || !donor.validation.isAcceptable()) {
            return OptimizationResult.failure("Two accepted trees and their original attachments are required");
        }
        try {
            List<Variant> routes = new ArrayList<>();
            List<AttachmentCandidate> attachments = new ArrayList<>();
            Set<TypedId> primaryTargets = new HashSet<>();
            appendTargetPaths(primary.variant, Collections.emptySet(), originalAttachments,
                    routes, attachments, primaryTargets);
            int beforeDonor = routes.size();
            appendTargetPaths(donor.variant, primaryTargets, originalAttachments,
                    routes, attachments, new HashSet<>());
            if (routes.size() == beforeDonor) {
                return OptimizationResult.failure("Donor adds no distinct target");
            }
            return optimize(variantId, routes, attachments, validationInputs);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return OptimizationResult.failure(exception.getMessage());
        }
    }

    /** Reuses only the donor path before its first contact with an accepted forest. */
    public OptimizationResult graftDonorPrefix(String variantId, TreeCandidate primary,
            TreeCandidate donor, TypedId targetId,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        if (variantId == null || variantId.trim().isEmpty() || primary == null || donor == null
                || targetId == null || validationInputs == null
                || !primary.validation.isAcceptable() || !donor.validation.isAcceptable()) {
            return OptimizationResult.failure("Two accepted trees, a missing target and inputs are required");
        }
        if (primary.variant.getNodes().stream().anyMatch(node ->
                "oks_connection_point".equals(node.getType()) && targetId.equals(node.getInputFeatureId()))) {
            return OptimizationResult.failure("Donor target is already connected");
        }
        FeatureIndex.IndexedFeature target = validationInputs.stream().filter(input ->
                targetId.equals(input.getFeature().getId())
                        && "oks_connection_point".equals(input.getFeature().getObjectType()))
                .findFirst().orElse(null);
        if (target == null) return OptimizationResult.failure("Donor target input is missing");
        try {
            LineString donorPath = targetPathGeometry(donor.variant, targetId);
            LengthIndexedLine indexed = new LengthIndexedLine(donorPath);
            List<DonorContact> contacts = new ArrayList<>();
            for (Variant.Segment segment : primary.variant.getSegments()) {
                Geometry intersection = donorPath.intersection(segment.getGeometry());
                if (intersection.isEmpty()) continue;
                for (int componentIndex = 0; componentIndex < intersection.getNumGeometries();
                        componentIndex++) {
                    Geometry component = intersection.getGeometryN(componentIndex);
                    DonorContact overlapStart = null;
                    for (Coordinate coordinate : component.getCoordinates()) {
                        double measure = indexed.project(coordinate);
                        if (indexed.extractPoint(measure).distance(coordinate) > 1e-6) {
                            return OptimizationResult.failure("Donor contact cannot be located on its path");
                        }
                        if (component.getDimension() == 0) {
                            contacts.add(new DonorContact(segment, coordinate, measure));
                        } else if (overlapStart == null || measure < overlapStart.measure) {
                            overlapStart = new DonorContact(segment, coordinate, measure);
                        }
                    }
                    if (overlapStart != null) contacts.add(overlapStart);
                }
            }
            contacts.sort(Comparator.comparingDouble((DonorContact contact) -> contact.measure)
                    .thenComparing(contact -> contact.segment.getId()));
            if (contacts.isEmpty() || contacts.get(0).measure < 0.25
                    || donorPath.getLength() - contacts.get(0).measure < 0.25) {
                return OptimizationResult.failure("Donor has no internal first contact with the accepted tree");
            }
            DonorContact first = contacts.get(0);
            LineString prefix = (LineString) indexed.extractLine(0, first.measure);
            prefix.setSRID(32637);
            for (Variant.Segment segment : primary.variant.getSegments()) {
                Geometry prefixContact = prefix.intersection(segment.getGeometry());
                if (prefixContact.isEmpty()) continue;
                if (prefixContact.getDimension() != 0) {
                    return OptimizationResult.failure("Donor prefix overlaps an accepted pipe");
                }
                for (Coordinate coordinate : prefixContact.getCoordinates()) {
                    if (coordinate.distance(first.coordinate) > 1e-6) {
                        return OptimizationResult.failure("Donor prefix contacts an accepted pipe before its end");
                    }
                }
            }
            JsonNode demand = target.getFeature().getProperties().getExtra().get("flow_tph");
            if (demand == null || !demand.isNumber() || demand.decimalValue().signum() <= 0) {
                return OptimizationResult.failure("Donor target flow is missing");
            }
            java.util.Optional<DiameterRule> diameter = RuleCatalog.smallestDiameter(
                    demand.decimalValue(), BigDecimal.valueOf(prefix.getLength()));
            if (!diameter.isPresent()) {
                return OptimizationResult.failure("Donor prefix has no feasible diameter");
            }
            for (Variant.Node node : primary.variant.getNodes()) {
                if (!"heat_chamber".equals(node.getType())
                        || first.coordinate.distance(new Coordinate(node.getX(), node.getY())) > 1e-6) continue;
                return attachRoutedBranchAtChamber(variantId, primary, target, node.getId(),
                        prefix, diameter.get().getDiameterMm(), validationInputs);
            }
            int checked = 0;
            OptimizationResult rejection = null;
            for (DonorContact contact : contacts) {
                if (Math.abs(contact.measure - first.measure) > 1e-6
                        || contact.coordinate.distance(first.coordinate) > 1e-6) break;
                if (!"base".equals(contact.segment.getLayingMethod())
                        || !contact.segment.getRestrictionIds().isEmpty()) continue;
                if (++checked > 3) break;
                OptimizationResult result = attachRoutedBranch(variantId, primary, target,
                        contact.segment.getId(), prefix, diameter.get().getDiameterMm(), validationInputs);
                if (result.isFound()) return result;
                rejection = result;
            }
            return rejection == null ? OptimizationResult.failure("First donor contact is not an eligible trunk")
                    : rejection;
        } catch (IllegalArgumentException | IllegalStateException | TopologyException exception) {
            return OptimizationResult.failure("Donor prefix cannot be grafted: " + exception.getMessage());
        }
    }

    private static LineString targetPathGeometry(Variant tree, TypedId targetId) {
        Map<String, Variant.Node> nodesById = new HashMap<>();
        Map<String, Variant.Segment> rootwardByNode = new HashMap<>();
        for (Variant.Node node : tree.getNodes()) nodesById.put(node.getId(), node);
        for (Variant.Segment segment : tree.getSegments()) {
            if (rootwardByNode.putIfAbsent(segment.getFlowFromNodeId(), segment) != null) {
                throw new IllegalArgumentException("Donor path has multiple parent edges");
            }
        }
        Variant.Node target = tree.getNodes().stream().filter(node ->
                "oks_connection_point".equals(node.getType()) && targetId.equals(node.getInputFeatureId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Donor does not contain target"));
        List<Coordinate> coordinates = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Variant.Node cursor = target;
        while (cursor != null && visited.add(cursor.getId())) {
            Variant.Segment segment = rootwardByNode.get(cursor.getId());
            if (segment == null) break;
            LineString geometry = segment.getGeometry();
            if (geometry == null || geometry.getSRID() != 32637) {
                throw new IllegalArgumentException("Donor path is not projected");
            }
            Coordinate[] piece = geometry.getCoordinates();
            if (segment.getFlowFromNodeId().equals(segment.getEndNodeId())) {
                for (int left = 0, right = piece.length - 1; left < right; left++, right--) {
                    Coordinate swap = piece[left]; piece[left] = piece[right]; piece[right] = swap;
                }
            } else if (!segment.getFlowFromNodeId().equals(segment.getStartNodeId())) {
                throw new IllegalArgumentException("Donor segment has inconsistent flow direction");
            }
            if (piece.length < 2 || piece[0].distance(new Coordinate(cursor.getX(), cursor.getY())) > 1e-6
                    || (!coordinates.isEmpty()
                            && coordinates.get(coordinates.size() - 1).distance(piece[0]) > 1e-6)) {
                throw new IllegalArgumentException("Donor path is discontinuous");
            }
            for (int index = coordinates.isEmpty() ? 0 : 1; index < piece.length; index++) {
                coordinates.add(new Coordinate(piece[index]));
            }
            cursor = nodesById.get(segment.getFlowToNodeId());
        }
        if (cursor == null || !"heat_chamber".equals(cursor.getType())
                || rootwardByNode.containsKey(cursor.getId()) || coordinates.size() < 2) {
            throw new IllegalArgumentException("Donor path does not reach an attachment root");
        }
        LineString path = tree.getSegments().get(0).getGeometry().getFactory()
                .createLineString(coordinates.toArray(new Coordinate[0]));
        path.setSRID(32637);
        if (!path.isSimple() || path.getLength() <= 0) {
            throw new IllegalArgumentException("Donor path is not simple");
        }
        return path;
    }

    private static final class DonorContact {
        private final Variant.Segment segment;
        private final Coordinate coordinate;
        private final double measure;
        private DonorContact(Variant.Segment segment, Coordinate coordinate, double measure) {
            this.segment = segment;
            this.coordinate = new Coordinate(coordinate);
            this.measure = measure;
        }
    }

    private static void appendTargetPaths(Variant tree, Set<TypedId> excludedTargets,
            List<AttachmentCandidate> originalAttachments, List<Variant> routes,
            List<AttachmentCandidate> attachments, Set<TypedId> collectedTargets) {
        Map<String, Variant.Node> nodesById = new HashMap<>();
        Map<String, Variant.Segment> rootwardByNode = new HashMap<>();
        for (Variant.Node node : tree.getNodes()) nodesById.put(node.getId(), node);
        for (Variant.Segment segment : tree.getSegments()) {
            if (rootwardByNode.putIfAbsent(segment.getFlowFromNodeId(), segment) != null) {
                throw new IllegalArgumentException("Accepted tree has more than one rootward segment at a node");
            }
        }
        for (Variant.Node target : tree.getNodes()) {
            if (!"oks_connection_point".equals(target.getType())
                    || excludedTargets.contains(target.getInputFeatureId())) continue;
            if (target.getInputFeatureId() == null || !collectedTargets.add(target.getInputFeatureId())) {
                throw new IllegalArgumentException("Target path has a missing or repeated typed ID");
            }
            List<Variant.Node> pathNodes = new ArrayList<>();
            List<Variant.Segment> pathSegments = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            Variant.Node cursor = target;
            while (cursor != null && visited.add(cursor.getId())) {
                pathNodes.add(cursor);
                Variant.Segment segment = rootwardByNode.get(cursor.getId());
                if (segment == null) break;
                pathSegments.add(segment);
                cursor = nodesById.get(segment.getFlowToNodeId());
            }
            if (cursor == null || !visited.contains(cursor.getId())
                    || pathSegments.isEmpty() || rootwardByNode.containsKey(cursor.getId())
                    || !"heat_chamber".equals(cursor.getType())) {
                throw new IllegalArgumentException("Target path has no unique attachment root");
            }
            AttachmentCandidate matching = null;
            for (AttachmentCandidate candidate : originalAttachments) {
                if (!isAttachmentNode(cursor, candidate)) continue;
                if (matching != null && !attachmentKey(matching).equals(attachmentKey(candidate))) {
                    throw new IllegalArgumentException("Attachment root has ambiguous original candidates");
                }
                matching = candidate;
            }
            if (matching == null) throw new IllegalArgumentException("Cannot match tree root to an original attachment");
            routes.add(new Variant(tree.getId() + ":path:" + idKey(target.getInputFeatureId()),
                    pathNodes, pathSegments));
            attachments.add(matching);
        }
    }

    /** Tests a perpendicular direct connection to an internal straight leg of an accepted new pipe. */
    public OptimizationResult attachDirectBranch(String variantId, TreeCandidate accepted,
            FeatureIndex.IndexedFeature target, String trunkSegmentId,
            List<FeatureIndex.IndexedFeature> validationInputs) {
        if (variantId == null || variantId.trim().isEmpty() || accepted == null || target == null
                || trunkSegmentId == null || validationInputs == null) {
            return OptimizationResult.failure("Accepted tree, target, trunk and validation inputs are required");
        }
        try {
            if (!accepted.validation.isAcceptable() || target.getFeature() == null
                    || !"oks_connection_point".equals(target.getFeature().getObjectType())
                    || !(target.getGeometry() instanceof Point)) {
                return OptimizationResult.failure("Only a point target can join an accepted tree");
            }
            Variant source = accepted.variant;
            TypedId targetId = target.getFeature().getId();
            for (Variant.Node node : source.getNodes()) {
                if ("oks_connection_point".equals(node.getType()) && targetId.equals(node.getInputFeatureId())) {
                    return OptimizationResult.failure("Target is already represented by the accepted tree");
                }
            }
            Variant.Segment trunk = null;
            for (Variant.Segment segment : source.getSegments()) {
                if (trunkSegmentId.equals(segment.getId())) trunk = segment;
            }
            if (trunk == null || !"base".equals(trunk.getLayingMethod())
                    || !trunk.getRestrictionIds().isEmpty() || trunk.getGeometry() == null
                    || trunk.getGeometry().getNumPoints() < 2
                    || !trunk.getFlowFromNodeId().equals(trunk.getStartNodeId())
                    || !trunk.getFlowToNodeId().equals(trunk.getEndNodeId())) {
                return OptimizationResult.failure("Only a base trunk without special passages can be split");
            }
            LineString geometry = trunk.getGeometry();
            LengthIndexedLine indexed = new LengthIndexedLine(geometry);
            Coordinate targetCoordinate = target.getGeometry().getCoordinate();
            double measure = indexed.project(targetCoordinate);
            Coordinate chamberCoordinate = indexed.extractPoint(measure);
            if (measure < 0.25 || geometry.getLength() - measure < 0.25
                    || chamberCoordinate.distance(targetCoordinate) < 0.25
                    || !clearOfExistingNetwork(chamberCoordinate, validationInputs)) {
                return OptimizationResult.failure("Direct branch has no eligible internal trunk projection");
            }
            for (Coordinate vertex : geometry.getCoordinates()) {
                if (chamberCoordinate.distance(vertex) < 0.25) {
                    return OptimizationResult.failure("Direct branch projection is too close to a polyline bend");
                }
            }
            for (Variant.Node node : source.getNodes()) {
                if (chamberCoordinate.distance(new Coordinate(node.getX(), node.getY())) < 0.25) {
                    return OptimizationResult.failure("Branch chamber is too close to an existing tree node");
                }
            }
            LineString before = (LineString) indexed.extractLine(0, measure);
            LineString after = (LineString) indexed.extractLine(measure, geometry.getLength());
            before.setSRID(geometry.getSRID());
            after.setSRID(geometry.getSRID());
            LineString branch = geometry.getFactory().createLineString(new Coordinate[] {
                    targetCoordinate, chamberCoordinate});
            branch.setSRID(geometry.getSRID());
            String suffix = ":direct:" + idKey(targetId);
            String chamberId = variantId + suffix + ":chamber";
            String targetNodeId = variantId + suffix + ":target";
            List<Variant.Node> nodes = new ArrayList<>(source.getNodes());
            nodes.add(new Variant.Node(chamberId, chamberCoordinate.x, chamberCoordinate.y, "heat_chamber"));
            nodes.add(new Variant.Node(targetNodeId, targetCoordinate.x, targetCoordinate.y,
                    "oks_connection_point", targetId));
            List<Variant.Segment> segments = new ArrayList<>();
            for (Variant.Segment segment : source.getSegments()) {
                if (!segment.getId().equals(trunkSegmentId)) {
                    segments.add(segment);
                    continue;
                }
                segments.add(copySegment(segment, variantId + suffix + ":trunk-before",
                        segment.getStartNodeId(), chamberId, segment.getFlowFromNodeId(), chamberId, before));
                segments.add(copySegment(segment, variantId + suffix + ":trunk-after",
                        chamberId, segment.getEndNodeId(), chamberId, segment.getFlowToNodeId(), after));
            }
            segments.add(new Variant.Segment(variantId + suffix + ":branch", targetNodeId, chamberId,
                    branch, targetNodeId, chamberId, BigDecimal.ONE, 50, "base",
                    Collections.emptySet(), RuleCatalog.baseMultiplier()));
            return finalizeCandidate(new Variant(variantId, nodes, segments), validationInputs);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return OptimizationResult.failure(exception.getMessage());
        }
    }

    /** Atomically joins a searched branch at an internal base leg of a validated tree. */
    public OptimizationResult attachRoutedBranch(String variantId, TreeCandidate accepted,
            FeatureIndex.IndexedFeature target, String trunkSegmentId, LineString branch,
            int searchedDiameterMm, List<FeatureIndex.IndexedFeature> validationInputs) {
        if (variantId == null || variantId.trim().isEmpty() || accepted == null || target == null
                || trunkSegmentId == null || branch == null || validationInputs == null) {
            return OptimizationResult.failure("Accepted tree, target, trunk, route and inputs are required");
        }
        try {
            if (!accepted.validation.isAcceptable() || target.getFeature() == null
                    || !"oks_connection_point".equals(target.getFeature().getObjectType())
                    || !(target.getGeometry() instanceof Point) || branch.getSRID() != 32637
                    || branch.getNumPoints() < 2 || !branch.isValid() || branch.getLength() <= 0
                    || !RuleCatalog.diameter(searchedDiameterMm).isPresent()) {
                return OptimizationResult.failure("Branch must be a valid projected route from a point target");
            }
            Variant source = accepted.variant;
            TypedId targetId = target.getFeature().getId();
            for (Variant.Node node : source.getNodes()) {
                if ("oks_connection_point".equals(node.getType()) && targetId.equals(node.getInputFeatureId())) {
                    return OptimizationResult.failure("Target is already represented by the accepted tree");
                }
            }
            Variant.Segment trunk = null;
            for (Variant.Segment segment : source.getSegments()) {
                if (trunkSegmentId.equals(segment.getId())) trunk = segment;
            }
            if (trunk == null || !"base".equals(trunk.getLayingMethod())
                    || !trunk.getRestrictionIds().isEmpty() || trunk.getGeometry() == null
                    || !trunk.getFlowFromNodeId().equals(trunk.getStartNodeId())
                    || !trunk.getFlowToNodeId().equals(trunk.getEndNodeId())) {
                return OptimizationResult.failure("Only a base trunk can be split for a routed branch");
            }
            Coordinate targetCoordinate = target.getGeometry().getCoordinate();
            Coordinate goalCoordinate = branch.getCoordinateN(branch.getNumPoints() - 1);
            if (targetCoordinate.distance(branch.getCoordinateN(0)) > 1e-6) {
                return OptimizationResult.failure("Branch must start at its target");
            }
            LineString geometry = trunk.getGeometry();
            LengthIndexedLine indexed = new LengthIndexedLine(geometry);
            double measure = indexed.project(goalCoordinate);
            Coordinate chamberCoordinate = indexed.extractPoint(measure);
            if (measure < 0.25 || geometry.getLength() - measure < 0.25
                    || chamberCoordinate.distance(goalCoordinate) > 1e-6
                    || chamberCoordinate.distance(targetCoordinate) < 0.25
                    || !clearOfExistingNetwork(chamberCoordinate, validationInputs)) {
                return OptimizationResult.failure("Branch goal must be internal to an eligible trunk");
            }
            for (Coordinate vertex : geometry.getCoordinates()) {
                if (chamberCoordinate.distance(vertex) < 0.25) {
                    return OptimizationResult.failure("Branch chamber is too close to a polyline bend");
                }
            }
            for (Variant.Node node : source.getNodes()) {
                if (chamberCoordinate.distance(new Coordinate(node.getX(), node.getY())) < 0.25) {
                    return OptimizationResult.failure("Branch chamber is too close to another tree node");
                }
            }
            for (Variant.Segment segment : source.getSegments()) {
                Geometry intersection = branch.intersection(segment.getGeometry());
                if (intersection.isEmpty()) continue;
                if (intersection.getDimension() != 0 || intersection.getNumPoints() != 1
                        || intersection.getCoordinate().distance(goalCoordinate) > 1e-6) {
                    return OptimizationResult.failure("Branch intersects the accepted tree outside its goal");
                }
            }
            List<SpecialCrossingBuilder.CrossingRestriction> crossings = new ArrayList<>();
            for (FeatureIndex.IndexedFeature input : validationInputs) {
                InputFeature feature = input.getFeature();
                String type = null;
                Double width = null;
                if ("heat_network".equals(feature.getObjectType())) {
                    type = "heat_network";
                    JsonNode diameter = feature.getProperties().getExtra().get("diameter");
                    if (diameter == null || !diameter.canConvertToInt()) {
                        return OptimizationResult.failure("Existing network diameter is required");
                    }
                    width = RuleCatalog.diameter(diameter.intValue()).get().getPairWidthM().doubleValue();
                } else if ("restriction".equals(feature.getObjectType())) {
                    JsonNode restrictionType = feature.getProperties().getExtra().get("restriction_type");
                    if (restrictionType == null || !restrictionType.isTextual()) continue;
                    type = restrictionType.textValue();
                    JsonNode declaredWidth = feature.getProperties().getExtra().get("width_m");
                    if (declaredWidth != null && declaredWidth.isNumber()) width = declaredWidth.doubleValue();
                    if ("gas_pipeline".equals(type) && width == null) width = 0.40;
                    if ("power_cable".equals(type) && width == null) width = 0.20;
                }
                if (type != null && RuleCatalog.restriction(type).isPresent()
                        && RuleCatalog.restriction(type).get().getCrossing()
                            == RestrictionRule.Crossing.SPECIAL_PASSAGE) {
                    crossings.add(new SpecialCrossingBuilder.CrossingRestriction(feature.getId(), type,
                            input.getGeometry(), width));
                }
            }
            List<IntersectionSegment> parts = crossingBuilder.build(branch, crossings,
                    searchedDiameterMm, true, true);
            LineString before = (LineString) indexed.extractLine(0, measure);
            LineString after = (LineString) indexed.extractLine(measure, geometry.getLength());
            before.setSRID(32637);
            after.setSRID(32637);
            String suffix = ":routed:" + idKey(targetId);
            String chamberId = variantId + suffix + ":chamber";
            String targetNodeId = variantId + suffix + ":target";
            List<Variant.Node> nodes = new ArrayList<>(source.getNodes());
            nodes.add(new Variant.Node(chamberId, chamberCoordinate.x, chamberCoordinate.y, "heat_chamber"));
            nodes.add(new Variant.Node(targetNodeId, targetCoordinate.x, targetCoordinate.y,
                    "oks_connection_point", targetId));
            List<Variant.Segment> segments = new ArrayList<>();
            for (Variant.Segment segment : source.getSegments()) {
                if (!segment.getId().equals(trunkSegmentId)) {
                    segments.add(segment);
                    continue;
                }
                segments.add(copySegment(segment, variantId + suffix + ":trunk-before",
                        segment.getStartNodeId(), chamberId, segment.getFlowFromNodeId(), chamberId, before));
                segments.add(copySegment(segment, variantId + suffix + ":trunk-after",
                        chamberId, segment.getEndNodeId(), chamberId, segment.getFlowToNodeId(), after));
            }
            String from = targetNodeId;
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                IntersectionSegment part = parts.get(partIndex);
                String to = partIndex == parts.size() - 1 ? chamberId
                        : variantId + suffix + ":technical:" + partIndex;
                if (partIndex < parts.size() - 1) {
                    Coordinate coordinate = new LengthIndexedLine(branch).extractPoint(part.getEndMeasureM());
                    nodes.add(new Variant.Node(to, coordinate.x, coordinate.y, "technical_node"));
                }
                LineString piece = (LineString) new LengthIndexedLine(branch)
                        .extractLine(part.getStartMeasureM(), part.getEndMeasureM());
                piece.setSRID(32637);
                segments.add(new Variant.Segment(variantId + suffix + ":branch:" + partIndex,
                        from, to, piece, from, to, BigDecimal.ONE, searchedDiameterMm,
                        part.getMethod() == IntersectionSegment.Method.SPECIAL ? "special" : "base",
                        part.getRestrictionIds(), part.getMultiplier()));
                from = to;
            }
            return finalizeCandidate(new Variant(variantId, nodes, segments), validationInputs);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return OptimizationResult.failure(exception.getMessage());
        }
    }

    /** Atomically joins a searched branch to an existing tree chamber with spare capacity. */
    public OptimizationResult attachRoutedBranchAtChamber(String variantId, TreeCandidate accepted,
            FeatureIndex.IndexedFeature target, String chamberNodeId, LineString branch,
            int searchedDiameterMm, List<FeatureIndex.IndexedFeature> validationInputs) {
        if (variantId == null || variantId.trim().isEmpty() || accepted == null || target == null
                || chamberNodeId == null || branch == null || validationInputs == null) {
            return OptimizationResult.failure("Accepted tree, target, chamber, route and inputs are required");
        }
        try {
            if (!accepted.validation.isAcceptable() || target.getFeature() == null
                    || !"oks_connection_point".equals(target.getFeature().getObjectType())
                    || !(target.getGeometry() instanceof Point) || branch.getSRID() != 32637
                    || branch.getNumPoints() < 2 || !branch.isValid() || branch.getLength() <= 0
                    || !RuleCatalog.diameter(searchedDiameterMm).isPresent()) {
                return OptimizationResult.failure("Branch must be a valid projected route from a point target");
            }
            Variant source = accepted.variant;
            TypedId targetId = target.getFeature().getId();
            for (Variant.Node node : source.getNodes()) {
                if ("oks_connection_point".equals(node.getType()) && targetId.equals(node.getInputFeatureId())) {
                    return OptimizationResult.failure("Target is already represented by the accepted tree");
                }
            }
            Variant.Node chamber = source.getNodes().stream()
                    .filter(node -> chamberNodeId.equals(node.getId()) && "heat_chamber".equals(node.getType()))
                    .findFirst().orElse(null);
            long chamberDegree = source.getSegments().stream().filter(segment ->
                    chamberNodeId.equals(segment.getStartNodeId())
                            || chamberNodeId.equals(segment.getEndNodeId())).count();
            if (chamber == null || chamberDegree == 0 || chamberDegree >= 4) {
                return OptimizationResult.failure("Selected tree chamber has no free connection");
            }
            Coordinate targetCoordinate = target.getGeometry().getCoordinate();
            Coordinate goalCoordinate = branch.getCoordinateN(branch.getNumPoints() - 1);
            if (targetCoordinate.distance(branch.getCoordinateN(0)) > 1e-6
                    || goalCoordinate.distance(new Coordinate(chamber.getX(), chamber.getY())) > 1e-6
                    || targetCoordinate.distance(goalCoordinate) < 0.25) {
                return OptimizationResult.failure("Branch endpoints must match the target and selected chamber");
            }
            for (Variant.Segment segment : source.getSegments()) {
                Geometry intersection = branch.intersection(segment.getGeometry());
                if (!intersection.isEmpty() && (intersection.getDimension() != 0
                        || intersection.getNumPoints() != 1
                        || intersection.getCoordinate().distance(goalCoordinate) > 1e-6)) {
                    return OptimizationResult.failure("Branch intersects the accepted tree outside its chamber");
                }
            }
            List<SpecialCrossingBuilder.CrossingRestriction> crossings = new ArrayList<>();
            for (FeatureIndex.IndexedFeature input : validationInputs) {
                InputFeature feature = input.getFeature();
                String type = null;
                Double width = null;
                if ("heat_network".equals(feature.getObjectType())) {
                    type = "heat_network";
                    JsonNode diameter = feature.getProperties().getExtra().get("diameter");
                    if (diameter == null || !diameter.canConvertToInt()) {
                        return OptimizationResult.failure("Existing network diameter is required");
                    }
                    width = RuleCatalog.diameter(diameter.intValue()).get().getPairWidthM().doubleValue();
                } else if ("restriction".equals(feature.getObjectType())) {
                    JsonNode restrictionType = feature.getProperties().getExtra().get("restriction_type");
                    if (restrictionType == null || !restrictionType.isTextual()) continue;
                    type = restrictionType.textValue();
                    JsonNode declaredWidth = feature.getProperties().getExtra().get("width_m");
                    if (declaredWidth != null && declaredWidth.isNumber()) width = declaredWidth.doubleValue();
                    if ("gas_pipeline".equals(type) && width == null) width = 0.40;
                    if ("power_cable".equals(type) && width == null) width = 0.20;
                }
                if (type != null && RuleCatalog.restriction(type).isPresent()
                        && RuleCatalog.restriction(type).get().getCrossing()
                                == RestrictionRule.Crossing.SPECIAL_PASSAGE) {
                    crossings.add(new SpecialCrossingBuilder.CrossingRestriction(feature.getId(), type,
                            input.getGeometry(), width));
                }
            }
            List<IntersectionSegment> parts = crossingBuilder.build(branch, crossings,
                    searchedDiameterMm, true, true);
            String suffix = ":routed-chamber:" + idKey(targetId);
            String targetNodeId = variantId + suffix + ":target";
            List<Variant.Node> nodes = new ArrayList<>(source.getNodes());
            nodes.add(new Variant.Node(targetNodeId, targetCoordinate.x, targetCoordinate.y,
                    "oks_connection_point", targetId));
            List<Variant.Segment> segments = new ArrayList<>(source.getSegments());
            LengthIndexedLine indexed = new LengthIndexedLine(branch);
            String from = targetNodeId;
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                IntersectionSegment part = parts.get(partIndex);
                String to = partIndex == parts.size() - 1 ? chamberNodeId
                        : variantId + suffix + ":technical:" + partIndex;
                if (partIndex < parts.size() - 1) {
                    Coordinate coordinate = indexed.extractPoint(part.getEndMeasureM());
                    nodes.add(new Variant.Node(to, coordinate.x, coordinate.y, "technical_node"));
                }
                LineString piece = (LineString) indexed.extractLine(part.getStartMeasureM(), part.getEndMeasureM());
                piece.setSRID(32637);
                segments.add(new Variant.Segment(variantId + suffix + ":branch:" + partIndex,
                        from, to, piece, from, to, BigDecimal.ONE, searchedDiameterMm,
                        part.getMethod() == IntersectionSegment.Method.SPECIAL ? "special" : "base",
                        part.getRestrictionIds(), part.getMultiplier()));
                from = to;
            }
            return finalizeCandidate(new Variant(variantId, nodes, segments), validationInputs);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return OptimizationResult.failure(exception.getMessage());
        }
    }

    private OptimizationResult finalizeCandidate(Variant combined,
            List<FeatureIndex.IndexedFeature> validationInputs) {
            List<FeatureIndex.IndexedFeature> applicableInputs = inputsForRepresentedTargets(combined, validationInputs);
            Map<String, BigDecimal> demandByTargetNode = targetDemands(combined, applicableInputs);
            List<NetworkEdge> edges = networkEdges(combined);
            Map<String, BigDecimal> flowByEdge = flowAggregator.aggregate(edges, demandByTargetNode);
            Map<String, DiameterRule> diameterByEdge = diameterSelector.selectForTree(edges, flowByEdge,
                    targetNodeIds(combined));
            Variant recalculated = applyFlowsAndDiameters(combined, flowByEdge, diameterByEdge);
            ValidationReport report = validator.validate(recalculated, new ValidationContext(applicableInputs));
            if (!report.isAcceptable()) {
                return OptimizationResult.rejected(report, "Combined tree failed independent validation");
            }
            double totalLength = 0;
            for (Variant.Segment segment : recalculated.getSegments()) totalLength += segment.getGeometry().getLength();
            return OptimizationResult.found(new TreeCandidate(recalculated, report, totalLength));
    }

    private static Variant combine(String id, List<Variant> routes, List<AttachmentCandidate> attachments) {
        Map<String, Variant.Node> nodesByCanonicalId = new LinkedHashMap<>();
        Map<String, String> nodeMapping = new HashMap<>();
        Set<String> targetIds = new LinkedHashSet<>();
        for (int routeIndex = 0; routeIndex < routes.size(); routeIndex++) {
            Variant route = routes.get(routeIndex);
            AttachmentCandidate attachment = attachments.get(routeIndex);
            if (route == null || attachment == null) throw new IllegalArgumentException("Route and attachment cannot be null");
            List<Variant.Node> targetNodes = new ArrayList<>();
            List<Variant.Node> attachmentNodes = new ArrayList<>();
            for (Variant.Node node : route.getNodes()) {
                if ("oks_connection_point".equals(node.getType())) targetNodes.add(node);
                else if ("heat_chamber".equals(node.getType()) && isAttachmentNode(node, attachment)
                        && degree(route, node.getId()) == 1) attachmentNodes.add(node);
            }
            if (targetNodes.size() != 1 || attachmentNodes.size() != 1) {
                throw new IllegalArgumentException("Each input route must have exactly one target and its attachment chamber");
            }
            Variant.Node target = targetNodes.get(0);
            if (target.getInputFeatureId() == null || !targetIds.add(idKey(target.getInputFeatureId()))) {
                throw new IllegalArgumentException("Every route must represent a distinct typed target ID");
            }
            Variant.Node root = attachmentNodes.get(0);
            String rootKey = attachmentKey(attachment);
            String rootId = "attachment:" + rootKey;
            for (Variant.Node node : route.getNodes()) {
                String canonical = canonicalNodeId(node, routeIndex, root, rootId);
                nodeMapping.put(routeIndex + "\u0000" + node.getId(), canonical);
                Variant.Node existing = nodesByCanonicalId.get(canonical);
                if (existing == null) {
                    nodesByCanonicalId.put(canonical, new Variant.Node(canonical, node.getX(), node.getY(),
                            node.getType(), node.getInputFeatureId()));
                } else if (!sameNode(existing, node)) {
                    throw new IllegalArgumentException("Coincident route nodes disagree on type or input link");
                }
            }
        }

        Map<String, Variant.Segment> uniqueSegments = new LinkedHashMap<>();
        int serial = 0;
        for (int routeIndex = 0; routeIndex < routes.size(); routeIndex++) {
            Variant route = routes.get(routeIndex);
            for (Variant.Segment segment : route.getSegments()) {
                String start = nodeMapping.get(routeIndex + "\u0000" + segment.getStartNodeId());
                String end = nodeMapping.get(routeIndex + "\u0000" + segment.getEndNodeId());
                String flowFrom = nodeMapping.get(routeIndex + "\u0000" + segment.getFlowFromNodeId());
                String flowTo = nodeMapping.get(routeIndex + "\u0000" + segment.getFlowToNodeId());
                if (start == null || end == null || flowFrom == null || flowTo == null
                        || segment.getGeometry() == null || segment.getLayingMethod() == null) {
                    throw new IllegalArgumentException("Route segment has incomplete topology or engineering data");
                }
                String key = segmentKey(segment, start, end, flowFrom, flowTo);
                if (!uniqueSegments.containsKey(key)) {
                    uniqueSegments.put(key, new Variant.Segment(id + ":segment:" + serial++, start, end,
                            segment.getGeometry(), flowFrom, flowTo, segment.getFlowTph(), segment.getDiameterMm(),
                            segment.getLayingMethod(), segment.getRestrictionIds(), segment.getMultiplier()));
                }
            }
        }
        return new Variant(id, new ArrayList<>(nodesByCanonicalId.values()), new ArrayList<>(uniqueSegments.values()));
    }

    /** Turns a common terminal route suffix into one trunk with a chamber at its divergence point. */
    private static List<Variant> insertSharedBranchCameras(List<Variant> routes,
            List<AttachmentCandidate> attachments, List<FeatureIndex.IndexedFeature> inputs) {
        List<Variant> prepared = new ArrayList<>(routes);
        for (int first = 0; first < prepared.size(); first++) {
            for (int second = first + 1; second < prepared.size(); second++) {
                AttachmentCandidate firstAttachment = attachments.get(first);
                AttachmentCandidate secondAttachment = attachments.get(second);
                if (!attachmentKey(firstAttachment).equals(attachmentKey(secondAttachment))) continue;
                Coordinate branch = sharedSuffixStart(prepared.get(first), firstAttachment,
                        prepared.get(second), secondAttachment);
                if (branch == null || !clearOfExistingNetwork(branch, inputs)) continue;
                String key = attachmentKey(firstAttachment);
                for (int routeIndex = 0; routeIndex < prepared.size(); routeIndex++) {
                    if (key.equals(attachmentKey(attachments.get(routeIndex)))) {
                        prepared.set(routeIndex, splitFinalSegmentAt(prepared.get(routeIndex),
                                attachments.get(routeIndex), branch));
                    }
                }
            }
        }
        return prepared;
    }

    private static boolean clearOfExistingNetwork(Coordinate branch, List<FeatureIndex.IndexedFeature> inputs) {
        for (FeatureIndex.IndexedFeature input : inputs) {
            if (input == null || input.getFeature() == null || input.getGeometry() == null) continue;
            String type = input.getFeature().getObjectType();
            if (!"heat_network".equals(type) && !"heat_chamber".equals(type)) continue;
            Point point = input.getGeometry().getFactory().createPoint(new Coordinate(branch));
            if (input.getGeometry().distance(point) <= 0.25) return false;
        }
        return true;
    }

    private static Coordinate sharedSuffixStart(Variant first, AttachmentCandidate firstAttachment,
            Variant second, AttachmentCandidate secondAttachment) {
        Variant.Node firstRoot = attachmentNode(first, firstAttachment);
        Variant.Node secondRoot = attachmentNode(second, secondAttachment);
        if (firstRoot == null || secondRoot == null) return null;
        Variant.Segment firstTerminal = terminalSegment(first, firstRoot.getId());
        Variant.Segment secondTerminal = terminalSegment(second, secondRoot.getId());
        if (firstTerminal == null || secondTerminal == null) return null;
        Geometry overlap;
        try {
            overlap = firstTerminal.getGeometry().intersection(secondTerminal.getGeometry());
        } catch (RuntimeException exception) {
            return null;
        }
        LineString shared = rootTouchingLine(overlap, firstRoot.getX(), firstRoot.getY());
        if (shared == null || shared.getLength() < 0.5) return null;
        Coordinate start = shared.getCoordinateN(0);
        Coordinate end = shared.getCoordinateN(shared.getNumPoints() - 1);
        Coordinate root = new Coordinate(firstRoot.getX(), firstRoot.getY());
        Coordinate branch = start.distance(root) <= 1e-7 ? end : start;
        LengthIndexedLine firstLine = new LengthIndexedLine(firstTerminal.getGeometry());
        LengthIndexedLine secondLine = new LengthIndexedLine(secondTerminal.getGeometry());
        double firstMeasure = firstLine.project(branch);
        double secondMeasure = secondLine.project(branch);
        if (firstLine.extractPoint(firstMeasure).distance(branch) > 1e-7
                || secondLine.extractPoint(secondMeasure).distance(branch) > 1e-7
                || firstMeasure < 0.25 || secondMeasure < 0.25
                || firstTerminal.getGeometry().getLength() - firstMeasure < 0.25
                || secondTerminal.getGeometry().getLength() - secondMeasure < 0.25) return null;
        return new Coordinate(branch);
    }

    private static LineString rootTouchingLine(Geometry geometry, double rootX, double rootY) {
        LineString best = null;
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry component = geometry.getGeometryN(i);
            if (!(component instanceof LineString)) continue;
            LineString line = (LineString) component;
            Coordinate start = line.getCoordinateN(0);
            Coordinate end = line.getCoordinateN(line.getNumPoints() - 1);
            if (start.distance(new Coordinate(rootX, rootY)) > 1e-7
                    && end.distance(new Coordinate(rootX, rootY)) > 1e-7) continue;
            if (best == null || line.getLength() > best.getLength()) best = line;
        }
        return best;
    }

    private static Variant.Node attachmentNode(Variant variant, AttachmentCandidate attachment) {
        Variant.Node found = null;
        for (Variant.Node node : variant.getNodes()) {
            if (!"heat_chamber".equals(node.getType()) || !isAttachmentNode(node, attachment)) continue;
            if (found != null) return null;
            found = node;
        }
        return found;
    }

    private static Variant.Segment terminalSegment(Variant variant, String rootId) {
        Variant.Segment found = null;
        for (Variant.Segment segment : variant.getSegments()) {
            if (!rootId.equals(segment.getFlowToNodeId())) continue;
            if (found != null) return null;
            found = segment;
        }
        return found;
    }

    private static Variant splitFinalSegmentAt(Variant route, AttachmentCandidate attachment, Coordinate branch) {
        Variant.Node root = attachmentNode(route, attachment);
        if (root == null) return route;
        Variant.Segment terminal = terminalSegment(route, root.getId());
        if (terminal == null || !terminal.getFlowFromNodeId().equals(terminal.getStartNodeId())
                || !terminal.getFlowToNodeId().equals(terminal.getEndNodeId())) return route;
        LineString geometry = terminal.getGeometry();
        LengthIndexedLine indexed = new LengthIndexedLine(geometry);
        double measure = indexed.project(branch);
        Coordinate projected = indexed.extractPoint(measure);
        if (projected.distance(branch) > 1e-7 || measure < 0.25 || geometry.getLength() - measure < 0.25) return route;
        Geometry beforeGeometry = indexed.extractLine(0, measure);
        Geometry afterGeometry = indexed.extractLine(measure, geometry.getLength());
        if (!(beforeGeometry instanceof LineString) || !(afterGeometry instanceof LineString)) return route;
        String branchId = route.getId() + ":shared-chamber:" + Double.toHexString(branch.x) + ":" + Double.toHexString(branch.y);
        List<Variant.Node> nodes = new ArrayList<>(route.getNodes());
        for (Variant.Node node : nodes) {
            if (node.getX() == branch.x && node.getY() == branch.y) return route;
        }
        nodes.add(new Variant.Node(branchId, branch.x, branch.y, "heat_chamber"));
        List<Variant.Segment> segments = new ArrayList<>();
        for (Variant.Segment segment : route.getSegments()) {
            if (!segment.getId().equals(terminal.getId())) {
                segments.add(segment);
                continue;
            }
            LineString before = (LineString) beforeGeometry;
            LineString after = (LineString) afterGeometry;
            before.setSRID(geometry.getSRID());
            after.setSRID(geometry.getSRID());
            segments.add(copySegment(terminal, terminal.getId() + ":to-shared-chamber",
                    terminal.getStartNodeId(), branchId, terminal.getFlowFromNodeId(), branchId, before));
            segments.add(copySegment(terminal, terminal.getId() + ":shared-trunk",
                    branchId, terminal.getEndNodeId(), branchId, terminal.getFlowToNodeId(), after));
        }
        try {
            return new Variant(route.getId(), nodes, segments);
        } catch (IllegalArgumentException exception) {
            return route;
        }
    }

    private static Variant.Segment copySegment(Variant.Segment source, String id, String start, String end,
            String flowFrom, String flowTo, LineString geometry) {
        return new Variant.Segment(id, start, end, geometry, flowFrom, flowTo, source.getFlowTph(),
                source.getDiameterMm(), source.getLayingMethod(), source.getRestrictionIds(), source.getMultiplier());
    }

    private static String canonicalNodeId(Variant.Node node, int routeIndex, Variant.Node root, String rootId) {
        if (node.getId().equals(root.getId())) return rootId;
        if ("oks_connection_point".equals(node.getType())) return "target:" + idKey(node.getInputFeatureId());
        String coordinate = Double.toHexString(node.getX()) + ":" + Double.toHexString(node.getY());
        if ("heat_chamber".equals(node.getType())) {
            return "chamber:" + (node.getInputFeatureId() == null ? "new" : idKey(node.getInputFeatureId())) + ":" + coordinate;
        }
        // Equal technical vertices may identify the same shared pipe boundary. Any resulting branch is
        // still rejected unless a heat chamber is present.
        if ("technical_node".equals(node.getType())) return "technical:" + coordinate;
        return "route:" + routeIndex + ":" + node.getId();
    }

    private static boolean isEndpoint(Variant.Node node, AttachmentCandidate attachment) {
        return node.getX() == attachment.getEndpoint().getX() && node.getY() == attachment.getEndpoint().getY();
    }

    private static boolean isAttachmentNode(Variant.Node node, AttachmentCandidate attachment) {
        TypedId expected = attachment.getEndpointKind() == AttachmentCandidate.EndpointKind.EXISTING_CHAMBER
                ? attachment.getExistingChamberId() : attachment.getNetworkId();
        return isEndpoint(node, attachment) && expected.equals(node.getInputFeatureId());
    }

    private static int degree(Variant variant, String nodeId) {
        int count = 0;
        for (Variant.Segment segment : variant.getSegments()) {
            if (segment.getStartNodeId().equals(nodeId) || segment.getEndNodeId().equals(nodeId)) count++;
        }
        return count;
    }

    private static boolean sameNode(Variant.Node canonical, Variant.Node incoming) {
        return canonical.getType().equals(incoming.getType())
                && canonical.getX() == incoming.getX() && canonical.getY() == incoming.getY()
                && java.util.Objects.equals(canonical.getInputFeatureId(), incoming.getInputFeatureId());
    }

    private static String segmentKey(Variant.Segment segment, String start, String end, String flowFrom, String flowTo) {
        List<String> restrictions = new ArrayList<>();
        for (TypedId restrictionId : segment.getRestrictionIds()) restrictions.add(idKey(restrictionId));
        Collections.sort(restrictions);
        LineString geometry = segment.getGeometry();
        StringBuilder coordinates = new StringBuilder();
        for (org.locationtech.jts.geom.Coordinate coordinate : geometry.getCoordinates()) {
            coordinates.append(Double.toHexString(coordinate.x)).append(',')
                    .append(Double.toHexString(coordinate.y)).append(';');
        }
        return start + '|' + end + '|' + flowFrom + '|' + flowTo + '|' + coordinates + '|'
                + segment.getLayingMethod() + '|' + restrictions + '|' + segment.getMultiplier();
    }

    private static void validateAttachmentCapacity(Variant combined, List<AttachmentCandidate> attachments) {
        Map<String, Integer> baselineByRoot = new HashMap<>();
        for (AttachmentCandidate attachment : attachments) {
            String root = attachmentKey(attachment);
            Integer previous = baselineByRoot.putIfAbsent(root, attachment.getExistingConnectionCount());
            if (previous != null && previous.intValue() != attachment.getExistingConnectionCount()) {
                throw new IllegalArgumentException("Attachment candidates disagree about existing chamber connections");
            }
        }
        Map<String, Integer> newConnectionsByRoot = new HashMap<>();
        for (AttachmentCandidate attachment : attachments) {
            Variant.Node root = null;
            for (Variant.Node node : combined.getNodes()) {
                if ("heat_chamber".equals(node.getType()) && isAttachmentNode(node, attachment)) {
                    if (root != null && !root.getId().equals(node.getId())) {
                        throw new IllegalArgumentException("One attachment candidate maps to multiple tree chambers");
                    }
                    root = node;
                }
            }
            if (root == null) throw new IllegalArgumentException("Combined tree lost an attachment chamber");
            int connections = degree(combined, root.getId());
            String rootKey = attachmentKey(attachment);
            Integer previous = newConnectionsByRoot.putIfAbsent(rootKey, connections);
            if (previous != null && previous.intValue() != connections) {
                throw new IllegalArgumentException("Merged routes disagree about their shared attachment degree");
            }
        }
        for (Map.Entry<String, Integer> entry : newConnectionsByRoot.entrySet()) {
            if (baselineByRoot.get(entry.getKey()) + entry.getValue() > 4) {
                throw new IllegalArgumentException("Combined routes exceed the four-connection chamber limit");
            }
        }
    }

    private static List<FeatureIndex.IndexedFeature> inputsForRepresentedTargets(Variant variant,
            List<FeatureIndex.IndexedFeature> inputs) {
        Set<TypedId> represented = new LinkedHashSet<>();
        for (Variant.Node node : variant.getNodes()) {
            if ("oks_connection_point".equals(node.getType())) represented.add(node.getInputFeatureId());
        }
        List<FeatureIndex.IndexedFeature> result = new ArrayList<>();
        for (FeatureIndex.IndexedFeature indexed : inputs) {
            InputFeature feature = indexed.getFeature();
            if (!"oks_connection_point".equals(feature.getObjectType()) || represented.contains(feature.getId())) {
                result.add(indexed);
            }
        }
        return result;
    }

    private static Map<String, BigDecimal> targetDemands(Variant variant, List<FeatureIndex.IndexedFeature> inputs) {
        Map<TypedId, FeatureIndex.IndexedFeature> byId = new HashMap<>();
        for (FeatureIndex.IndexedFeature input : inputs) byId.put(input.getFeature().getId(), input);
        Map<String, BigDecimal> demands = new LinkedHashMap<>();
        for (Variant.Node node : variant.getNodes()) {
            if (!"oks_connection_point".equals(node.getType())) continue;
            FeatureIndex.IndexedFeature input = byId.get(node.getInputFeatureId());
            if (input == null) throw new IllegalArgumentException("Cannot find an input feature for a target");
            com.fasterxml.jackson.databind.JsonNode flow = input.getFeature().getProperties().getExtra().get("flow_tph");
            if (flow == null || !flow.isNumber()) throw new IllegalArgumentException("Target flow is missing or invalid");
            BigDecimal demand = flow.decimalValue();
            if (demand.signum() <= 0) throw new IllegalArgumentException("Target flow must be positive");
            demands.put(node.getId(), demand);
        }
        return demands;
    }

    private static List<NetworkEdge> networkEdges(Variant variant) {
        List<NetworkEdge> edges = new ArrayList<>();
        for (Variant.Segment segment : variant.getSegments()) {
            edges.add(new NetworkEdge(segment.getId(), segment.getFlowFromNodeId(), segment.getFlowToNodeId(),
                    segment.getGeometry().getLength(), segment.getDiameterMm()));
        }
        return edges;
    }

    private static List<String> targetNodeIds(Variant variant) {
        List<String> result = new ArrayList<>();
        for (Variant.Node node : variant.getNodes()) {
            if ("oks_connection_point".equals(node.getType())) result.add(node.getId());
        }
        return result;
    }

    private static Variant applyFlowsAndDiameters(Variant variant, Map<String, BigDecimal> flows,
            Map<String, DiameterRule> diameters) {
        Map<String, Variant.Segment> updated = new LinkedHashMap<>();
        for (Variant.Segment segment : variant.getSegments()) {
            updated.put(segment.getId(), new Variant.Segment(segment.getId(), segment.getStartNodeId(),
                    segment.getEndNodeId(), segment.getGeometry(), segment.getFlowFromNodeId(),
                    segment.getFlowToNodeId(), flows.get(segment.getId()), diameters.get(segment.getId()).getDiameterMm(),
                    segment.getLayingMethod(), segment.getRestrictionIds(), segment.getMultiplier()));
        }
        return new Variant(variant.getId(), variant.getNodes(), new ArrayList<>(updated.values()));
    }

    public static String attachmentKey(AttachmentCandidate attachment) {
        if (attachment.getEndpointKind() == AttachmentCandidate.EndpointKind.EXISTING_CHAMBER) {
            return "chamber:" + idKey(attachment.getExistingChamberId());
        }
        return "network:" + idKey(attachment.getNetworkId()) + '@'
                + Double.toHexString(attachment.getEndpoint().getX()) + ':'
                + Double.toHexString(attachment.getEndpoint().getY());
    }

    private static String idKey(TypedId id) {
        if (id == null) throw new IllegalArgumentException("Typed input ID is required");
        return id.isNumeric() ? "number:" + id.asNumber().stripTrailingZeros().toPlainString()
                : "string:" + id.asString();
    }

    public static final class TreeCandidate {
        private final Variant variant;
        private final ValidationReport validation;
        private final double totalLengthM;
        private TreeCandidate(Variant variant, ValidationReport validation, double totalLengthM) {
            this.variant = variant;
            this.validation = validation;
            this.totalLengthM = totalLengthM;
        }
        public Variant getVariant() { return variant; }
        public ValidationReport getValidation() { return validation; }
        public double getTotalLengthM() { return totalLengthM; }
    }

    public static final class OptimizationResult {
        private final TreeCandidate candidate;
        private final ValidationReport validation;
        private final String detail;
        private OptimizationResult(TreeCandidate candidate, ValidationReport validation, String detail) {
            this.candidate = candidate;
            this.validation = validation;
            this.detail = detail;
        }
        private static OptimizationResult found(TreeCandidate candidate) { return new OptimizationResult(candidate, candidate.validation, null); }
        private static OptimizationResult rejected(ValidationReport validation, String detail) {
            return new OptimizationResult(null, validation, detail);
        }
        private static OptimizationResult failure(String detail) { return new OptimizationResult(null, null, detail); }
        public boolean isFound() { return candidate != null; }
        public TreeCandidate getCandidate() { return candidate; }
        public ValidationReport getValidation() { return validation; }
        public String getDetail() { return detail; }
    }
}
