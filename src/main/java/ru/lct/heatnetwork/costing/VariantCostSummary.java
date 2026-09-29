package ru.lct.heatnetwork.costing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.lct.heatnetwork.domain.TypedId;

/** Monetary and length totals for one independently validated network variant. */
public final class VariantCostSummary {
    private final BigDecimal constructionCost;
    private final BigDecimal chamberConstructionCost;
    private final int existingChamberTieInCount;
    private final BigDecimal existingChamberTieInCost;
    private final BigDecimal unconnectedPenalty;
    private final BigDecimal calculatedCost;
    private final BigDecimal newNetworkLengthM;
    private final BigDecimal score;
    private final List<TypedId> unconnectedTargetIds;
    private final List<TypedId> unsearchedTargetIds;

    VariantCostSummary(BigDecimal constructionCost, BigDecimal chamberConstructionCost,
            int existingChamberTieInCount, BigDecimal existingChamberTieInCost,
            BigDecimal unconnectedPenalty, BigDecimal calculatedCost, BigDecimal newNetworkLengthM,
            BigDecimal score, List<TypedId> unconnectedTargetIds) {
        this(constructionCost, chamberConstructionCost, existingChamberTieInCount,
                existingChamberTieInCost, unconnectedPenalty, calculatedCost, newNetworkLengthM,
                score, unconnectedTargetIds, Collections.emptyList());
    }

    VariantCostSummary(BigDecimal constructionCost, BigDecimal chamberConstructionCost,
            int existingChamberTieInCount, BigDecimal existingChamberTieInCost,
            BigDecimal unconnectedPenalty, BigDecimal calculatedCost, BigDecimal newNetworkLengthM,
            BigDecimal score, List<TypedId> unconnectedTargetIds, List<TypedId> unsearchedTargetIds) {
        this.constructionCost = constructionCost;
        this.chamberConstructionCost = chamberConstructionCost;
        this.existingChamberTieInCount = existingChamberTieInCount;
        this.existingChamberTieInCost = existingChamberTieInCost;
        this.unconnectedPenalty = unconnectedPenalty;
        this.calculatedCost = calculatedCost;
        this.newNetworkLengthM = newNetworkLengthM;
        this.score = score;
        this.unconnectedTargetIds = Collections.unmodifiableList(new ArrayList<>(unconnectedTargetIds));
        this.unsearchedTargetIds = Collections.unmodifiableList(new ArrayList<>(unsearchedTargetIds));
    }

    public BigDecimal getConstructionCost() { return constructionCost; }
    public BigDecimal getChamberConstructionCost() { return chamberConstructionCost; }
    public int getExistingChamberTieInCount() { return existingChamberTieInCount; }
    public BigDecimal getExistingChamberTieInCost() { return existingChamberTieInCost; }
    public BigDecimal getUnconnectedPenalty() { return unconnectedPenalty; }
    public BigDecimal getCalculatedCost() { return calculatedCost; }
    public BigDecimal getNewNetworkLengthM() { return newNetworkLengthM; }
    public BigDecimal getScore() { return score; }
    public List<TypedId> getUnconnectedTargetIds() { return unconnectedTargetIds; }
    public List<TypedId> getUnsearchedTargetIds() { return unsearchedTargetIds; }
}
