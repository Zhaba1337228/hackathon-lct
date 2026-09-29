package ru.lct.heatnetwork.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import ru.lct.heatnetwork.domain.TypedId;

class DemandGroupPlannerTest {
    private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 32637);
    private static final DemandGroupPlanner PLANNER = new DemandGroupPlanner();

    @Test
    void groupsSixTargetsBeforeRoutingWithTypedIdsOverlapsMultipleRootsAndRotationInvariance() {
        List<DemandGroupPlanner.Demand> targets = Arrays.asList(
                demand(TypedId.of(new BigDecimal("1")), 0, 0, 1),
                demand(TypedId.of("1"), 10, 0, 2),
                demand(TypedId.of("3"), 20, 0, 1),
                demand(TypedId.of("4"), 30, 0, 2),
                demand(TypedId.of("5"), 40, 0, 1),
                demand(TypedId.of("6"), 50, 0, 2));
        List<DemandGroupPlanner.RootOption> roots = Arrays.asList(
                root(TypedId.of(new BigDecimal("9")), -10, 0),
                root(TypedId.of("9"), 60, 0));
        DemandGroupPlanner.Limits limits = limits(6, 2, 2, 3, 10, 30, 100, 2, 10);

        DemandGroupPlanner.Plan original = PLANNER.plan(targets, roots, clearProbe(), limits);
        DemandGroupPlanner.Plan rotated = PLANNER.plan(transformTargets(targets),
                transformRoots(roots), clearProbe(), limits);

        assertEquals(groupMembers(original), groupMembers(rotated));
        assertEquals(rootMembership(original), rootMembership(rotated));
        assertTrue(original.getGroups().size() >= 2);
        assertTrue(groupsOverlap(original));
        assertTrue(original.getGroups().stream().allMatch(group -> group.getPossibleRootIds().size() == 2));
        assertTrue(original.getGroups().stream().allMatch(group ->
                group.getLandRightsStatus()
                        == DemandGroupPlanner.LandRightsStatus.NOT_VERIFIED_REQUIRES_AGREEMENT));
        assertEquals(6, original.getStandaloneTargetIds().size());
        assertTrue(original.getStandaloneTargetIds().contains(TypedId.of(new BigDecimal("1"))));
        assertTrue(original.getStandaloneTargetIds().contains(TypedId.of("1")));
        assertFalse(original.isPairBudgetExhausted());
        assertFalse(original.isRootBudgetExhausted());
        assertFalse(original.isGroupLimitReached());
    }

    @Test
    void blockedClosePairIsExplainedWhileOtherOverlappingGroupsRemain() {
        List<DemandGroupPlanner.Demand> targets = Arrays.asList(
                demand("a", 0, 0, 1), demand("b", 20, 0, 1),
                demand("c", 0, 10, 1), demand("d", 20, 10, 1),
                demand("e", 30, 10, 1), demand("f", -10, 0, 1));
        Geometry obstacle = FACTORY.createPolygon(new Coordinate[] {
                new Coordinate(500009, 5999995), new Coordinate(500011, 5999995),
                new Coordinate(500011, 6000005), new Coordinate(500009, 6000005),
                new Coordinate(500009, 5999995)});
        List<DemandGroupPlanner.RootOption> roots = Arrays.asList(
                root(TypedId.of("west"), -20, 10), root(TypedId.of("east"), 40, 10));
        DemandGroupPlanner.Limits limits = limits(6, 2, 3, 4, 12, 30, 100, 2, 10);
        DemandGroupPlanner.Plan plan = PLANNER.plan(targets, roots, obstacleProbe(obstacle), limits);
        DemandGroupPlanner.Plan moved = PLANNER.plan(transformTargets(targets), transformRoots(roots),
                obstacleProbe(rotatedObstacle(obstacle)), limits);

        assertEquals(groupMembers(plan), groupMembers(moved));
        assertEquals(rootMembership(plan), rootMembership(moved));
        assertTrue(plan.getPairEvidence().stream().anyMatch(pair ->
                pair.getFirstId().equals(TypedId.of("a"))
                        && pair.getSecondId().equals(TypedId.of("b"))
                        && pair.getPassage().getStatus() == DemandGroupPlanner.PassageStatus.BLOCKED
                        && pair.getPassage().getReason().equals("KNOWN_OBSTACLE")));
        assertTrue(plan.getGroups().stream().anyMatch(group -> group.getTargetIds().contains(TypedId.of("a"))));
        assertTrue(plan.getGroups().stream().anyMatch(group -> group.getTargetIds().contains(TypedId.of("b"))));
        assertTrue(groupsOverlap(plan), "a blocked pair must not erase alternative neighboring groups");
        assertEquals(6, plan.getStandaloneTargetIds().size());
    }

    @Test
    void loadAndProbeBudgetsLimitWorkWithoutDiscardingIndividualTargets() {
        List<DemandGroupPlanner.Demand> targets = Arrays.asList(
                demand("a", 0, 0, 4), demand("b", 10, 0, 4),
                demand("c", 20, 0, 4), demand("d", 30, 0, 4));
        DemandGroupPlanner.Plan loadLimited = PLANNER.plan(targets,
                Collections.singletonList(root(TypedId.of("root"), -10, 0)), clearProbe(),
                limits(4, 1, 2, 3, 8, 20, 20, 1, 8));

        assertTrue(loadLimited.getGroups().stream().allMatch(group ->
                group.getTotalFlowTph().compareTo(new BigDecimal("8")) <= 0));
        assertTrue(loadLimited.getGroups().stream().anyMatch(group ->
                !group.getExcludedByFlowLimitIds().isEmpty()));
        assertEquals(4, loadLimited.getStandaloneTargetIds().size());

        DemandGroupPlanner.Plan truncated = PLANNER.plan(targets,
                Collections.singletonList(root(TypedId.of("root"), -10, 0)), clearProbe(),
                limits(4, 1, 2, 3, 1, 1, 0, 1, 100));
        assertEquals(1, truncated.getPairProbeCount());
        assertEquals(0, truncated.getRootProbeCount());
        assertTrue(truncated.isPairBudgetExhausted());
        assertTrue(truncated.isRootBudgetExhausted());
        assertEquals(4, truncated.getStandaloneTargetIds().size());
        assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(targets,
                Collections.emptyList(), clearProbe(), limits(3, 0, 2, 3, 4, 5, 0, 0, 100)));
    }

    @Test
    void rejectsUnprojectedPointsAndDoesNotMergeNumericAndTextualIds() {
        Point geographic = new GeometryFactory(new PrecisionModel(), 4326)
                .createPoint(new Coordinate(37, 55));
        assertThrows(IllegalArgumentException.class,
                () -> new DemandGroupPlanner.Demand(TypedId.of("x"), geographic, BigDecimal.ONE));
        List<DemandGroupPlanner.Demand> duplicate = Arrays.asList(
                demand(TypedId.of(new BigDecimal("1")), 0, 0, 1),
                demand(TypedId.of(new BigDecimal("1.0")), 10, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> PLANNER.plan(duplicate,
                Collections.emptyList(), clearProbe(), limits(2, 0, 1, 2, 1, 1, 0, 0, 10)));
        assertTrue(PLANNER.plan(Collections.emptyList(), Collections.emptyList(), clearProbe(),
                limits(2, 0, 1, 2, 1, 1, 0, 0, 10)).getGroups().isEmpty());
    }

    private static DemandGroupPlanner.Demand demand(String id, double x, double y, int flow) {
        return demand(TypedId.of(id), x, y, flow);
    }

    private static DemandGroupPlanner.Demand demand(TypedId id, double x, double y, int flow) {
        return new DemandGroupPlanner.Demand(id, point(x, y), BigDecimal.valueOf(flow));
    }

    private static DemandGroupPlanner.RootOption root(TypedId id, double x, double y) {
        return new DemandGroupPlanner.RootOption(id, TypedId.of("existing-network"), point(x, y));
    }

    private static Point point(double x, double y) {
        return FACTORY.createPoint(new Coordinate(500000 + x, 6000000 + y));
    }

    private static Point rotated(Point point) {
        double x = point.getX() - 500000;
        double y = point.getY() - 6000000;
        return point(100 - y, -200 + x);
    }

    private static List<DemandGroupPlanner.Demand> transformTargets(
            List<DemandGroupPlanner.Demand> original) {
        List<DemandGroupPlanner.Demand> transformed = new ArrayList<>();
        for (DemandGroupPlanner.Demand demand : original) {
            transformed.add(new DemandGroupPlanner.Demand(demand.getId(),
                    rotated(demand.getProjectedExit()), demand.getFlowTph()));
        }
        Collections.reverse(transformed);
        return transformed;
    }

    private static List<DemandGroupPlanner.RootOption> transformRoots(
            List<DemandGroupPlanner.RootOption> original) {
        List<DemandGroupPlanner.RootOption> transformed = new ArrayList<>();
        for (DemandGroupPlanner.RootOption root : original) {
            transformed.add(new DemandGroupPlanner.RootOption(root.getId(), root.getNetworkId(),
                    rotated(root.getProjectedEndpoint())));
        }
        Collections.reverse(transformed);
        return transformed;
    }

    private static DemandGroupPlanner.PassageProbe clearProbe() {
        return new DemandGroupPlanner.PassageProbe() {
            @Override
            public DemandGroupPlanner.Passage betweenExits(DemandGroupPlanner.Demand first,
                    DemandGroupPlanner.Demand second) {
                return DemandGroupPlanner.Passage.possible(
                        first.getProjectedExit().distance(second.getProjectedExit()));
            }

            @Override
            public DemandGroupPlanner.Passage toRoot(DemandGroupPlanner.Demand target,
                    DemandGroupPlanner.RootOption root, BigDecimal groupFlowTph) {
                return DemandGroupPlanner.Passage.possible(
                        target.getProjectedExit().distance(root.getProjectedEndpoint()));
            }
        };
    }

    private static DemandGroupPlanner.PassageProbe obstacleProbe(Geometry obstacle) {
        return new DemandGroupPlanner.PassageProbe() {
            @Override
            public DemandGroupPlanner.Passage betweenExits(DemandGroupPlanner.Demand first,
                    DemandGroupPlanner.Demand second) {
                return assess(first.getProjectedExit(), second.getProjectedExit(), obstacle);
            }

            @Override
            public DemandGroupPlanner.Passage toRoot(DemandGroupPlanner.Demand target,
                    DemandGroupPlanner.RootOption root, BigDecimal groupFlowTph) {
                return assess(target.getProjectedExit(), root.getProjectedEndpoint(), obstacle);
            }
        };
    }

    private static DemandGroupPlanner.Passage assess(Point from, Point to, Geometry obstacle) {
        LineString line = FACTORY.createLineString(new Coordinate[] {
                from.getCoordinate(), to.getCoordinate()});
        return line.intersects(obstacle) ? DemandGroupPlanner.Passage.blocked("KNOWN_OBSTACLE")
                : DemandGroupPlanner.Passage.possible(from.distance(to));
    }

    private static Geometry rotatedObstacle(Geometry obstacle) {
        Coordinate[] coordinates = obstacle.getCoordinates();
        Coordinate[] rotated = new Coordinate[coordinates.length];
        for (int i = 0; i < coordinates.length; i++) {
            rotated[i] = rotated(FACTORY.createPoint(coordinates[i])).getCoordinate();
        }
        return FACTORY.createPolygon(rotated);
    }

    private static DemandGroupPlanner.Limits limits(int targets, int roots, int neighbors,
            int groupSize, int groups, int pairProbes, int rootProbes, int rootsPerGroup, int flow) {
        return new DemandGroupPlanner.Limits(targets, roots, neighbors, groupSize, groups,
                pairProbes, rootProbes, rootsPerGroup, BigDecimal.valueOf(flow));
    }

    private static List<List<TypedId>> groupMembers(DemandGroupPlanner.Plan plan) {
        return plan.getGroups().stream().map(DemandGroupPlanner.Group::getTargetIds)
                .collect(Collectors.toList());
    }

    private static List<List<TypedId>> rootMembership(DemandGroupPlanner.Plan plan) {
        return plan.getGroups().stream().map(DemandGroupPlanner.Group::getPossibleRootIds)
                .collect(Collectors.toList());
    }

    private static boolean groupsOverlap(DemandGroupPlanner.Plan plan) {
        List<DemandGroupPlanner.Group> groups = plan.getGroups();
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                Set<TypedId> shared = new HashSet<>(groups.get(i).getTargetIds());
                shared.retainAll(groups.get(j).getTargetIds());
                if (!shared.isEmpty() && !groups.get(i).getTargetIds().equals(groups.get(j).getTargetIds())) {
                    return true;
                }
            }
        }
        return false;
    }
}
