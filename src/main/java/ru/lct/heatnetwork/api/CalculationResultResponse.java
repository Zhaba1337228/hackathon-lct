package ru.lct.heatnetwork.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.lct.heatnetwork.domain.TypedId;

/** Published calculation summary. Geometry is served by the variant-view/export APIs. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Completed bounded routing search and ranked variant summaries")
public final class CalculationResultResponse {
    private String searchStatus;
    private String detail;
    private int totalTargets;
    private int combinationsEvaluated;
    private boolean combinationLimitReached;
    private List<TypedId> unconnectedTargetIds = new ArrayList<>();
    private List<TypedId> unsearchedTargetIds = new ArrayList<>();
    private List<TargetSearch> targetSearches = new ArrayList<>();
    private List<VariantSummary> variants = new ArrayList<>();
    private List<RouteAttemptEvent> attemptEvents = new ArrayList<>();
    private long truncatedAttemptEvents;

    public CalculationResultResponse() { }

    public CalculationResultResponse(String searchStatus, String detail, int totalTargets,
            int combinationsEvaluated, boolean combinationLimitReached, List<TypedId> unconnectedTargetIds,
            List<TypedId> unsearchedTargetIds, List<TargetSearch> targetSearches, List<VariantSummary> variants) {
        this.searchStatus = searchStatus;
        this.detail = detail;
        this.totalTargets = totalTargets;
        this.combinationsEvaluated = combinationsEvaluated;
        this.combinationLimitReached = combinationLimitReached;
        this.unconnectedTargetIds = new ArrayList<>(unconnectedTargetIds);
        this.unsearchedTargetIds = new ArrayList<>(unsearchedTargetIds);
        this.targetSearches = new ArrayList<>(targetSearches);
        this.variants = new ArrayList<>(variants);
    }

    public String getSearchStatus() { return searchStatus; }
    public void setSearchStatus(String value) { searchStatus = value; }
    public String getDetail() { return detail; }
    public void setDetail(String value) { detail = value; }
    public int getTotalTargets() { return totalTargets; }
    public void setTotalTargets(int value) { totalTargets = value; }
    public int getCombinationsEvaluated() { return combinationsEvaluated; }
    public void setCombinationsEvaluated(int value) { combinationsEvaluated = value; }
    public boolean isCombinationLimitReached() { return combinationLimitReached; }
    public void setCombinationLimitReached(boolean value) { combinationLimitReached = value; }
    @Schema(description = "Every input connection target absent from the selected variant; each incurs the specified penalty")
    public List<TypedId> getUnconnectedTargetIds() { return Collections.unmodifiableList(unconnectedTargetIds); }
    public void setUnconnectedTargetIds(List<TypedId> value) { unconnectedTargetIds = new ArrayList<>(value); }
    @Schema(description = "Diagnostic subset of unconnected targets outside the search area or with incomplete bounded search; their absence is included in the variant penalty")
    public List<TypedId> getUnsearchedTargetIds() { return Collections.unmodifiableList(unsearchedTargetIds); }
    public void setUnsearchedTargetIds(List<TypedId> value) { unsearchedTargetIds = new ArrayList<>(value); }
    public List<TargetSearch> getTargetSearches() { return Collections.unmodifiableList(targetSearches); }
    public void setTargetSearches(List<TargetSearch> value) { targetSearches = new ArrayList<>(value); }
    @Schema(description = "Ranked valid route variants, up to three")
    public List<VariantSummary> getVariants() { return Collections.unmodifiableList(variants); }
    public void setVariants(List<VariantSummary> value) { variants = new ArrayList<>(value); }
    @Schema(description = "Bounded history of tried attachment points; failed paths have no geometry")
    public List<RouteAttemptEvent> getAttemptEvents() {
        return Collections.unmodifiableList(RouteAttemptEvent.copyAll(attemptEvents));
    }
    public void setAttemptEvents(List<RouteAttemptEvent> value) {
        attemptEvents = RouteAttemptEvent.copyAll(value);
    }
    public long getTruncatedAttemptEvents() { return truncatedAttemptEvents; }
    public void setTruncatedAttemptEvents(long value) { truncatedAttemptEvents = value; }

    @Schema(description = "Search attempts for one target, including explicit route failure states")
    public static final class TargetSearch {
        private TypedId targetId;
        private List<String> attempts = new ArrayList<>();

        public TargetSearch() { }
        public TargetSearch(TypedId targetId, List<String> attempts) {
            this.targetId = targetId;
            this.attempts = new ArrayList<>(attempts);
        }
        public TypedId getTargetId() { return targetId; }
        public void setTargetId(TypedId value) { targetId = value; }
        public List<String> getAttempts() { return Collections.unmodifiableList(attempts); }
        public void setAttempts(List<String> value) { attempts = new ArrayList<>(value); }
    }

    @Schema(description = "Cost and score summary for one ranked variant")
    public static final class VariantSummary {
        private int rank;
        private String variantId;
        private BigDecimal constructionCost;
        private BigDecimal chamberConstructionCost;
        private int existingChamberTieInCount;
        private BigDecimal existingChamberTieInCost;
        private BigDecimal unconnectedPenalty;
        private BigDecimal calculatedCost;
        private BigDecimal newNetworkLengthM;
        private BigDecimal score;
        private List<TypedId> connectedTargetIds = new ArrayList<>();
        private List<TypedId> unconnectedTargetIds = new ArrayList<>();
        private List<TypedId> unsearchedTargetIds = new ArrayList<>();
        private List<TargetReason> unsearchedReasons = new ArrayList<>();

        public VariantSummary() { }

        public VariantSummary(int rank, String variantId, BigDecimal constructionCost,
                BigDecimal chamberConstructionCost, int existingChamberTieInCount,
                BigDecimal existingChamberTieInCost, BigDecimal unconnectedPenalty,
                BigDecimal calculatedCost, BigDecimal newNetworkLengthM, BigDecimal score,
                List<TypedId> unconnectedTargetIds) {
            this(rank, variantId, constructionCost, chamberConstructionCost, existingChamberTieInCount,
                    existingChamberTieInCost, unconnectedPenalty, calculatedCost, newNetworkLengthM,
                    score, Collections.emptyList(), unconnectedTargetIds);
        }

        public VariantSummary(int rank, String variantId, BigDecimal constructionCost,
                BigDecimal chamberConstructionCost, int existingChamberTieInCount,
                BigDecimal existingChamberTieInCost, BigDecimal unconnectedPenalty,
                BigDecimal calculatedCost, BigDecimal newNetworkLengthM, BigDecimal score,
                List<TypedId> connectedTargetIds, List<TypedId> unconnectedTargetIds) {
            this.rank = rank;
            this.variantId = variantId;
            this.constructionCost = constructionCost;
            this.chamberConstructionCost = chamberConstructionCost;
            this.existingChamberTieInCount = existingChamberTieInCount;
            this.existingChamberTieInCost = existingChamberTieInCost;
            this.unconnectedPenalty = unconnectedPenalty;
            this.calculatedCost = calculatedCost;
            this.newNetworkLengthM = newNetworkLengthM;
            this.score = score;
            this.connectedTargetIds = new ArrayList<>(connectedTargetIds);
            this.unconnectedTargetIds = new ArrayList<>(unconnectedTargetIds);
        }

        public int getRank() { return rank; }
        public void setRank(int value) { rank = value; }
        public String getVariantId() { return variantId; }
        public void setVariantId(String value) { variantId = value; }
        public BigDecimal getConstructionCost() { return constructionCost; }
        public void setConstructionCost(BigDecimal value) { constructionCost = value; }
        public BigDecimal getChamberConstructionCost() { return chamberConstructionCost; }
        public void setChamberConstructionCost(BigDecimal value) { chamberConstructionCost = value; }
        public int getExistingChamberTieInCount() { return existingChamberTieInCount; }
        public void setExistingChamberTieInCount(int value) { existingChamberTieInCount = value; }
        public BigDecimal getExistingChamberTieInCost() { return existingChamberTieInCost; }
        public void setExistingChamberTieInCost(BigDecimal value) { existingChamberTieInCost = value; }
        public BigDecimal getUnconnectedPenalty() { return unconnectedPenalty; }
        public void setUnconnectedPenalty(BigDecimal value) { unconnectedPenalty = value; }
        public BigDecimal getCalculatedCost() { return calculatedCost; }
        public void setCalculatedCost(BigDecimal value) { calculatedCost = value; }
        public BigDecimal getNewNetworkLengthM() { return newNetworkLengthM; }
        public void setNewNetworkLengthM(BigDecimal value) { newNetworkLengthM = value; }
        public BigDecimal getScore() { return score; }
        public void setScore(BigDecimal value) { score = value; }
        @Schema(description = "Typed IDs of input connection targets reached by this route variant")
        public List<TypedId> getConnectedTargetIds() { return Collections.unmodifiableList(connectedTargetIds); }
        public void setConnectedTargetIds(List<TypedId> value) { connectedTargetIds = new ArrayList<>(value); }
        @Schema(description = "All connection targets absent from this variant, including any unsearched diagnostic targets")
        public List<TypedId> getUnconnectedTargetIds() { return Collections.unmodifiableList(unconnectedTargetIds); }
        public void setUnconnectedTargetIds(List<TypedId> value) { unconnectedTargetIds = new ArrayList<>(value); }
        public List<TypedId> getUnsearchedTargetIds() { return Collections.unmodifiableList(unsearchedTargetIds); }
        public void setUnsearchedTargetIds(List<TypedId> value) { unsearchedTargetIds = new ArrayList<>(value); }
        public List<TargetReason> getUnsearchedReasons() { return Collections.unmodifiableList(unsearchedReasons); }
        public void setUnsearchedReasons(List<TargetReason> value) { unsearchedReasons = new ArrayList<>(value); }
    }

    public static final class TargetReason {
        private TypedId targetId;
        private String reason;

        public TargetReason() { }
        public TargetReason(TypedId targetId, String reason) {
            this.targetId = targetId;
            this.reason = reason;
        }
        public TypedId getTargetId() { return targetId; }
        public void setTargetId(TypedId value) { targetId = value; }
        public String getReason() { return reason; }
        public void setReason(String value) { reason = value; }
    }
}
