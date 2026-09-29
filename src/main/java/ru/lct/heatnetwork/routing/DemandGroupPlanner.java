package ru.lct.heatnetwork.routing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.locationtech.jts.geom.Point;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.domain.TypedIdOrder;
import ru.lct.heatnetwork.projection.CrsTransform;

/**
 * Bounded, job-local demand grouping before any new pipe is fixed. The probe supplies only
 * preliminary corridor evidence. A possible link or root is neither an engineered route nor
 * permission to occupy land; the later corridor search and full validator decide that.
 */
public final class DemandGroupPlanner {
    public interface PassageProbe {
        /** Preliminary, symmetric connection between projected exits at their combined demand. */
        Passage betweenExits(Demand first, Demand second);

        /** Preliminary connection from one projected exit to a candidate existing-network root. */
        Passage toRoot(Demand target, RootOption root, BigDecimal groupFlowTph);
    }

    public enum PassageStatus { POSSIBLE, BLOCKED, UNKNOWN }

    public enum LandRightsStatus { NOT_VERIFIED_REQUIRES_AGREEMENT }

    public static final class Passage {
        private final PassageStatus status;
        private final double estimatedLengthM;
        private final String reason;

        private Passage(PassageStatus status, double estimatedLengthM, String reason) {
            this.status = status;
            this.estimatedLengthM = estimatedLengthM;
            this.reason = reason;
        }

        public static Passage possible(double estimatedLengthM) {
            if (!Double.isFinite(estimatedLengthM) || estimatedLengthM < 0) {
                throw new IllegalArgumentException("Estimated passage length must be finite and non-negative");
            }
            return new Passage(PassageStatus.POSSIBLE, estimatedLengthM, "PRELIMINARY_PASSAGE");
        }

        public static Passage blocked(String reason) {
            return new Passage(PassageStatus.BLOCKED, Double.NaN, nonBlank(reason));
        }

        public static Passage unknown(String reason) {
            return new Passage(PassageStatus.UNKNOWN, Double.NaN, nonBlank(reason));
        }

        public PassageStatus getStatus() { return status; }
        public double getEstimatedLengthM() { return estimatedLengthM; }
        public String getReason() { return reason; }
    }

    public static final class Demand {
        private final TypedId id;
        private final Point projectedExit;
        private final BigDecimal flowTph;

        public Demand(TypedId id, Point projectedExit, BigDecimal flowTph) {
            this.id = Objects.requireNonNull(id, "id");
            this.projectedExit = projectedPoint(projectedExit);
            if (flowTph == null || flowTph.signum() <= 0) {
                throw new IllegalArgumentException("Demand flow must be positive");
            }
            this.flowTph = flowTph;
        }

        public TypedId getId() { return id; }
        public Point getProjectedExit() { return (Point) projectedExit.copy(); }
        public BigDecimal getFlowTph() { return flowTph; }
    }

    /** Stable typed option ID can identify a chamber or a proposed point on an existing network. */
    public static final class RootOption {
        private final TypedId id;
        private final TypedId networkId;
        private final Point projectedEndpoint;

        public RootOption(TypedId id, TypedId networkId, Point projectedEndpoint) {
            this.id = Objects.requireNonNull(id, "id");
            this.networkId = Objects.requireNonNull(networkId, "networkId");
            this.projectedEndpoint = projectedPoint(projectedEndpoint);
        }

        public TypedId getId() { return id; }
        public TypedId getNetworkId() { return networkId; }
        public Point getProjectedEndpoint() { return (Point) projectedEndpoint.copy(); }
    }

    /** Every limit is explicit so a large job cannot silently turn into a quadratic allocation. */
    public static final class Limits {
        private final int maximumTargets;
        private final int maximumRoots;
        private final int neighborsPerTarget;
        private final int maximumGroupSize;
        private final int maximumGroups;
        private final int maximumPairProbes;
        private final int maximumRootProbes;
        private final int rootsPerGroup;
        private final BigDecimal maximumGroupFlowTph;

        public Limits(int maximumTargets, int maximumRoots, int neighborsPerTarget,
                int maximumGroupSize, int maximumGroups, int maximumPairProbes,
                int maximumRootProbes, int rootsPerGroup, BigDecimal maximumGroupFlowTph) {
            if (maximumTargets < 2 || maximumRoots < 0 || neighborsPerTarget < 1
                    || maximumGroupSize < 2 || maximumGroups < 1 || maximumPairProbes < 1
                    || maximumRootProbes < 0 || rootsPerGroup < 0
                    || maximumGroupFlowTph == null || maximumGroupFlowTph.signum() <= 0) {
                throw new IllegalArgumentException("Demand-group limits must be positive and consistent");
            }
            this.maximumTargets = maximumTargets;
            this.maximumRoots = maximumRoots;
            this.neighborsPerTarget = neighborsPerTarget;
            this.maximumGroupSize = maximumGroupSize;
            this.maximumGroups = maximumGroups;
            this.maximumPairProbes = maximumPairProbes;
            this.maximumRootProbes = maximumRootProbes;
            this.rootsPerGroup = rootsPerGroup;
            this.maximumGroupFlowTph = maximumGroupFlowTph;
        }
    }

    /** Evidence from a checked neighboring pair, including why an apparent neighbor was split. */
    public static final class PairEvidence {
        private final TypedId firstId;
        private final TypedId secondId;
        private final double straightDistanceM;
        private final Passage passage;

        private PairEvidence(TypedId firstId, TypedId secondId, double straightDistanceM, Passage passage) {
            this.firstId = firstId;
            this.secondId = secondId;
            this.straightDistanceM = straightDistanceM;
            this.passage = passage;
        }

        public TypedId getFirstId() { return firstId; }
        public TypedId getSecondId() { return secondId; }
        public double getStraightDistanceM() { return straightDistanceM; }
        public Passage getPassage() { return passage; }
    }

    public static final class Group {
        private final List<TypedId> targetIds;
        private final BigDecimal totalFlowTph;
        private final List<TypedId> possibleRootIds;
        private final List<TypedId> excludedByFlowLimitIds;

        private Group(List<TypedId> targetIds, BigDecimal totalFlowTph,
                List<TypedId> possibleRootIds, List<TypedId> excludedByFlowLimitIds) {
            this.targetIds = immutable(targetIds);
            this.totalFlowTph = totalFlowTph;
            this.possibleRootIds = immutable(possibleRootIds);
            this.excludedByFlowLimitIds = immutable(excludedByFlowLimitIds);
        }

        public List<TypedId> getTargetIds() { return targetIds; }
        public BigDecimal getTotalFlowTph() { return totalFlowTph; }
        public List<TypedId> getPossibleRootIds() { return possibleRootIds; }
        public List<TypedId> getExcludedByFlowLimitIds() { return excludedByFlowLimitIds; }
        public LandRightsStatus getLandRightsStatus() {
            return LandRightsStatus.NOT_VERIFIED_REQUIRES_AGREEMENT;
        }
    }

    public static final class Plan {
        private final List<Group> groups;
        private final List<PairEvidence> pairEvidence;
        private final List<TypedId> standaloneTargetIds;
        private final boolean pairBudgetExhausted;
        private final boolean rootBudgetExhausted;
        private final boolean groupLimitReached;
        private final int pairProbeCount;
        private final int rootProbeCount;

        private Plan(List<Group> groups, List<PairEvidence> pairEvidence, List<TypedId> standaloneTargetIds,
                boolean pairBudgetExhausted, boolean rootBudgetExhausted, boolean groupLimitReached,
                int pairProbeCount, int rootProbeCount) {
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.pairEvidence = Collections.unmodifiableList(new ArrayList<>(pairEvidence));
            this.standaloneTargetIds = immutable(standaloneTargetIds);
            this.pairBudgetExhausted = pairBudgetExhausted;
            this.rootBudgetExhausted = rootBudgetExhausted;
            this.groupLimitReached = groupLimitReached;
            this.pairProbeCount = pairProbeCount;
            this.rootProbeCount = rootProbeCount;
        }

        public List<Group> getGroups() { return groups; }
        public List<PairEvidence> getPairEvidence() { return pairEvidence; }
        /** Every target stays eligible for an individual route, even when grouping is incomplete. */
        public List<TypedId> getStandaloneTargetIds() { return standaloneTargetIds; }
        public boolean isPairBudgetExhausted() { return pairBudgetExhausted; }
        public boolean isRootBudgetExhausted() { return rootBudgetExhausted; }
        public boolean isGroupLimitReached() { return groupLimitReached; }
        public int getPairProbeCount() { return pairProbeCount; }
        public int getRootProbeCount() { return rootProbeCount; }
        public LandRightsStatus getLandRightsStatus() {
            return LandRightsStatus.NOT_VERIFIED_REQUIRES_AGREEMENT;
        }
    }

    public Plan plan(List<Demand> demands, List<RootOption> roots, PassageProbe probe, Limits limits) {
        Objects.requireNonNull(demands, "demands");
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(limits, "limits");
        if (demands.size() > limits.maximumTargets || roots.size() > limits.maximumRoots) {
            throw new IllegalArgumentException("Demand-group input exceeds its explicit target/root limit");
        }
        List<Demand> ordered = new ArrayList<>(demands);
        ordered.sort(Comparator.comparing(Demand::getId, TypedIdOrder.INSTANCE));
        uniqueIds(ordered, Demand::getId);
        List<RootOption> orderedRoots = new ArrayList<>(roots);
        orderedRoots.sort(Comparator.comparing(RootOption::getId, TypedIdOrder.INSTANCE));
        uniqueIds(orderedRoots, RootOption::getId);

        PlanningState state = new PlanningState(ordered, orderedRoots, probe, limits);
        List<Edge> graph = state.neighborGraph();
        graph.sort(Comparator.comparingDouble((Edge edge) -> edge.lengthM)
                .thenComparingInt(edge -> edge.first).thenComparingInt(edge -> edge.second));

        Map<List<TypedId>, Group> uniqueGroups = new LinkedHashMap<>();
        for (int seed = 0; seed < ordered.size(); seed++) {
            BitSet selected = new BitSet(ordered.size());
            selected.set(seed);
            BigDecimal flow = ordered.get(seed).flowTph;
            Set<TypedId> excludedByFlow = new HashSet<>();
            while (selected.cardinality() < limits.maximumGroupSize) {
                Edge best = null;
                int next = -1;
                for (Edge edge : graph) {
                    if (selected.get(edge.first) == selected.get(edge.second)) continue;
                    int outside = selected.get(edge.first) ? edge.second : edge.first;
                    if (flow.add(ordered.get(outside).flowTph)
                            .compareTo(limits.maximumGroupFlowTph) > 0) {
                        excludedByFlow.add(ordered.get(outside).id);
                        continue;
                    }
                    best = edge;
                    next = outside;
                    break;
                }
                if (best == null) break;
                selected.set(next);
                flow = flow.add(ordered.get(next).flowTph);
                excludedByFlow.remove(ordered.get(next).id);
            }
            if (selected.cardinality() < 2) continue;
            List<TypedId> ids = new ArrayList<>();
            for (int index = selected.nextSetBit(0); index >= 0; index = selected.nextSetBit(index + 1)) {
                ids.add(ordered.get(index).id);
            }
            if (uniqueGroups.containsKey(ids)) continue;
            if (uniqueGroups.size() >= limits.maximumGroups) {
                state.groupLimitReached = true;
                continue;
            }
            List<TypedId> rootIds = state.possibleRoots(selected, flow);
            List<TypedId> loadExcluded = new ArrayList<>(excludedByFlow);
            loadExcluded.removeAll(ids);
            loadExcluded.sort(TypedIdOrder.INSTANCE);
            uniqueGroups.put(ids, new Group(ids, flow, rootIds, loadExcluded));
        }
        List<TypedId> standalone = new ArrayList<>();
        for (Demand demand : ordered) standalone.add(demand.id);
        return new Plan(new ArrayList<>(uniqueGroups.values()), new ArrayList<>(state.pairs.values()),
                standalone, state.pairBudgetExhausted, state.rootBudgetExhausted,
                state.groupLimitReached, state.pairProbeCount, state.rootProbeCount);
    }

    private static final class PlanningState {
        private final List<Demand> demands;
        private final List<RootOption> roots;
        private final PassageProbe probe;
        private final Limits limits;
        private final Map<Long, PairEvidence> pairs = new LinkedHashMap<>();
        private int pairProbeCount;
        private int rootProbeCount;
        private boolean pairBudgetExhausted;
        private boolean rootBudgetExhausted;
        private boolean groupLimitReached;

        private PlanningState(List<Demand> demands, List<RootOption> roots,
                PassageProbe probe, Limits limits) {
            this.demands = demands;
            this.roots = roots;
            this.probe = probe;
            this.limits = limits;
        }

        private List<Edge> neighborGraph() {
            Map<Long, Edge> edges = new HashMap<>();
            for (int index = 0; index < demands.size(); index++) {
                final int seed = index;
                List<Integer> neighbors = new ArrayList<>(Math.max(0, demands.size() - 1));
                for (int other = 0; other < demands.size(); other++) {
                    if (other != seed) neighbors.add(other);
                }
                neighbors.sort(Comparator.comparingDouble((Integer other) ->
                        demands.get(seed).projectedExit.distance(demands.get(other).projectedExit))
                        .thenComparing(other -> demands.get(other).id, TypedIdOrder.INSTANCE));
                int possible = 0;
                for (int other : neighbors) {
                    if (possible >= limits.neighborsPerTarget) break;
                    if (demands.get(seed).flowTph.add(demands.get(other).flowTph)
                            .compareTo(limits.maximumGroupFlowTph) > 0) continue;
                    int first = Math.min(seed, other);
                    int second = Math.max(seed, other);
                    long key = pairKey(first, second);
                    PairEvidence evidence = pairs.get(key);
                    if (evidence == null) {
                        if (pairProbeCount >= limits.maximumPairProbes) {
                            pairBudgetExhausted = true;
                            break;
                        }
                        Passage passage = Objects.requireNonNull(probe.betweenExits(
                                demands.get(first), demands.get(second)), "pair passage");
                        double straight = demands.get(first).projectedExit.distance(
                                demands.get(second).projectedExit);
                        validateLength(passage, straight);
                        evidence = new PairEvidence(demands.get(first).id, demands.get(second).id,
                                straight, passage);
                        pairs.put(key, evidence);
                        pairProbeCount++;
                    }
                    if (evidence.passage.status == PassageStatus.POSSIBLE) {
                        possible++;
                        edges.putIfAbsent(key, new Edge(first, second,
                                evidence.passage.estimatedLengthM));
                    }
                }
            }
            return new ArrayList<>(edges.values());
        }

        private List<TypedId> possibleRoots(BitSet group, BigDecimal groupFlow) {
            List<Integer> rankedRoots = new ArrayList<>(roots.size());
            for (int index = 0; index < roots.size(); index++) rankedRoots.add(index);
            rankedRoots.sort(Comparator.comparingDouble((Integer rootIndex) ->
                    minimumDistance(group, roots.get(rootIndex)))
                    .thenComparing(rootIndex -> roots.get(rootIndex).id, TypedIdOrder.INSTANCE));
            List<TypedId> result = new ArrayList<>();
            for (int rootIndex : rankedRoots) {
                if (result.size() >= limits.rootsPerGroup) break;
                RootOption root = roots.get(rootIndex);
                List<Integer> members = new ArrayList<>(group.cardinality());
                for (int index = group.nextSetBit(0); index >= 0; index = group.nextSetBit(index + 1)) {
                    members.add(index);
                }
                members.sort(Comparator.comparingDouble((Integer member) ->
                        demands.get(member).projectedExit.distance(root.projectedEndpoint))
                        .thenComparing(member -> demands.get(member).id, TypedIdOrder.INSTANCE));
                for (int member : members) {
                    if (rootProbeCount >= limits.maximumRootProbes) {
                        rootBudgetExhausted = true;
                        return result;
                    }
                    Demand target = demands.get(member);
                    Passage passage = Objects.requireNonNull(probe.toRoot(target, root, groupFlow),
                            "root passage");
                    rootProbeCount++;
                    validateLength(passage, target.projectedExit.distance(root.projectedEndpoint));
                    if (passage.status == PassageStatus.POSSIBLE) {
                        result.add(root.id);
                        break;
                    }
                }
            }
            return result;
        }

        private double minimumDistance(BitSet group, RootOption root) {
            double minimum = Double.POSITIVE_INFINITY;
            for (int index = group.nextSetBit(0); index >= 0; index = group.nextSetBit(index + 1)) {
                minimum = Math.min(minimum, demands.get(index).projectedExit.distance(root.projectedEndpoint));
            }
            return minimum;
        }

        private long pairKey(int first, int second) {
            return (long) first * demands.size() + second;
        }
    }

    private static final class Edge {
        private final int first;
        private final int second;
        private final double lengthM;

        private Edge(int first, int second, double lengthM) {
            this.first = first;
            this.second = second;
            this.lengthM = lengthM;
        }
    }

    private static void validateLength(Passage passage, double straightDistanceM) {
        if (passage.status == PassageStatus.POSSIBLE
                && passage.estimatedLengthM + 1e-6 < straightDistanceM) {
            throw new IllegalArgumentException("Estimated passage is shorter than its straight-line lower bound");
        }
    }

    private static Point projectedPoint(Point point) {
        if (point == null || point.isEmpty() || point.getSRID() != CrsTransform.PROJECTED_SRID
                || !Double.isFinite(point.getX()) || !Double.isFinite(point.getY())) {
            throw new IllegalArgumentException("A finite EPSG:32637 point is required");
        }
        return (Point) point.copy();
    }

    private static String nonBlank(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("A specific passage reason is required");
        }
        return value;
    }

    private static <T> void uniqueIds(List<T> values, java.util.function.Function<T, TypedId> id) {
        for (int index = 1; index < values.size(); index++) {
            if (id.apply(values.get(index - 1)).equals(id.apply(values.get(index)))) {
                throw new IllegalArgumentException("Duplicate typed identifier in demand-group input");
            }
        }
    }

    private static List<TypedId> immutable(List<TypedId> ids) {
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }
}
