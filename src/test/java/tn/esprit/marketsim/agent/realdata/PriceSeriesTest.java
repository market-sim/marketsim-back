package tn.esprit.marketsim.agent.realdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Lecture des donnees de marche reelles : CSV (formats varies) et reponse JSON de Yahoo Finance. */
class PriceSeriesTest {

    /** 250 jours ouvres a partir du 2 janvier 2024, prix croissant. */
    private static String csv(String header, String sep, String dateFormat, boolean decimalComma) {
        StringBuilder sb = new StringBuilder(header).append('\n');
        LocalDate d = LocalDate.of(2024, 1, 1);
        int added = 0;
        double price = 100;
        while (added < 250) {
            d = d.plusDays(1);
            if (d.getDayOfWeek().getValue() > 5) continue;
            String date = dateFormat.equals("iso") ? d.toString()
                    : String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
            String p = String.format(java.util.Locale.ROOT, "%.2f", price += 0.5);
            if (decimalComma) p = p.replace('.', ',');
            sb.append(date).append(sep).append(p).append('\n');
            added++;
        }
        return sb.toString();
    }

    @Test
    void readsAYahooStyleCsvAndPrefersAdjClose() {
        StringBuilder sb = new StringBuilder("Date,Open,High,Low,Close,Adj Close,Volume\n");
        LocalDate d = LocalDate.of(2023, 1, 2);
        for (int i = 0; i < 250; i++) {
            d = d.plusDays(d.getDayOfWeek().getValue() == 5 ? 3 : 1);
            sb.append(d).append(",1,1,1,200.00,").append(100 + i).append(".50,1000\n");
        }
        PriceSeries s = PriceSeries.fromCsv("AAPL", sb.toString());
        assertEquals(250, s.size());
        assertEquals(100.5, s.closes()[0], 1e-9, "Adj Close, pas Close");
        assertEquals("CSV", s.source());
    }

    @Test
    void readsAFrenchExcelCsvWithSemicolonsAndDecimalCommas() {
        PriceSeries s = PriceSeries.fromCsv("EUR/USD", csv("Date;Clôture", ";", "fr", true));
        assertEquals(250, s.size());
        assertEquals(100.5, s.closes()[0], 1e-9);
        assertTrue(s.dates()[0].isBefore(s.dates()[249]));
    }

    @Test
    void readsATwoColumnCsvWithoutHeader() {
        String text = csv("", ",", "iso", false).strip();
        PriceSeries s = PriceSeries.fromCsv("X", text.substring(text.indexOf('\n') + 1));
        assertTrue(s.size() >= 249);
    }

    @Test
    void sortsDropsDuplicatesAndInvalidPrices() {
        StringBuilder sb = new StringBuilder("Date,Close\n");
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 260; i++) sb.append(d.plusDays(259 - i)).append(',').append(10 + i).append('\n');   // ordre inverse
        sb.append(d).append(",5\n");              // doublon : le dernier ecrase
        sb.append(d.plusDays(300)).append(",-3\n");   // prix negatif : ignore
        sb.append(d.plusDays(301)).append(",null\n"); // ignore
        PriceSeries s = PriceSeries.fromCsv("X", sb.toString());
        assertEquals(260, s.size());
        assertEquals(d, s.dates()[0], "ordre chronologique");
    }

    @Test
    void rejectsTooShortOrUnreadableFiles() {
        assertThrows(IllegalArgumentException.class, () -> PriceSeries.fromCsv("X", ""));
        assertThrows(IllegalArgumentException.class, () -> PriceSeries.fromCsv("X", "Date,Close\n2024-01-01,10\n2024-01-02,11\n"));
        assertThrows(IllegalArgumentException.class, () -> PriceSeries.fromCsv("X", "Date,Volume\n2024-01-01,10\n2024-01-02,11\n"));
        assertThrows(IllegalArgumentException.class, () -> PriceSeries.fromCsv("X", "n'importe quoi\nencore du texte\net une troisieme ligne\n"));
    }

    @Test
    void encodeDecodeRoundTripKeepsTheHash() {
        PriceSeries s = PriceSeries.fromCsv("X", csv("Date,Close", ",", "iso", false));
        PriceSeries again = PriceSeries.decode("X", "CSV", s.encode());
        assertEquals(s.hash(), again.hash());
        assertEquals(64, s.hash().length());
        assertEquals(s.size(), again.size());
    }

    @Test
    void hashChangesWhenASinglePriceChanges() {
        PriceSeries a = PriceSeries.fromCsv("X", csv("Date,Close", ",", "iso", false));
        String edited = csv("Date,Close", ",", "iso", false).replaceFirst("100\\.50", "100.51");
        assertNotEquals(a.hash(), PriceSeries.fromCsv("X", edited).hash());
    }

    @Test
    void parsesYahooJsonWithAdjustedCloseAndClosedDays() throws Exception {
        StringBuilder ts = new StringBuilder();
        StringBuilder close = new StringBuilder();
        StringBuilder adj = new StringBuilder();
        long t0 = 1_700_000_000L;
        for (int i = 0; i < 260; i++) {
            ts.append(i == 0 ? "" : ",").append(t0 + i * 86_400L);
            close.append(i == 0 ? "" : ",").append(i == 5 ? "null" : String.valueOf(50 + i));   // jour ferme
            adj.append(i == 0 ? "" : ",").append(i == 5 ? "null" : String.valueOf(40 + i));
        }
        String json = "{\"chart\":{\"result\":[{\"meta\":{\"gmtoffset\":-14400},\"timestamp\":[" + ts + "],"
                + "\"indicators\":{\"quote\":[{\"close\":[" + close + "]}],\"adjclose\":[{\"adjclose\":[" + adj + "]}]}}],\"error\":null}}";
        PriceSeries s = PriceSeries.fromYahoo("AAPL", new ObjectMapper().readTree(json));
        assertEquals(259, s.size(), "le jour null est ignore");
        assertEquals(40.0, s.closes()[0], 1e-9, "cours ajuste prefere");
        assertEquals("YAHOO", s.source());
    }

    @Test
    void yahooErrorsBecomeReadableMessages() throws Exception {
        String json = "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found, symbol may be delisted\"}}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PriceSeries.fromYahoo("ZZZZ", new ObjectMapper().readTree(json)));
        assertTrue(e.getMessage().contains("delisted"));
    }
}
