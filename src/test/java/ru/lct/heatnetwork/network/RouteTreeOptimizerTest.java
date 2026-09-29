package ru.lct.heatnetwork.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.lct.heatnetwork.domain.FeatureProperties;
import ru.lct.heatnetwork.domain.InputFeature;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.Variant;
import ru.lct.heatnetwork.costing.VariantCostCalculator;
import ru.lct.heatnetwork.spatial.FeatureIndex;
import ru.lct.heatnetwork.routing.SingleTargetRouter;
import ru.lct.heatnetwork.routing.TreeRouteRequest;

class RouteTreeOptimizerTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RouteTreeOptimizer optimizer = new RouteTreeOptimizer();

    @Test
    void addsOnlyMissingTargetsFromAnotherValidatedRoot() throws Exception {
        TypedId firstId = TypedId.of("first"), secondId = TypedId.of("second");
        TypedId firstNetwork = TypedId.of("first-network"), secondNetwork = TypedId.of("second-network");
        TypedId firstChamber = TypedId.of("first-chamber"), secondChamber = TypedId.of("second-chamber");
        Point firstRoot = point(50, 0), secondRoot = point(150, 0);
        AttachmentCandidate firstAttachment = attachment(firstNetwork, firstChamber, firstRoot, 1);
        AttachmentCandidate secondAttachment = attachment(secondNetwork, secondChamber, secondRoot, 1);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 1.0, point(0, 0)), target(secondId, 2.0, point(100, 0)),
                indexed(properties(firstNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (50 0, 80 0)")),
                indexed(properties(secondNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (150 0, 180 0)")),
                indexed(new FeatureProperties(firstChamber, "heat_chamber"), firstRoot),
                indexed(new FeatureProperties(secondChamber, "heat_chamber"), secondRoot));
        Variant firstRoute = straightRoute("first-route", firstId, point(0, 0), firstChamber, firstRoot, 1.0);
        Variant secondRoute = straightRoute("second-route", secondId, point(100, 0), secondChamber,
                secondRoot, 2.0);
        RouteTreeOptimizer.OptimizationResult primary = optimizer.optimize("primary",
                Collections.singletonList(firstRoute), Collections.singletonList(firstAttachment), inputs);
        RouteTreeOptimizer.OptimizationResult donor = optimizer.optimize("donor",
                Arrays.asList(firstRoute, secondRoute), Arrays.asList(firstAttachment, secondAttachment), inputs);
        assertTrue(primary.isFound(), () -> primary.getDetail() + issues(primary));
        assertTrue(donor.isFound(), () -> donor.getDetail() + issues(donor));

        RouteTreeOptimizer.OptimizationResult combined = optimizer.combineValidatedTrees("combined-trees",
                primary.getCandidate(), donor.getCandidate(), Arrays.asList(firstAttachment, secondAttachment), inputs);
        assertTrue(combined.isFound(), () -> combined.getDetail() + issues(combined));
        assertEquals(2, combined.getCandidate().getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(2, combined.getCandidate().getVariant().getSegments().size());
        assertEquals(1, primary.getCandidate().getVariant().getSegments().size());
        assertTrue(combined.getCandidate().getValidation().isAcceptable());
    }

    @Test
    void donorPathKeepsThePrimarySharedTrunkAndRecalculatesItsFlow() throws Exception {
        TypedId firstId = TypedId.of("first"), secondId = TypedId.of("second"), thirdId = TypedId.of("third");
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        Point root = point(10, 0);
        AttachmentCandidate attachment = attachment(networkId, chamberId, root, 1);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 3)), target(secondId, 3.0, point(0, -3)),
                target(thirdId, 1.0, point(20, 10)),
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), root));
        Variant first = route("first", firstId, point(0, 3), point(5, 0), root, 2.0);
        Variant second = route("second", secondId, point(0, -3), point(5, 0), root, 3.0);
        Variant third = straightRoute("third", thirdId, point(20, 10), chamberId, root, 1.0);
        RouteTreeOptimizer.OptimizationResult primary = optimizer.optimize("primary-shared",
                Arrays.asList(first, second), Arrays.asList(attachment, attachment), inputs);
        RouteTreeOptimizer.OptimizationResult donor = optimizer.optimize("donor-shared",
                Arrays.asList(first, second, third),
                Arrays.asList(attachment, attachment, attachment), inputs);
        assertTrue(primary.isFound(), () -> primary.getDetail() + issues(primary));
        assertTrue(donor.isFound(), () -> donor.getDetail() + issues(donor));

        RouteTreeOptimizer.OptimizationResult merged = optimizer.combineValidatedTrees("reused-shared",
                primary.getCandidate(), donor.getCandidate(), Collections.singletonList(attachment), inputs);
        assertTrue(merged.isFound(), () -> merged.getDetail() + issues(merged));
        Variant tree = merged.getCandidate().getVariant();
        assertEquals(3, tree.getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(1, tree.getSegments().stream()
                .filter(segment -> segment.getGeometry().getLength() == 5.0).count(),
                "the shared trunk is billed once");
        assertEquals(5.0, tree.getSegments().stream()
                .filter(segment -> segment.getGeometry().getLength() == 5.0).findFirst().orElseThrow()
                .getFlowTph().doubleValue(), 1e-9);
        assertTrue(merged.getValidation().isAcceptable());
    }

    @Test
    void graftsOnlyDonorPrefixAtFirstCrossingAndRecalculatesSharedFlow() throws Exception {
        TypedId firstId = TypedId.of("first"), donorId = TypedId.of("donor");
        TypedId firstNetwork = TypedId.of("first-network"), donorNetwork = TypedId.of("donor-network");
        TypedId firstRootId = TypedId.of("first-root"), donorRootId = TypedId.of("donor-root");
        Point firstRoot = point(30, 0), donorRoot = point(30, 10);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(-10, 0)), target(donorId, 3.0, point(5, 10)),
                indexed(properties(firstNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (30 0, 40 0)")),
                indexed(properties(donorNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (30 10, 40 10)")),
                indexed(new FeatureProperties(firstRootId, "heat_chamber"), firstRoot),
                indexed(new FeatureProperties(donorRootId, "heat_chamber"), donorRoot));
        Variant firstRoute = straightRoute("first-route", firstId, point(-10, 0), firstRootId,
                firstRoot, 2.0);
        Variant donorRoute = new Variant("donor-route", Arrays.asList(
                new Variant.Node("donor-target", 5, 10, "oks_connection_point", donorId),
                new Variant.Node("donor-attachment", 30, 10, "heat_chamber", donorRootId)),
                Collections.singletonList(segment("donor-pipe", "donor-target", "donor-attachment",
                        line("LINESTRING (5 10, 5 -5, 20 -5, 20 0, 25 0, 25 5, 30 10)"), 3.0)));
        AttachmentCandidate firstAttachment = attachment(firstNetwork, firstRootId, firstRoot, 1);
        AttachmentCandidate donorAttachment = attachment(donorNetwork, donorRootId, donorRoot, 1);
        RouteTreeOptimizer.OptimizationResult primary = optimizer.optimize("primary",
                Collections.singletonList(firstRoute), Collections.singletonList(firstAttachment), inputs);
        RouteTreeOptimizer.OptimizationResult donor = optimizer.optimize("donor",
                Collections.singletonList(donorRoute), Collections.singletonList(donorAttachment), inputs);
        assertTrue(primary.isFound(), () -> primary.getDetail() + issues(primary));
        assertTrue(donor.isFound(), () -> donor.getDetail() + issues(donor));
        RouteTreeOptimizer.OptimizationResult whole = optimizer.combineValidatedTrees("whole",
                primary.getCandidate(), donor.getCandidate(),
                Arrays.asList(firstAttachment, donorAttachment), inputs);
        assertTrue(!whole.isFound(), "the full donor overlaps the parent after an earlier crossing");

        RouteTreeOptimizer.OptimizationResult graft = optimizer.graftDonorPrefix("grafted",
                primary.getCandidate(), donor.getCandidate(), donorId, inputs);

        assertTrue(graft.isFound(), () -> graft.getDetail() + issues(graft));
        Variant combined = graft.getCandidate().getVariant();
        assertEquals(2, combined.getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(1, combined.getNodes().stream()
                .filter(node -> "heat_chamber".equals(node.getType()) && node.getInputFeatureId() != null).count());
        assertEquals(50.0, graft.getCandidate().getTotalLengthM(), 1e-8,
                "the donor is cut at its first crossing, not at the later crossing");
        assertTrue(combined.getSegments().stream().anyMatch(segment ->
                Math.abs(segment.getGeometry().getLength() - 25.0) < 1e-8
                        && segment.getFlowTph().doubleValue() == 5.0));
        assertEquals(1, primary.getCandidate().getVariant().getSegments().size(),
                "the accepted parent remains unchanged");
    }

    @Test
    void graftsAtStartOfLaterOverlapWithoutKeepingTheSharedDonorTail() throws Exception {
        TypedId firstId = TypedId.of("first"), donorId = TypedId.of("donor");
        TypedId firstNetwork = TypedId.of("first-network"), donorNetwork = TypedId.of("donor-network");
        TypedId firstRootId = TypedId.of("first-root"), donorRootId = TypedId.of("donor-root");
        Point firstRoot = point(30, 0), donorRoot = point(30, 10);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(-10, 0)), target(donorId, 3.0, point(5, 10)),
                indexed(properties(firstNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (30 0, 40 0)")),
                indexed(properties(donorNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (30 10, 40 10)")),
                indexed(new FeatureProperties(firstRootId, "heat_chamber"), firstRoot),
                indexed(new FeatureProperties(donorRootId, "heat_chamber"), donorRoot));
        Variant firstRoute = straightRoute("first-route", firstId, point(-10, 0), firstRootId,
                firstRoot, 2.0);
        Variant donorRoute = new Variant("donor-route", Arrays.asList(
                new Variant.Node("donor-target", 5, 10, "oks_connection_point", donorId),
                new Variant.Node("donor-attachment", 30, 10, "heat_chamber", donorRootId)),
                Collections.singletonList(segment("donor-pipe", "donor-target", "donor-attachment",
                        line("LINESTRING (5 10, 5 0, 10 0, 10 5, 30 10)"), 3.0)));
        RouteTreeOptimizer.OptimizationResult primary = optimizer.optimize("primary-overlap",
                Collections.singletonList(firstRoute),
                Collections.singletonList(attachment(firstNetwork, firstRootId, firstRoot, 1)), inputs);
        RouteTreeOptimizer.OptimizationResult donor = optimizer.optimize("donor-overlap",
                Collections.singletonList(donorRoute),
                Collections.singletonList(attachment(donorNetwork, donorRootId, donorRoot, 1)), inputs);
        assertTrue(primary.isFound(), () -> primary.getDetail() + issues(primary));
        assertTrue(donor.isFound(), () -> donor.getDetail() + issues(donor));

        RouteTreeOptimizer.OptimizationResult graft = optimizer.graftDonorPrefix("grafted-overlap",
                primary.getCandidate(), donor.getCandidate(), donorId, inputs);

        assertTrue(graft.isFound(), () -> graft.getDetail() + issues(graft));
        assertEquals(50.0, graft.getCandidate().getTotalLengthM(), 1e-8);
        assertEquals(1, primary.getCandidate().getVariant().getSegments().size());
        assertEquals(1, donor.getCandidate().getVariant().getSegments().size());
    }

    @Test
    void rejectsFirstDonorContactAtTrunkVertexInsteadOfSkippingToLaterCrossing() throws Exception {
        TypedId firstId = TypedId.of("first"), donorId = TypedId.of("donor");
        TypedId firstNetwork = TypedId.of("first-network"), donorNetwork = TypedId.of("donor-network");
        TypedId firstRootId = TypedId.of("first-root"), donorRootId = TypedId.of("donor-root");
        Point firstRoot = point(30, 0), donorRoot = point(30, 10);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(-10, 0)), target(donorId, 3.0, point(5, 10)),
                indexed(properties(firstNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (30 0, 40 0)")),
                indexed(properties(donorNetwork, "heat_network", "diameter", 500),
                        line("LINESTRING (30 10, 40 10)")),
                indexed(new FeatureProperties(firstRootId, "heat_chamber"), firstRoot),
                indexed(new FeatureProperties(donorRootId, "heat_chamber"), donorRoot));
        Variant firstRoute = new Variant("first-route", Arrays.asList(
                new Variant.Node("first-target", -10, 0, "oks_connection_point", firstId),
                new Variant.Node("first-attachment", 30, 0, "heat_chamber", firstRootId)),
                Collections.singletonList(segment("first-pipe", "first-target", "first-attachment",
                        line("LINESTRING (-10 0, 5 0, 30 0)"), 2.0)));
        Variant donorRoute = new Variant("donor-route", Arrays.asList(
                new Variant.Node("donor-target", 5, 10, "oks_connection_point", donorId),
                new Variant.Node("donor-attachment", 30, 10, "heat_chamber", donorRootId)),
                Collections.singletonList(segment("donor-pipe", "donor-target", "donor-attachment",
                        line("LINESTRING (5 10, 5 -5, 20 -5, 20 5, 30 10)"), 3.0)));
        RouteTreeOptimizer.OptimizationResult primary = optimizer.optimize("primary-vertex",
                Collections.singletonList(firstRoute),
                Collections.singletonList(attachment(firstNetwork, firstRootId, firstRoot, 1)), inputs);
        RouteTreeOptimizer.OptimizationResult donor = optimizer.optimize("donor-vertex",
                Collections.singletonList(donorRoute),
                Collections.singletonList(attachment(donorNetwork, donorRootId, donorRoot, 1)), inputs);
        assertTrue(primary.isFound(), () -> primary.getDetail() + issues(primary));
        assertTrue(donor.isFound(), () -> donor.getDetail() + issues(donor));

        RouteTreeOptimizer.OptimizationResult graft = optimizer.graftDonorPrefix("invalid-first-contact",
                primary.getCandidate(), donor.getCandidate(), donorId, inputs);

        assertTrue(!graft.isFound());
        assertTrue(graft.getDetail().contains("vertex") || graft.getDetail().contains("bend"),
                graft::getDetail);
        assertEquals(1, primary.getCandidate().getVariant().getSegments().size());
        assertEquals(1, primary.getCandidate().getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
    }

    @Test
    void longerFirstBranchCanMakeTheSameThreeTargetNetworkCheaper() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 1.0, point(-10, 0));
        FeatureIndex.IndexedFeature a = target(TypedId.of("A"), 0.5, point(10, 40));
        FeatureIndex.IndexedFeature b = target(TypedId.of("B"), 0.5, point(26, 27));
        FeatureIndex.IndexedFeature c = target(TypedId.of("C"), 0.5, point(35, 15));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, a, b, c,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        line("LINESTRING (50 0, 60 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(50, 0)));
        RouteTreeOptimizer.OptimizationResult base = optimizer.optimize("base",
                Collections.singletonList(straightRoute("seed-route", seed.getFeature().getId(),
                        point(-10, 0), chamberId, point(50, 0), 1.0)),
                Collections.singletonList(attachment(networkId, chamberId, point(50, 0), 1)), inputs);
        assertTrue(base.isFound(), () -> base.getDetail() + issues(base));

        RouteTreeOptimizer.OptimizationResult shortA = attachAt("short-A", base.getCandidate(), a,
                line("LINESTRING (10 40, 10 0)"), inputs);
        RouteTreeOptimizer.OptimizationResult longA = attachAt("long-A", base.getCandidate(), a,
                line("LINESTRING (10 40, 40 0)"), inputs);
        assertTrue(shortA.isFound(), () -> shortA.getDetail() + issues(shortA));
        assertTrue(longA.isFound(), () -> longA.getDetail() + issues(longA));

        RouteTreeOptimizer.OptimizationResult shortB = attachAt("short-B", shortA.getCandidate(), b,
                line("LINESTRING (26 27, 10 27)"), inputs);
        RouteTreeOptimizer.OptimizationResult shortC = shortB.isFound()
                ? attachAt("short-C", shortB.getCandidate(), c, line("LINESTRING (35 15, 35 0)"), inputs)
                : shortB;
        RouteTreeOptimizer.OptimizationResult longB = attachAt("long-B", longA.getCandidate(), b,
                line("LINESTRING (26 27, 22 24)"), inputs);
        RouteTreeOptimizer.OptimizationResult longC = longB.isFound()
                ? attachAt("long-C", longB.getCandidate(), c, line("LINESTRING (35 15, 31 12)"), inputs)
                : longB;
        assertTrue(shortC.isFound(), () -> shortC.getDetail() + issues(shortC));
        assertTrue(longC.isFound(), () -> longC.getDetail() + issues(longC));

        VariantCostCalculator costs = new VariantCostCalculator();
        java.math.BigDecimal shortFirstPrice = costs.calculate(shortA.getCandidate().getVariant(),
                shortA.getCandidate().getValidation(), inputs, Collections.emptyList(),
                Arrays.asList(b.getFeature().getId(), c.getFeature().getId()))
                .getSummary().getConstructionCost();
        java.math.BigDecimal longFirstPrice = costs.calculate(longA.getCandidate().getVariant(),
                longA.getCandidate().getValidation(), inputs, Collections.emptyList(),
                Arrays.asList(b.getFeature().getId(), c.getFeature().getId()))
                .getSummary().getConstructionCost();
        assertTrue(shortFirstPrice.compareTo(longFirstPrice) < 0);
        java.math.BigDecimal shortFullScore = costs.calculate(shortC.getCandidate().getVariant(),
                shortC.getCandidate().getValidation(), inputs, Collections.emptyList())
                .getSummary().getScore();
        java.math.BigDecimal longFullScore = costs.calculate(longC.getCandidate().getVariant(),
                longC.getCandidate().getValidation(), inputs, Collections.emptyList())
                .getSummary().getScore();
        assertTrue(longFullScore.compareTo(shortFullScore) < 0,
                () -> "long=" + longFullScore + ", short=" + shortFullScore);
        assertEquals(131.0, shortC.getCandidate().getTotalLengthM(), 1e-9);
        assertEquals(120.0, longC.getCandidate().getTotalLengthM(), 1e-9);
        assertEquals(shortC.getCandidate().getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType()))
                .map(Variant.Node::getInputFeatureId).collect(java.util.stream.Collectors.toSet()),
                longC.getCandidate().getVariant().getNodes().stream()
                        .filter(node -> "oks_connection_point".equals(node.getType()))
                        .map(Variant.Node::getInputFeatureId).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void independentConnectionCanBeatReuseOfAnExistingNewBranch() throws Exception {
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("chamber");
        FeatureIndex.IndexedFeature seed = target(TypedId.of("seed"), 1.0, point(-10, 0));
        FeatureIndex.IndexedFeature a = target(TypedId.of("A"), 0.5, point(10, 40));
        FeatureIndex.IndexedFeature b = target(TypedId.of("B"), 0.5, point(45, 20));
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(seed, a, b,
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        line("LINESTRING (50 0, 60 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(50, 0)));
        RouteTreeOptimizer.OptimizationResult base = optimizer.optimize("base",
                Collections.singletonList(straightRoute("seed-route", seed.getFeature().getId(),
                        point(-10, 0), chamberId, point(50, 0), 1.0)),
                Collections.singletonList(attachment(networkId, chamberId, point(50, 0), 1)), inputs);
        assertTrue(base.isFound(), () -> base.getDetail() + issues(base));
        RouteTreeOptimizer.OptimizationResult first = attachAt("A", base.getCandidate(), a,
                line("LINESTRING (10 40, 10 0)"), inputs);
        assertTrue(first.isFound(), () -> first.getDetail() + issues(first));
        RouteTreeOptimizer.OptimizationResult reuse = attachAt("B-reuse", first.getCandidate(), b,
                line("LINESTRING (45 20, 10 15)"), inputs);
        RouteTreeOptimizer.OptimizationResult independent = attachAt("B-independent", first.getCandidate(), b,
                line("LINESTRING (45 20, 45 0)"), inputs);
        assertTrue(reuse.isFound(), () -> reuse.getDetail() + issues(reuse));
        assertTrue(independent.isFound(), () -> independent.getDetail() + issues(independent));

        VariantCostCalculator costs = new VariantCostCalculator();
        java.math.BigDecimal reuseScore = costs.calculate(reuse.getCandidate().getVariant(),
                reuse.getCandidate().getValidation(), inputs, Collections.emptyList()).getSummary().getScore();
        java.math.BigDecimal independentScore = costs.calculate(independent.getCandidate().getVariant(),
                independent.getCandidate().getValidation(), inputs, Collections.emptyList()).getSummary().getScore();
        assertTrue(independentScore.compareTo(reuseScore) < 0,
                () -> "independent=" + independentScore + ", reuse=" + reuseScore);
        assertTrue(independent.getCandidate().getTotalLengthM() < reuse.getCandidate().getTotalLengthM());
    }

    @Test
    void mergesCommonTrunkAndRecalculatesItsCombinedFlowAndDiameter() throws Exception {
        TypedId firstId = TypedId.of(new BigDecimal("1"));
        TypedId secondId = TypedId.of("1");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 3)), target(secondId, 3.0, point(0, -3)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)));
        AttachmentCandidate attachment = attachment(networkId, chamberId, point(10, 0), 1);
        Variant first = route("first", firstId, point(0, 3), point(5, 0), point(10, 0), 2.0);
        Variant second = route("second", secondId, point(0, -3), point(5, 0), point(10, 0), 3.0);

        RouteTreeOptimizer.OptimizationResult result = optimizer.optimize("combined",
                Arrays.asList(first, second), Arrays.asList(attachment, attachment), inputs);

        assertTrue(result.isFound(), () -> result.getDetail() + issues(result));
        Variant combined = result.getCandidate().getVariant();
        Variant.Segment trunk = combined.getSegments().stream()
                .filter(segment -> segment.getGeometry().getLength() == 5.0).findFirst().orElseThrow();
        assertEquals(2, combined.getNodes().stream().filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(5.0, trunk.getFlowTph().doubleValue(), 1e-9);
        assertTrue(trunk.getDiameterMm() >= 50);
        assertEquals(3, combined.getSegments().size(), "the common trunk must be stored once");
        assertTrue(result.getCandidate().getValidation().isAcceptable());
    }

    @Test
    void createsAChamberWhereTwoCandidatePathsConvergeOntoAnIdenticalTrunk() throws Exception {
        TypedId firstId = TypedId.of("first");
        TypedId secondId = TypedId.of("second");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 3)), target(secondId, 3.0, point(0, -3)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)));
        AttachmentCandidate attachment = attachment(networkId, chamberId, point(10, 0), 1);

        RouteTreeOptimizer.OptimizationResult result = optimizer.optimize("generated-shared-trunk",
                Arrays.asList(sharedSuffixRoute("first", firstId, point(0, 3), point(5, 0), point(10, 0), 2.0),
                        sharedSuffixRoute("second", secondId, point(0, -3), point(5, 0), point(10, 0), 3.0)),
                Arrays.asList(attachment, attachment), inputs);

        assertTrue(result.isFound(), () -> result.getDetail() + issues(result));
        Variant combined = result.getCandidate().getVariant();
        assertEquals(1, combined.getNodes().stream().filter(node -> "heat_chamber".equals(node.getType())
                && node.getInputFeatureId() == null).count());
        assertEquals(3, combined.getSegments().size());
        assertEquals(5.0, combined.getSegments().stream().filter(segment -> segment.getGeometry().getLength() == 5.0)
                .findFirst().orElseThrow().getFlowTph().doubleValue(), 1e-9);
    }

    @Test
    void rejectsCombinationWhenBranchWouldExceedExistingChamberCapacity() throws Exception {
        TypedId firstId = TypedId.of("one");
        TypedId secondId = TypedId.of("two");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 3)), target(secondId, 3.0, point(0, -3)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), point(10, 0)));
        AttachmentCandidate fullChamber = attachment(networkId, chamberId, point(10, 0), 3);

        RouteTreeOptimizer.OptimizationResult result = optimizer.optimize("overfull",
                Arrays.asList(route("first", firstId, point(0, 3), point(5, 2), point(10, 0), 2.0),
                        route("second", secondId, point(0, -3), point(5, -2), point(10, 0), 3.0)),
                Arrays.asList(fullChamber, fullChamber), inputs);

        assertTrue(!result.isFound());
        assertTrue(result.getDetail().contains("four-connection chamber limit"));
    }

    @Test
    void rejectsSharedBranchThatWasNotGivenAHeatChamber() throws Exception {
        TypedId firstId = TypedId.of("one");
        TypedId secondId = TypedId.of("two");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        Point root = point(10, 0);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 3)), target(secondId, 3.0, point(0, -3)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), root));
        AttachmentCandidate attachment = attachment(networkId, chamberId, root, 1);

        RouteTreeOptimizer.OptimizationResult result = optimizer.optimize("uncamerad-branch",
                Arrays.asList(route("first", firstId, point(0, 3), point(5, 0), root, 2.0, "technical_node"),
                        route("second", secondId, point(0, -3), point(5, 0), root, 3.0, "technical_node")),
                Arrays.asList(attachment, attachment), inputs);

        assertTrue(!result.isFound());
        assertTrue(result.getValidation().getIssues().stream()
                .anyMatch(issue -> "BRANCH_WITHOUT_CHAMBER".equals(issue.getCode())));
    }

    @Test
    void rejectsDirectedCyclesInCombinedNetwork() throws Exception {
        TypedId targetId = TypedId.of("target");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        Point target = point(0, 0), branch = point(5, 0), root = point(10, 0);
        Point loopA = point(30, 0), loopB = point(30, 5);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(target(targetId, 2.0, target),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), root));
        Variant cyclic = new Variant("cycle", Arrays.asList(
                new Variant.Node("target-node", 0, 0, "oks_connection_point", targetId),
                new Variant.Node("branch-node", 5, 0, "technical_node"),
                new Variant.Node("root-node", 10, 0, "heat_chamber", chamberId),
                new Variant.Node("loop-a", 30, 0, "technical_node"),
                new Variant.Node("loop-b", 30, 5, "technical_node")), Arrays.asList(
                        segment("cycle:one", "target-node", "branch-node", line(target, branch), 2.0),
                        segment("cycle:two", "branch-node", "root-node", line(branch, root), 2.0),
                        segment("cycle:loop-a", "loop-a", "loop-b", line(loopA, loopB), 2.0),
                        segment("cycle:loop-b", "loop-b", "loop-a", line(loopB, loopA), 2.0)));

        RouteTreeOptimizer.OptimizationResult result = optimizer.optimize("cycle-merge",
                Collections.singletonList(cyclic), Collections.singletonList(attachment(networkId, chamberId, root, 1)), inputs);

        assertTrue(!result.isFound());
        assertTrue(result.getDetail().contains("cycle"));
    }

    @Test
    void rejectsCrossingRoutesThatDoNotMeetAtANetworkNode() throws Exception {
        TypedId firstId = TypedId.of("first");
        TypedId secondId = TypedId.of("second");
        TypedId firstNetwork = TypedId.of("network-a");
        TypedId secondNetwork = TypedId.of("network-b");
        TypedId firstChamber = TypedId.of("chamber-a");
        TypedId secondChamber = TypedId.of("chamber-b");
        Point firstTarget = point(0, 0), firstRoot = point(10, 10);
        Point secondTarget = point(10, 0), secondRoot = point(0, 10);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, firstTarget), target(secondId, 3.0, secondTarget),
                indexed(properties(firstNetwork, "heat_network", "diameter", 500), line("LINESTRING (10 10, 20 10)")),
                indexed(properties(secondNetwork, "heat_network", "diameter", 500), line("LINESTRING (0 10, -10 10)")),
                indexed(new FeatureProperties(firstChamber, "heat_chamber"), firstRoot),
                indexed(new FeatureProperties(secondChamber, "heat_chamber"), secondRoot));

        RouteTreeOptimizer.OptimizationResult result = optimizer.optimize("crossing",
                Arrays.asList(straightRoute("route-a", firstId, firstTarget, firstChamber, firstRoot, 2.0),
                        straightRoute("route-b", secondId, secondTarget, secondChamber, secondRoot, 3.0)),
                Arrays.asList(attachment(firstNetwork, firstChamber, firstRoot, 1),
                        attachment(secondNetwork, secondChamber, secondRoot, 1)), inputs);

        assertTrue(!result.isFound());
        assertTrue(result.getValidation().getIssues().stream()
                .anyMatch(issue -> issue.getCode().contains("INTERSECT") || issue.getCode().contains("CLEARANCE")),
                () -> result.getValidation().getIssues().stream()
                        .map(issue -> issue.getCode() + ":" + issue.getMessage()).collect(Collectors.joining(", ")));
    }

    @Test
    void attachesDirectBranchToNewTrunkAndRecalculatesSharedFlow() throws Exception {
        TypedId firstId = TypedId.of("first");
        TypedId secondId = TypedId.of("second");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        Point root = point(10, 0);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 0)), target(secondId, 3.0, point(5, 4)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), root));
        RouteTreeOptimizer.OptimizationResult base = optimizer.optimize("base",
                Collections.singletonList(straightRoute("first-route", firstId, point(0, 0), chamberId, root, 2.0)),
                Collections.singletonList(attachment(networkId, chamberId, root, 1)), inputs);
        assertTrue(base.isFound(), () -> base.getDetail() + issues(base));

        RouteTreeOptimizer.OptimizationResult attached = optimizer.attachDirectBranch("branched", base.getCandidate(),
                inputs.get(1), base.getCandidate().getVariant().getSegments().get(0).getId(), inputs);

        assertTrue(attached.isFound(), () -> attached.getDetail() + issues(attached));
        Variant variant = attached.getCandidate().getVariant();
        assertEquals(3, variant.getSegments().size());
        assertEquals(1, variant.getNodes().stream().filter(node -> "heat_chamber".equals(node.getType())
                && node.getInputFeatureId() == null).count());
        Variant.Segment common = variant.getSegments().stream().filter(segment ->
                segment.getGeometry().getCoordinateN(0).equals2D(new Coordinate(5, 0))
                && segment.getGeometry().getCoordinateN(1).equals2D(new Coordinate(10, 0)))
                .findFirst().orElseThrow();
        assertEquals(5.0, common.getFlowTph().doubleValue(), 1e-9);
        assertEquals(65, common.getDiameterMm().intValue());
        assertEquals(1, base.getCandidate().getVariant().getSegments().size(), "accepted tree stays unchanged");
    }

    @Test
    void attachesToFreeTreeChamberAndKeepsValidatedFlowAndCapacity() throws Exception {
        TypedId firstId = TypedId.of("first"), secondId = TypedId.of("second");
        TypedId thirdId = TypedId.of("third"), networkId = TypedId.of("network");
        TypedId rootId = TypedId.of("root");
        Point root = point(10, 0);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 0)), target(secondId, 3.0, point(5, 4)),
                target(thirdId, 4.0, point(5, -4)),
                indexed(properties(networkId, "heat_network", "diameter", 500),
                        line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(rootId, "heat_chamber"), root));
        RouteTreeOptimizer.OptimizationResult seed = optimizer.optimize("seed",
                Collections.singletonList(straightRoute("first-route", firstId, point(0, 0),
                        rootId, root, 2.0)),
                Collections.singletonList(attachment(networkId, rootId, root, 1)), inputs);
        assertTrue(seed.isFound(), () -> seed.getDetail() + issues(seed));
        RouteTreeOptimizer.OptimizationResult branched = optimizer.attachDirectBranch("branched",
                seed.getCandidate(), inputs.get(1), seed.getCandidate().getVariant().getSegments().get(0).getId(),
                inputs);
        assertTrue(branched.isFound(), () -> branched.getDetail() + issues(branched));
        Variant.Node freeChamber = branched.getCandidate().getVariant().getNodes().stream()
                .filter(node -> "heat_chamber".equals(node.getType()) && node.getInputFeatureId() == null)
                .findFirst().orElseThrow();
        TreeRouteRequest request = TreeRouteRequest.toChamber("to-free-chamber", inputs.get(2),
                branched.getCandidate(), freeChamber.getId(), inputs, new Envelope(-5, 35, -8, 8), 300);
        SingleTargetRouter.BranchRoute route = new SingleTargetRouter().routeToTree(request);
        assertTrue(route.isFound(), () -> route.getStatus() + ": " + route.getDetail());

        RouteTreeOptimizer.OptimizationResult grown = optimizer.attachRoutedBranchAtChamber("grown",
                branched.getCandidate(), inputs.get(2), freeChamber.getId(), route.getGeometry(),
                route.getDiameterMm(), inputs);

        assertTrue(grown.isFound(), () -> grown.getDetail() + issues(grown));
        assertEquals(3, grown.getCandidate().getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(4, grown.getCandidate().getVariant().getSegments().stream()
                .filter(segment -> freeChamber.getId().equals(segment.getStartNodeId())
                        || freeChamber.getId().equals(segment.getEndNodeId())).count());
        assertEquals(9.0, grown.getCandidate().getVariant().getSegments().stream()
                .filter(segment -> rootId.equals(grown.getCandidate().getVariant().getNodes().stream()
                        .filter(node -> node.getId().equals(segment.getFlowToNodeId()))
                        .findFirst().orElseThrow().getInputFeatureId()))
                .findFirst().orElseThrow().getFlowTph().doubleValue(), 1e-9);
        assertEquals(3, branched.getCandidate().getVariant().getSegments().size(),
                "the accepted input tree is unchanged");
        assertTrue(grown.getCandidate().getValidation().isAcceptable());
    }

    @Test
    void attachesInsideStraightLegOfAStoredPolyline() throws Exception {
        TypedId firstId = TypedId.of("first");
        TypedId secondId = TypedId.of("second");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        Point root = point(10, 1);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 0)), target(secondId, 3.0, point(5, 4)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 1, 30 1)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), root));
        Variant route = new Variant("polyline-route", Arrays.asList(
                new Variant.Node("first-target", 0, 0, "oks_connection_point", firstId),
                new Variant.Node("source-root", 10, 1, "heat_chamber", chamberId)),
                Collections.singletonList(segment("polyline-pipe", "first-target", "source-root",
                        line("LINESTRING (0 0, 2 0, 10 1)"), 2.0)));
        RouteTreeOptimizer.OptimizationResult base = optimizer.optimize("polyline-base",
                Collections.singletonList(route),
                Collections.singletonList(attachment(networkId, chamberId, root, 1)), inputs);
        assertTrue(base.isFound(), () -> base.getDetail() + issues(base));

        RouteTreeOptimizer.OptimizationResult attached = optimizer.attachDirectBranch("polyline-branched",
                base.getCandidate(), inputs.get(1), base.getCandidate().getVariant().getSegments().get(0).getId(), inputs);

        assertTrue(attached.isFound(), () -> attached.getDetail() + issues(attached));
        assertEquals(3, attached.getCandidate().getVariant().getSegments().size());
        assertTrue(attached.getCandidate().getVariant().getSegments().stream()
                .anyMatch(segment -> segment.getGeometry().getNumPoints() == 3),
                "the consumer-side part retains the bend before the projected chamber");
    }

    @Test
    void rejectsBlockedDirectBranchWithoutMutatingAcceptedTree() throws Exception {
        TypedId firstId = TypedId.of("first");
        TypedId secondId = TypedId.of("second");
        TypedId networkId = TypedId.of("network");
        TypedId chamberId = TypedId.of("source-chamber");
        Point root = point(10, 0);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 0)), target(secondId, 3.0, point(5, 4)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), root),
                indexed(properties(TypedId.of("park"), "restriction", "restriction_type", "park"),
                        new WKTReader(FACTORY).read("POLYGON ((4.5 2, 5.5 2, 5.5 3, 4.5 3, 4.5 2))")));
        RouteTreeOptimizer.OptimizationResult base = optimizer.optimize("base",
                Collections.singletonList(straightRoute("first-route", firstId, point(0, 0), chamberId, root, 2.0)),
                Collections.singletonList(attachment(networkId, chamberId, root, 1)), inputs);
        assertTrue(base.isFound(), () -> base.getDetail() + issues(base));

        RouteTreeOptimizer.OptimizationResult attached = optimizer.attachDirectBranch("blocked", base.getCandidate(),
                inputs.get(1), base.getCandidate().getVariant().getSegments().get(0).getId(), inputs);

        assertTrue(!attached.isFound());
        assertTrue(attached.getValidation() != null && !attached.getValidation().isAcceptable());
        assertEquals(1, base.getCandidate().getVariant().getSegments().size());
        assertTrue(base.getCandidate().getValidation().isAcceptable());
    }

    @Test
    void navigatorSearchDetoursAroundParkAndGrowsValidatedTree() throws Exception {
        TypedId firstId = TypedId.of("first"), secondId = TypedId.of("second");
        TypedId networkId = TypedId.of("network"), chamberId = TypedId.of("source-chamber");
        Point root = point(10, 0);
        List<FeatureIndex.IndexedFeature> inputs = Arrays.asList(
                target(firstId, 2.0, point(0, 0)), target(secondId, 3.0, point(5, 5)),
                indexed(properties(networkId, "heat_network", "diameter", 500), line("LINESTRING (10 0, 30 0)")),
                indexed(new FeatureProperties(chamberId, "heat_chamber"), root),
                indexed(properties(TypedId.of("park"), "restriction", "restriction_type", "park"),
                        new WKTReader(FACTORY).read("POLYGON ((4.5 2, 5.5 2, 5.5 3, 4.5 3, 4.5 2))")));
        RouteTreeOptimizer.OptimizationResult seed = optimizer.optimize("seed",
                Collections.singletonList(straightRoute("seed-route", firstId, point(0, 0), chamberId, root, 2.0)),
                Collections.singletonList(attachment(networkId, chamberId, root, 1)), inputs);
        assertTrue(seed.isFound(), () -> seed.getDetail() + issues(seed));
        Variant.Segment trunk = seed.getCandidate().getVariant().getSegments().get(0);
        Point goal = point(4, 0);
        Point corridorPoint = point(2, 2);
        TreeRouteRequest corridorRequest = new TreeRouteRequest("branch-via", inputs.get(1),
                seed.getCandidate(), trunk.getId(), goal, inputs,
                new Envelope(-5, 35, -12, 12), 300, corridorPoint);
        SingleTargetRouter.BranchRoute corridorBranch = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_TREE_NAVIGATOR).routeToTree(corridorRequest);
        assertTrue(corridorBranch.isFound(), () -> corridorBranch.getStatus() + ": "
                + corridorBranch.getDetail());
        assertTrue(Arrays.stream(corridorBranch.getGeometry().getCoordinates())
                .anyMatch(vertex -> vertex.distance(corridorPoint.getCoordinate()) < 1e-9));
        RouteTreeOptimizer.OptimizationResult corridorTree = optimizer.attachRoutedBranch("grown-via",
                seed.getCandidate(), inputs.get(1), trunk.getId(), corridorBranch.getGeometry(),
                corridorBranch.getDiameterMm(), inputs);
        assertTrue(corridorTree.isFound(), () -> corridorTree.getDetail() + issues(corridorTree));
        TreeRouteRequest branchRequest = new TreeRouteRequest("branch", inputs.get(1),
                seed.getCandidate(), trunk.getId(), goal, inputs,
                new Envelope(-5, 35, -12, 12), 300);
        LengthIndexedLine indexedTrunk = new LengthIndexedLine(trunk.getGeometry());
        LineString sourceward = (LineString) indexedTrunk.extractLine(
                indexedTrunk.project(goal.getCoordinate()), trunk.getGeometry().getLength());
        assertEquals(sourceward.getCoordinateN(1).x - goal.getX(),
                branchRequest.getGoalForward().x, 1e-9);
        assertEquals(sourceward.getCoordinateN(1).y - goal.getY(),
                branchRequest.getGoalForward().y, 1e-9);
        SingleTargetRouter.BranchRoute branch = new SingleTargetRouter(
                SingleTargetRouter.SearchMode.CONSERVATIVE_FOREST).routeToTree(
                        branchRequest);
        assertTrue(branch.isFound(), () -> branch.getStatus() + ": " + branch.getDetail());
        assertTrue(branch.getGeometry().getLength() > Math.hypot(1, 5),
                "The direct attachment has an invalid terminal angle, so the route must detour");
        Coordinate[] branchCoordinates = branch.getGeometry().getCoordinates();
        assertTrue(branchCoordinates[branchCoordinates.length - 2].x <= goal.getX() + 1e-9,
                "The final approach must point toward the sourceward side of the trunk");
        RouteTreeOptimizer.OptimizationResult grown = optimizer.attachRoutedBranch("grown",
                seed.getCandidate(), inputs.get(1), trunk.getId(), branch.getGeometry(),
                branch.getDiameterMm(), inputs);
        assertTrue(grown.isFound(), () -> Arrays.toString(branch.getGeometry().getCoordinates()) + " "
                + grown.getDetail() + (grown.getValidation() == null ? ""
                : grown.getValidation().getIssues().stream()
                        .map(issue -> issue.getCode() + ":" + issue.getMessage())
                        .collect(Collectors.joining(", "))));
        assertEquals(2, grown.getCandidate().getVariant().getNodes().stream()
                .filter(node -> "oks_connection_point".equals(node.getType())).count());
        assertEquals(5.0, grown.getCandidate().getVariant().getSegments().stream()
                .filter(segment -> segment.getFlowTph().doubleValue() == 5.0).findFirst().orElseThrow()
                .getFlowTph().doubleValue(), 1e-9);
        assertEquals(1, seed.getCandidate().getVariant().getSegments().size());
    }

    private RouteTreeOptimizer.OptimizationResult attachAt(String id,
            RouteTreeOptimizer.TreeCandidate parent, FeatureIndex.IndexedFeature target,
            LineString branch, List<FeatureIndex.IndexedFeature> inputs) {
        Point goal = branch.getEndPoint();
        Variant.Segment trunk = parent.getVariant().getSegments().stream()
                .filter(segment -> "base".equals(segment.getLayingMethod())
                        && segment.getGeometry().covers(goal)
                        && segment.getGeometry().getStartPoint().distance(goal) > 0.25
                        && segment.getGeometry().getEndPoint().distance(goal) > 0.25)
                .findFirst().orElseThrow(() -> new AssertionError("No eligible trunk for " + id));
        return optimizer.attachRoutedBranch(id, parent, target, trunk.getId(), branch, 50, inputs);
    }

    private static Variant route(String id, TypedId targetId, Point target, Point branch, Point root, double flow)
            throws Exception {
        return route(id, targetId, target, branch, root, flow, "heat_chamber");
    }

    private static Variant route(String id, TypedId targetId, Point target, Point branch, Point root, double flow,
            String branchType) throws Exception {
        String targetNode = id + ":target";
        String branchNode = id + ":branch";
        String rootNode = id + ":attachment";
        Variant.Node targetValue = new Variant.Node(targetNode, target.getX(), target.getY(),
                "oks_connection_point", targetId);
        Variant.Node branchValue = new Variant.Node(branchNode, branch.getX(), branch.getY(), branchType);
        Variant.Node rootValue = new Variant.Node(rootNode, root.getX(), root.getY(), "heat_chamber",
                TypedId.of("source-chamber"));
        return new Variant(id, Arrays.asList(targetValue, branchValue, rootValue), Arrays.asList(
                segment(id + ":segment:branch", targetNode, branchNode, line(target, branch), flow),
                segment(id + ":segment:trunk", branchNode, rootNode, line(branch, root), flow)));
    }

    private static Variant.Segment segment(String id, String start, String end, LineString geometry, double flow) {
        return new Variant.Segment(id, start, end, geometry, start, end, BigDecimal.valueOf(flow), 50,
                "base", Collections.emptySet(), BigDecimal.ONE);
    }

    private static Variant straightRoute(String id, TypedId targetId, Point target, TypedId chamberId,
            Point root, double flow) {
        String targetNode = id + ":target-node";
        String rootNode = id + ":root-node";
        return new Variant(id, Arrays.asList(
                new Variant.Node(targetNode, target.getX(), target.getY(), "oks_connection_point", targetId),
                new Variant.Node(rootNode, root.getX(), root.getY(), "heat_chamber", chamberId)),
                Collections.singletonList(segment(id + ":pipe", targetNode, rootNode, line(target, root), flow)));
    }

    private static Variant sharedSuffixRoute(String id, TypedId targetId, Point target, Point branch,
            Point root, double flow) {
        String targetNode = id + ":target";
        String rootNode = id + ":attachment";
        Variant.Node targetValue = new Variant.Node(targetNode, target.getX(), target.getY(),
                "oks_connection_point", targetId);
        Variant.Node rootValue = new Variant.Node(rootNode, root.getX(), root.getY(), "heat_chamber",
                TypedId.of("source-chamber"));
        LineString geometry = FACTORY.createLineString(new Coordinate[] {
                target.getCoordinate(), branch.getCoordinate(), root.getCoordinate()});
        return new Variant(id, Arrays.asList(targetValue, rootValue),
                Collections.singletonList(segment(id + ":pipe", targetNode, rootNode, geometry, flow)));
    }

    private static AttachmentCandidate attachment(TypedId network, TypedId chamber, Point point, int existingCount) {
        return new AttachmentCandidate(network, 0, point, point,
                AttachmentCandidate.EndpointKind.EXISTING_CHAMBER, chamber,
                Collections.singletonList(network), existingCount, 500);
    }

    private static FeatureIndex.IndexedFeature target(TypedId id, double flow, Point point) {
        return indexed(properties(id, "oks_connection_point", "flow_tph", flow), point);
    }

    private static FeatureProperties properties(TypedId id, String type, String name, Object value) {
        FeatureProperties properties = new FeatureProperties(id, type);
        properties.putExtra(name, MAPPER.valueToTree(value));
        return properties;
    }

    private static FeatureIndex.IndexedFeature indexed(FeatureProperties properties, Geometry geometry) {
        return new FeatureIndex.IndexedFeature(new InputFeature(properties, NullNode.getInstance()), geometry);
    }

    private static Point point(double x, double y) { return FACTORY.createPoint(new Coordinate(x, y)); }
    private static LineString line(Point first, Point last) {
        return FACTORY.createLineString(new Coordinate[] {first.getCoordinate(), last.getCoordinate()});
    }
    private static LineString line(String wkt) throws Exception { return (LineString) new WKTReader(FACTORY).read(wkt); }
    private static String issues(RouteTreeOptimizer.OptimizationResult result) {
        return result.getValidation() == null ? "" : result.getValidation().getIssues().toString();
    }
}
