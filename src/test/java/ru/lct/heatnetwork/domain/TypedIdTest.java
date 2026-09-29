package ru.lct.heatnetwork.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TypedIdTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsNumericAndStringIdentifiersDistinct() throws Exception {
        TypedId numeric = mapper.readValue("1", TypedId.class);
        TypedId string = mapper.readValue("\"1\"", TypedId.class);

        assertNotEquals(numeric, string);
        assertEquals("1", mapper.writeValueAsString(numeric));
        assertEquals("\"1\"", mapper.writeValueAsString(string));
    }

    @Test
    void preservesIntegerPrecisionBeyondLongRange() throws Exception {
        String exact = "1234567890123456789012345678901234567890";
        TypedId id = mapper.readValue(exact, TypedId.class);

        assertTrue(id.isNumeric());
        assertEquals(0, id.asNumber().compareTo(new BigDecimal(exact)));
        assertEquals(exact, mapper.writeValueAsString(id));
    }

    @Test
    void treatsEquivalentJsonNumbersAsTheSameNumericId() throws Exception {
        TypedId integer = mapper.readValue("1", TypedId.class);
        TypedId decimal = mapper.readValue("1.0", TypedId.class);

        assertEquals(integer, decimal);
        assertEquals(integer.hashCode(), decimal.hashCode());
    }
}
