package ru.lct.heatnetwork.domain;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;

/** A GeoJSON identifier whose JSON type is part of its identity. */
@JsonSerialize(using = TypedId.Serializer.class)
@JsonDeserialize(using = TypedId.Deserializer.class)
public final class TypedId {
    private final String stringValue;
    private final BigDecimal numericValue;

    private TypedId(String stringValue, BigDecimal numericValue) {
        this.stringValue = stringValue;
        this.numericValue = numericValue == null ? null : numericValue.stripTrailingZeros();
    }

    public static TypedId of(String value) {
        return new TypedId(Objects.requireNonNull(value, "value"), null);
    }

    public static TypedId of(BigDecimal value) {
        return new TypedId(null, Objects.requireNonNull(value, "value"));
    }

    public boolean isNumeric() {
        return numericValue != null;
    }

    public String asString() {
        if (isNumeric()) {
            throw new IllegalStateException("Identifier is numeric");
        }
        return stringValue;
    }

    public BigDecimal asNumber() {
        if (!isNumeric()) {
            throw new IllegalStateException("Identifier is a string");
        }
        return numericValue;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TypedId)) return false;
        TypedId that = (TypedId) other;
        if (isNumeric() != that.isNumeric()) return false;
        return isNumeric() ? numericValue.compareTo(that.numericValue) == 0
                : stringValue.equals(that.stringValue);
    }

    @Override
    public int hashCode() {
        return isNumeric() ? Objects.hash("number", numericValue) : Objects.hash("string", stringValue);
    }

    @Override
    public String toString() {
        return isNumeric() ? numericValue.toPlainString() : stringValue;
    }

    public static final class Serializer extends JsonSerializer<TypedId> {
        @Override
        public void serialize(TypedId value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            if (value.isNumeric()) {
                generator.writeNumber(value.numericValue.toPlainString());
            } else {
                generator.writeString(value.stringValue);
            }
        }
    }

    public static final class Deserializer extends JsonDeserializer<TypedId> {
        @Override
        public TypedId deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_STRING) return TypedId.of(parser.getText());
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                return TypedId.of(parser.getDecimalValue());
            }
            return (TypedId) context.handleUnexpectedToken(TypedId.class, parser);
        }
    }
}
