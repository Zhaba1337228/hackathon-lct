package ru.lct.heatnetwork.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RuleCatalogTest {
    private static final String[][] EXPECTED_DIAMETERS = {
        {"50","3.5","181","74023","0.400","0.125"}, {"65","8.3","245","78631","0.430","0.140"},
        {"80","13.2","327","83530","0.470","0.160"}, {"100","22.3","419","89748","0.510","0.180"},
        {"125","40.2","554","97275","0.600","0.225"}, {"150","65.1","696","105507","0.650","0.250"},
        {"200","152.3","1042","120275","0.880","0.315"}, {"250","274.9","1379","135323","1.050","0.400"},
        {"300","437.4","1718","150022","1.150","0.450"}, {"400","943.1","2477","190299","1.370","0.560"},
        {"500","1663.4","3245","224137","1.670","0.710"}, {"600","2627.7","4037","264790","1.850","0.800"},
        {"700","3735.1","4775","324298","2.050","0.900"}, {"800","5296.8","5644","325996","2.250","1.000"},
        {"900","7165.0","6518","327693","2.450","1.100"}, {"1000","9391.8","7419","418777","2.650","1.200"},
        {"1200","15012.8","9288","428074","3.100","1.425"}, {"1400","22501.9","11276","683417","3.450","1.600"}
    };

    @Test
    void allDiameterRowsMatchTheApprovedRequirementsTable() {
        assertEquals(EXPECTED_DIAMETERS.length, RuleCatalog.diameters().size());
        for (int i = 0; i < EXPECTED_DIAMETERS.length; i++) {
            DiameterRule rule = RuleCatalog.diameters().get(i);
            String[] expected = EXPECTED_DIAMETERS[i];
            assertEquals(Integer.parseInt(expected[0]), rule.getDiameterMm());
            assertDecimal(expected[1], rule.getCapacityTph());
            assertDecimal(expected[2], rule.getMaxLengthM());
            assertDecimal(expected[3], rule.getPricePerMeter());
            assertDecimal(expected[4], rule.getPairWidthM());
            assertDecimal(expected[5], rule.getHeightM());
        }
        assertThrows(UnsupportedOperationException.class,
                () -> RuleCatalog.diameters().add(RuleCatalog.diameters().get(0)));
    }

    @Test
    void selectsSmallestDiameterWithInclusiveFlowAndLengthLimits() {
        assertEquals(50, RuleCatalog.smallestDiameter(new BigDecimal("3.5"), new BigDecimal("181"))
                .orElseThrow(AssertionError::new).getDiameterMm());
        assertEquals(65, RuleCatalog.smallestDiameter(new BigDecimal("3.5"), new BigDecimal("181.0001"))
                .orElseThrow(AssertionError::new).getDiameterMm());
        assertEquals(65, RuleCatalog.smallestDiameter(new BigDecimal("3.5001"), new BigDecimal("181"))
                .orElseThrow(AssertionError::new).getDiameterMm());
        assertFalse(RuleCatalog.smallestDiameter(new BigDecimal("22501.91"), BigDecimal.ZERO).isPresent());
    }

    @Test
    void chamberRangesCoverEveryTabulatedDiameterExactlyOnce() {
        for (DiameterRule diameter : RuleCatalog.diameters()) {
            assertTrue(RuleCatalog.chamberCost(diameter.getDiameterMm()).signum() > 0);
        }
        assertDecimal("3000000", RuleCatalog.chamberCost(200));
        assertDecimal("5000000", RuleCatalog.chamberCost(250));
        assertDecimal("5000000", RuleCatalog.chamberCost(500));
        assertDecimal("8000000", RuleCatalog.chamberCost(600));
        assertDecimal("8000000", RuleCatalog.chamberCost(1000));
        assertDecimal("12000000", RuleCatalog.chamberCost(1200));
        assertDecimal("12000000", RuleCatalog.chamberCost(1400));
        assertDecimal("5000000", RuleCatalog.existingChamberTieInCost());
        assertThrows(IllegalArgumentException.class, () -> RuleCatalog.chamberCost(1150));
    }

    @Test
    void restrictionTableMatchesClearanceAndSpecialPassagePolicies() {
        assertEquals(11, RuleCatalog.restrictions().size());
        assertEquals(RestrictionRule.Crossing.FINAL_APPROACH_ONLY,
                RuleCatalog.restriction("oks").orElseThrow(AssertionError::new).getCrossing());
        RestrictionRule oks = RuleCatalog.restriction("oks").orElseThrow(AssertionError::new);
        assertDecimal("5.0", oks.minimumClearanceM(499));
        assertDecimal("7.0", oks.minimumClearanceM(500));
        assertDecimal("7.0", oks.minimumClearanceM(800));
        assertDecimal("9.0", oks.minimumClearanceM(900));
        assertDecimal("1.60", RuleCatalog.restriction("road").orElseThrow(AssertionError::new)
                .getSpecialMultiplier());
        assertDecimal("1.75", RuleCatalog.restriction("tram_tracks").orElseThrow(AssertionError::new)
                .getSpecialMultiplier());
        assertDecimal("1.25", RuleCatalog.restriction("gas_pipeline").orElseThrow(AssertionError::new)
                .getSpecialMultiplier());
        assertDecimal("1.15", RuleCatalog.restriction("power_cable").orElseThrow(AssertionError::new)
                .getSpecialMultiplier());
        assertDecimal("1.05", RuleCatalog.restriction("heat_network").orElseThrow(AssertionError::new)
                .getSpecialMultiplier());
        assertEquals(RestrictionRule.Crossing.PROHIBITED,
                RuleCatalog.restriction("water").orElseThrow(AssertionError::new).getCrossing());
        assertFalse(RuleCatalog.restriction("unknown").isPresent());
        assertEquals(Arrays.asList("oks", "park", "social_area", "prohibited_site", "water", "railway",
                "road", "tram_tracks", "gas_pipeline", "power_cable", "heat_network").size(),
                RuleCatalog.restrictions().size());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
