package ru.lct.heatnetwork.input;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import ru.lct.heatnetwork.domain.InputFeature;

/** Reads a FeatureCollection one Feature at a time and retains only a compact summary. */
@Component
public final class GeoJsonStreamReader {
    private final ObjectMapper mapper;
    private final InputValidator validator;
    private final FeatureIdRegistryFactory idRegistryFactory;

    public GeoJsonStreamReader(InputValidator validator, FeatureIdRegistryFactory idRegistryFactory) {
        this.validator = validator;
        this.idRegistryFactory = idRegistryFactory;
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper = new ObjectMapper(factory);
    }

    public Summary read(InputStream input) {
        return read(input, feature -> { });
    }

    public Summary read(InputStream input, Consumer<InputFeature> featureConsumer) {
        Objects.requireNonNull(featureConsumer, "featureConsumer");
        try (FeatureIdRegistry idRegistry = idRegistryFactory.create();
                JsonParser parser = mapper.getFactory().createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new InputValidationException("$", "Expected a GeoJSON object");
            }
            boolean featureCollection = false;
            boolean featuresSeen = false;
            long count = 0;
            Map<String, Long> countsByType = new LinkedHashMap<>();
            InputValidator.ValidationContext context = validator.newContext(idRegistry);

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw new InputValidationException("$", "Expected a top-level field name");
                }
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("type".equals(field)) {
                    featureCollection = valueToken == JsonToken.VALUE_STRING
                            && "FeatureCollection".equals(parser.getText());
                    if (!featureCollection) {
                        throw new InputValidationException("type", "Expected GeoJSON FeatureCollection");
                    }
                } else if ("features".equals(field)) {
                    if (featuresSeen) throw new InputValidationException("features", "Duplicate features field");
                    featuresSeen = true;
                    if (valueToken != JsonToken.START_ARRAY) {
                        throw new InputValidationException("features", "Expected an array of Features");
                    }
                    long index = 0;
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (parser.currentToken() != JsonToken.START_OBJECT) {
                            throw new InputValidationException("features[" + index + "]", "Expected a Feature object");
                        }
                        InputFeature feature = mapper.readValue(parser, InputFeature.class);
                        validator.validate(feature, index, context);
                        featureConsumer.accept(feature);
                        countsByType.merge(feature.getObjectType(), 1L, Long::sum);
                        count++;
                        index++;
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw new InputValidationException("$", "Unexpected content after FeatureCollection");
            }
            if (!featureCollection) throw new InputValidationException("type", "Expected GeoJSON FeatureCollection");
            if (!featuresSeen) throw new InputValidationException("features", "Required field is missing");
            idRegistry.complete();
            return new Summary(count, countsByType);
        } catch (InputValidationException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new InputValidationException("$", "Invalid GeoJSON: " + exception.getMessage(), exception);
        }
    }

    public static final class Summary {
        private final long featureCount;
        private final Map<String, Long> countsByType;

        private Summary(long featureCount, Map<String, Long> countsByType) {
            this.featureCount = featureCount;
            this.countsByType = Collections.unmodifiableMap(new LinkedHashMap<>(countsByType));
        }

        public long getFeatureCount() { return featureCount; }
        public Map<String, Long> getCountsByType() { return countsByType; }
    }
}
