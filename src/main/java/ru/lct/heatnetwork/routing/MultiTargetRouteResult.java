package ru.lct.heatnetwork.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.lct.heatnetwork.domain.TypedId;
import ru.lct.heatnetwork.network.RouteTreeOptimizer;

/** Bounded search result; incomplete search is explicit and never described as infeasibility. */
public final class MultiTargetRouteResult {
    public enum Status { COMPLETE, PARTIAL, NO_TARGET_ROUTED, NO_FEASIBLE_TREE, SEARCH_LIMIT_REACHED, INVALID_REQUEST }

    private final Status status;
    private final List<RouteTreeOptimizer.TreeCandidate> candidates;
    private final List<TypedId> unroutedTargetIds;
    private final List<TypedId> unsearchedTargetIds;
    private final Map<TypedId, List<RouteCandidate.Status>> targetAttempts;
    private final Map<String, Map<TypedId, String>> unsearchedReasonsByCandidate;
    private final int combinationsEvaluated;
    private final boolean combinationLimitReached;
    private final String detail;

    MultiTargetRouteResult(Status status, List<RouteTreeOptimizer.TreeCandidate> candidates,
            List<TypedId> unroutedTargetIds, List<TypedId> unsearchedTargetIds,
            Map<TypedId, List<RouteCandidate.Status>> targetAttempts,
            int combinationsEvaluated, boolean combinationLimitReached, String detail) {
        this(status, candidates, unroutedTargetIds, unsearchedTargetIds, targetAttempts,
                Collections.emptyMap(), combinationsEvaluated, combinationLimitReached, detail);
    }

    MultiTargetRouteResult(Status status, List<RouteTreeOptimizer.TreeCandidate> candidates,
            List<TypedId> unroutedTargetIds, List<TypedId> unsearchedTargetIds,
            Map<TypedId, List<RouteCandidate.Status>> targetAttempts,
            Map<String, Map<TypedId, String>> unsearchedReasonsByCandidate,
            int combinationsEvaluated, boolean combinationLimitReached, String detail) {
        this.status = status;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.unroutedTargetIds = Collections.unmodifiableList(new ArrayList<>(unroutedTargetIds));
        this.unsearchedTargetIds = Collections.unmodifiableList(new ArrayList<>(unsearchedTargetIds));
        Map<TypedId, List<RouteCandidate.Status>> copied = new LinkedHashMap<>();
        for (Map.Entry<TypedId, List<RouteCandidate.Status>> entry : targetAttempts.entrySet()) {
            copied.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        this.targetAttempts = Collections.unmodifiableMap(copied);
        Map<String, Map<TypedId, String>> diagnostics = new LinkedHashMap<>();
        for (Map.Entry<String, Map<TypedId, String>> entry : unsearchedReasonsByCandidate.entrySet()) {
            diagnostics.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        this.unsearchedReasonsByCandidate = Collections.unmodifiableMap(diagnostics);
        this.combinationsEvaluated = combinationsEvaluated;
        this.combinationLimitReached = combinationLimitReached;
        this.detail = detail;
    }

    public Status getStatus() { return status; }
    public List<RouteTreeOptimizer.TreeCandidate> getCandidates() { return candidates; }
    public List<TypedId> getUnroutedTargetIds() { return unroutedTargetIds; }
    public List<TypedId> getUnsearchedTargetIds() { return unsearchedTargetIds; }
    public Map<TypedId, List<RouteCandidate.Status>> getTargetAttempts() { return targetAttempts; }
    public Map<TypedId, String> getUnsearchedReasonsForCandidate(String variantId) {
        return unsearchedReasonsByCandidate.getOrDefault(variantId, Collections.emptyMap());
    }
    public boolean hasCandidateDiagnostics(String variantId) {
        return unsearchedReasonsByCandidate.containsKey(variantId);
    }
    public int getCombinationsEvaluated() { return combinationsEvaluated; }
    public boolean isCombinationLimitReached() { return combinationLimitReached; }
    public String getDetail() { return detail; }
}
