package ru.lct.heatnetwork.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Single immutable source of the accepted case reference tables. */
public final class RuleCatalog {
    private static final List<DiameterRule> DIAMETERS = Collections.unmodifiableList(Arrays.asList(
            d(50,"3.5","181","74023","0.400","0.125"), d(65,"8.3","245","78631","0.430","0.140"),
            d(80,"13.2","327","83530","0.470","0.160"), d(100,"22.3","419","89748","0.510","0.180"),
            d(125,"40.2","554","97275","0.600","0.225"), d(150,"65.1","696","105507","0.650","0.250"),
            d(200,"152.3","1042","120275","0.880","0.315"), d(250,"274.9","1379","135323","1.050","0.400"),
            d(300,"437.4","1718","150022","1.150","0.450"), d(400,"943.1","2477","190299","1.370","0.560"),
            d(500,"1663.4","3245","224137","1.670","0.710"), d(600,"2627.7","4037","264790","1.850","0.800"),
            d(700,"3735.1","4775","324298","2.050","0.900"), d(800,"5296.8","5644","325996","2.250","1.000"),
            d(900,"7165.0","6518","327693","2.450","1.100"), d(1000,"9391.8","7419","418777","2.650","1.200"),
            d(1200,"15012.8","9288","428074","3.100","1.425"), d(1400,"22501.9","11276","683417","3.450","1.600")));

    private static final Map<Integer, DiameterRule> BY_DIAMETER;
    private static final List<ChamberCostRule> CHAMBER_COSTS = Collections.unmodifiableList(Arrays.asList(
            new ChamberCostRule(50, 200, "3000000"), new ChamberCostRule(250, 500, "5000000"),
            new ChamberCostRule(600, 1000, "8000000"), new ChamberCostRule(1200, 1400, "12000000")));
    private static final BigDecimal EXISTING_CHAMBER_TIE_IN_COST = new BigDecimal("5000000");
    private static final BigDecimal BASE_MULTIPLIER = BigDecimal.ONE;
    private static final Map<String, RestrictionRule> RESTRICTIONS;

    static {
        Map<Integer, DiameterRule> diameterMap = new TreeMap<>();
        for (DiameterRule rule : DIAMETERS) {
            if (diameterMap.put(rule.getDiameterMm(), rule) != null) {
                throw new IllegalStateException("Duplicate diameter " + rule.getDiameterMm());
            }
        }
        BY_DIAMETER = Collections.unmodifiableMap(diameterMap);
        validateChamberCoverage();

        Map<String, RestrictionRule> restrictions = new LinkedHashMap<>();
        add(restrictions, new RestrictionRule("oks", RestrictionRule.Crossing.FINAL_APPROACH_ONLY,
                "0", "1", true));
        for (String type : Arrays.asList("park", "social_area", "prohibited_site", "water", "railway")) {
            add(restrictions, new RestrictionRule(type, RestrictionRule.Crossing.PROHIBITED, "1.0", "1", false));
        }
        add(restrictions, new RestrictionRule("road", RestrictionRule.Crossing.SPECIAL_PASSAGE, "1.5", "1.60", false));
        add(restrictions, new RestrictionRule("tram_tracks", RestrictionRule.Crossing.SPECIAL_PASSAGE, "1.5", "1.75", false));
        add(restrictions, new RestrictionRule("gas_pipeline", RestrictionRule.Crossing.SPECIAL_PASSAGE, "2.0", "1.25", false));
        add(restrictions, new RestrictionRule("power_cable", RestrictionRule.Crossing.SPECIAL_PASSAGE, "2.0", "1.15", false));
        add(restrictions, new RestrictionRule("heat_network", RestrictionRule.Crossing.SPECIAL_PASSAGE, "1.0", "1.05", false));
        RESTRICTIONS = Collections.unmodifiableMap(restrictions);
    }

    private RuleCatalog() { }

    public static List<DiameterRule> diameters() { return DIAMETERS; }
    public static Optional<DiameterRule> diameter(int diameterMm) {
        return Optional.ofNullable(BY_DIAMETER.get(diameterMm));
    }
    public static Optional<DiameterRule> smallestDiameter(BigDecimal flowTph, BigDecimal pathLengthM) {
        if (flowTph == null || pathLengthM == null || flowTph.signum() <= 0 || pathLengthM.signum() < 0) {
            throw new IllegalArgumentException("Flow must be positive and path length must be non-negative");
        }
        return DIAMETERS.stream().filter(rule -> flowTph.compareTo(rule.getCapacityTph()) <= 0
                && pathLengthM.compareTo(rule.getMaxLengthM()) <= 0).findFirst();
    }
    public static List<ChamberCostRule> chamberCosts() { return CHAMBER_COSTS; }
    public static BigDecimal chamberCost(int maxDiameterMm) {
        return CHAMBER_COSTS.stream().filter(rule -> rule.contains(maxDiameterMm))
                .map(ChamberCostRule::getCostRub).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No chamber cost for DN " + maxDiameterMm));
    }
    public static BigDecimal existingChamberTieInCost() { return EXISTING_CHAMBER_TIE_IN_COST; }
    public static BigDecimal baseMultiplier() { return BASE_MULTIPLIER; }
    public static Optional<RestrictionRule> restriction(String type) {
        return Optional.ofNullable(RESTRICTIONS.get(type));
    }
    public static Map<String, RestrictionRule> restrictions() { return RESTRICTIONS; }

    private static DiameterRule d(int dn, String capacity, String length, String price, String width, String height) {
        return new DiameterRule(dn, capacity, length, price, width, height);
    }
    private static void add(Map<String, RestrictionRule> rules, RestrictionRule rule) {
        if (rules.put(rule.getType(), rule) != null) throw new IllegalStateException("Duplicate restriction " + rule.getType());
    }
    private static void validateChamberCoverage() {
        for (Integer diameter : BY_DIAMETER.keySet()) {
            long count = CHAMBER_COSTS.stream().filter(rule -> rule.contains(diameter)).count();
            if (count != 1) throw new IllegalStateException("Expected one chamber-cost rule for DN " + diameter);
        }
        for (int i = 0; i < CHAMBER_COSTS.size(); i++) {
            for (int j = i + 1; j < CHAMBER_COSTS.size(); j++) {
                ChamberCostRule a = CHAMBER_COSTS.get(i), b = CHAMBER_COSTS.get(j);
                if (a.getMinDiameterMm() <= b.getMaxDiameterMm() && b.getMinDiameterMm() <= a.getMaxDiameterMm()) {
                    throw new IllegalStateException("Overlapping chamber-cost intervals");
                }
            }
        }
    }
}
