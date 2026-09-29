package ru.lct.heatnetwork.domain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TypedIdOrderTest {
    @Test
    void sortsNumericIdsBeforeStringsAndUsesNaturalValues() {
        assertTrue(TypedIdOrder.INSTANCE.compare(TypedId.of(new BigDecimal("2")),
                TypedId.of(new BigDecimal("10"))) < 0);
        assertTrue(TypedIdOrder.INSTANCE.compare(TypedId.of(new BigDecimal("10")), TypedId.of("ch1")) < 0);
        assertTrue(TypedIdOrder.INSTANCE.compare(TypedId.of("ch2"), TypedId.of("ch10")) < 0);
        assertTrue(TypedIdOrder.INSTANCE.compare(TypedId.of("ch02"), TypedId.of("ch2")) > 0);
    }
}
