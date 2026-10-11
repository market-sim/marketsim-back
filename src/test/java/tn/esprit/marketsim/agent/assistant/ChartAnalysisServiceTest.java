package tn.esprit.marketsim.agent.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ChartAnalysisServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void schemaIsStrictAndComplete() throws Exception {
        String json = new ObjectMapper().writeValueAsString(ChartAnalysisService.schema()._additionalProperties());
        Map<String, Object> schema = new ObjectMapper().readValue(json, Map.class);
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(props.keySet(), new java.util.HashSet<>((List<String>) schema.get("required")), "tous les champs sont obligatoires");
        assertTrue(props.containsKey("signal") && props.containsKey("summary"));
    }

    @Test
    void parsesModelAnswerIntoRecord() throws Exception {
        String answer = """
                {"isChart":true,"asset":"BTC/USD","timeframe":"4h","trend":"HAUSSIERE","signal":"ACHAT","confidence":68,
                 "supports":["82 000"],"resistances":["86 500"],"patterns":["triangle ascendant"],"indicators":["RSI 58"],
                 "reasons":["plus hauts croissants"],"risks":["faux breakout"],"stopLoss":"81 500","takeProfit":"88 000",
                 "agentView":"Un agent Momentum achèterait.","summary":"Lecture pédagogique."}""";
        ChartAnalysisService.ChartAnalysis a = new ObjectMapper().readValue(answer, ChartAnalysisService.ChartAnalysis.class);
        assertEquals("ACHAT", a.signal());
        assertEquals(68, a.confidence());
        assertEquals(List.of("82 000"), a.supports());
    }
}
