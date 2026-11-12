package tn.esprit.marketsim.common;

import java.io.IOException;
import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/** Refuse les decimales (5.5) pour les champs entiers. */
public class StrictIntDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getIntValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            BigDecimal value = p.getDecimalValue();
            return (Integer) ctxt.handleWeirdNumberValue(Integer.class, value, "valeur entiere requise (pas de decimales)");
        }
        if (token == JsonToken.VALUE_STRING) {
            String text = p.getText().trim();
            if (text.matches("-?\\d+")) {
                return Integer.parseInt(text);
            }
            return (Integer) ctxt.handleWeirdStringValue(Integer.class, text, "valeur entiere requise");
        }
        return (Integer) ctxt.handleUnexpectedToken(Integer.class, p);
    }
}
