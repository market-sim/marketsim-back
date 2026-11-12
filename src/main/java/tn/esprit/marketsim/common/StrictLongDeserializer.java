package tn.esprit.marketsim.common;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/** Refuse les decimales pour les champs long. */
public class StrictLongDeserializer extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getLongValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            BigDecimal value = p.getDecimalValue();
            return (Long) ctxt.handleWeirdNumberValue(Long.class, value, "valeur entiere requise (pas de decimales)");
        }
        if (token == JsonToken.VALUE_STRING) {
            String text = p.getText().trim();
            if (text.matches("-?\\d+")) {
                return Long.parseLong(text);
            }
            return (Long) ctxt.handleWeirdStringValue(Long.class, text, "valeur entiere requise");
        }
        return (Long) ctxt.handleUnexpectedToken(Long.class, p);
    }
}
