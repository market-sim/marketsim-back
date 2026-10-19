package tn.esprit.marketsim.agent.realdata;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

import tn.esprit.marketsim.common.Hashing;

/**
 * Serie de cours de cloture journaliers (ordre chronologique, sans doublon, prix strictement positifs).
 * Savoir lire un export CSV ou la reponse JSON de Yahoo Finance, et produire une empreinte SHA-256 stable.
 *
 * @param hash SHA-256 du texte {@link #encode()} : identifie le jeu de donnees exact
 */
public record PriceSeries(String symbol, String source, LocalDate[] dates, double[] closes, String hash) {

    static final int MIN_POINTS = 200;
    private static final DateTimeFormatter DAY_FIRST = DateTimeFormatter.ofPattern("d/M/uuuu");
    private static final DateTimeFormatter DAY_FIRST_DASH = DateTimeFormatter.ofPattern("d-M-uuuu");
    private static final DateTimeFormatter YEAR_FIRST_SLASH = DateTimeFormatter.ofPattern("uuuu/M/d");

    public int size() {
        return closes.length;
    }

    /** Une ligne par jour : {@code 2024-03-15,172.620000}. */
    public String encode() {
        StringBuilder sb = new StringBuilder(closes.length * 20);
        for (int i = 0; i < closes.length; i++) {
            sb.append(dates[i]).append(',').append(String.format(Locale.ROOT, "%.6f", closes[i])).append('\n');
        }
        return sb.toString();
    }

    /** Construit une serie propre : tri chronologique, doublons ecartes, prix invalides ignores. */
    public static PriceSeries of(String symbol, String source, List<LocalDate> dates, List<Double> prices) {
        TreeMap<LocalDate, Double> sorted = new TreeMap<>();
        for (int i = 0; i < dates.size(); i++) {
            Double p = prices.get(i);
            if (dates.get(i) != null && p != null && Double.isFinite(p) && p > 0) sorted.put(dates.get(i), p);
        }
        LocalDate[] d = sorted.keySet().toArray(new LocalDate[0]);
        double[] c = sorted.values().stream().mapToDouble(Double::doubleValue).toArray();
        PriceSeries draft = new PriceSeries(symbol, source, d, c, null);
        return new PriceSeries(symbol, source, d, c, Hashing.sha256Hex(draft.encode()));
    }

    /** Relit une serie enregistree avec {@link #encode()}. */
    public static PriceSeries decode(String symbol, String source, String data) {
        List<LocalDate> dates = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        for (String line : data.split("\\R")) {
            int comma = line.indexOf(',');
            if (comma < 0) continue;
            dates.add(LocalDate.parse(line.substring(0, comma)));
            prices.add(Double.parseDouble(line.substring(comma + 1)));
        }
        return of(symbol, source, dates, prices);
    }

    // ===================== Yahoo Finance =====================

    /**
     * Lit la reponse de {@code /v8/finance/chart/{symbole}?interval=1d}. Utilise le cours ajuste
     * (dividendes et splits) quand il existe, sinon le cours de cloture.
     */
    public static PriceSeries fromYahoo(String symbol, JsonNode root) {
        JsonNode result = root.path("chart").path("result").path(0);
        if (result.isMissingNode() || result.isNull()) {
            String reason = root.path("chart").path("error").path("description").asText("reponse vide");
            throw new IllegalArgumentException("Yahoo Finance : " + reason);
        }
        JsonNode timestamps = result.path("timestamp");
        JsonNode adjusted = result.path("indicators").path("adjclose").path(0).path("adjclose");
        JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
        JsonNode prices = adjusted.isArray() && adjusted.size() == timestamps.size() ? adjusted : closes;
        if (!timestamps.isArray() || !prices.isArray() || prices.size() != timestamps.size()) {
            throw new IllegalArgumentException("Yahoo Finance : format de reponse inattendu");
        }
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(result.path("meta").path("gmtoffset").asInt(0));
        List<LocalDate> dates = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode price = prices.get(i);
            if (price == null || price.isNull()) continue;   // jour ferme
            dates.add(Instant.ofEpochSecond(timestamps.get(i).asLong()).atOffset(offset).toLocalDate());
            values.add(price.asDouble());
        }
        return of(symbol, "YAHOO", dates, values);
    }

    // ===================== Import CSV =====================

    /**
     * Lit un export CSV (Yahoo, Investing, Excel FR...) : separateur {@code , ; tab} detecte, colonne de date et
     * colonne de prix reperees par leur en-tete ({@code Adj Close} prefere a {@code Close}), ou 2 colonnes date,prix.
     */
    public static PriceSeries fromCsv(String symbol, String csv) {
        String[] lines = csv == null ? new String[0] : csv.strip().split("\\R");
        if (lines.length < 2) throw new IllegalArgumentException("Fichier CSV vide");
        char sep = separator(lines[0]);
        String[] head = split(lines[0], sep);
        boolean header = parseDate(head[0]) == null;
        int dateCol = 0;
        int priceCol = head.length > 1 ? 1 : -1;
        if (header) {
            dateCol = indexOf(head, 0, "date", "jour", "time");
            priceCol = firstIndex(indexOfAll(head, "adj", "close"),
                    indexOf(head, -1, "close"), indexOf(head, -1, "clôture"), indexOf(head, -1, "cloture"),
                    indexOf(head, -1, "dernier"), indexOf(head, -1, "price"), indexOf(head, -1, "prix"),
                    head.length == 2 ? 1 : -1);
        }
        if (priceCol < 0 || priceCol == dateCol) {
            throw new IllegalArgumentException("CSV : colonne de prix introuvable (attendu : Date, Close ou Adj Close)");
        }

        List<LocalDate> dates = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        int bad = 0;
        for (int i = header ? 1 : 0; i < lines.length; i++) {
            String[] cells = split(lines[i], sep);
            LocalDate date = cells.length > Math.max(dateCol, priceCol) ? parseDate(cells[dateCol]) : null;
            Double price = cells.length > Math.max(dateCol, priceCol) ? parsePrice(cells[priceCol], sep) : null;
            if (date == null || price == null) {
                if (!lines[i].isBlank()) bad++;
                continue;
            }
            dates.add(date);
            prices.add(price);
        }
        if (bad > lines.length / 5) {
            throw new IllegalArgumentException("CSV illisible : " + bad + " lignes invalides sur " + lines.length);
        }
        PriceSeries series = of(symbol, "CSV", dates, prices);
        if (series.size() < MIN_POINTS) {
            throw new IllegalArgumentException("CSV : " + series.size() + " jours valides, il en faut au moins " + MIN_POINTS);
        }
        return series;
    }

    private static char separator(String firstLine) {
        long semicolons = firstLine.chars().filter(c -> c == ';').count();
        long commas = firstLine.chars().filter(c -> c == ',').count();
        long tabs = firstLine.chars().filter(c -> c == '\t').count();
        if (tabs > semicolons && tabs > commas) return '\t';
        return semicolons > commas ? ';' : ',';
    }

    private static String[] split(String line, char sep) {
        String[] cells = line.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
        for (int i = 0; i < cells.length; i++) cells[i] = cells[i].strip().replace("\"", "");
        return cells;
    }

    /** Index de la premiere colonne dont l'en-tete contient un des mots, sinon {@code fallback}. */
    private static int indexOf(String[] head, int fallback, String... words) {
        for (int i = 0; i < head.length; i++) {
            String h = head[i].toLowerCase(Locale.ROOT);
            for (String w : words) if (h.contains(w)) return i;
        }
        return fallback;
    }

    /** Colonne dont l'en-tete contient TOUS les mots (ex : "adj" et "close"), sinon -1. */
    private static int indexOfAll(String[] head, String... words) {
        for (int i = 0; i < head.length; i++) {
            String h = head[i].toLowerCase(Locale.ROOT);
            if (java.util.Arrays.stream(words).allMatch(h::contains)) return i;
        }
        return -1;
    }

    private static int firstIndex(int... candidates) {
        for (int c : candidates) if (c >= 0) return c;
        return -1;
    }

    static LocalDate parseDate(String raw) {
        String s = raw.strip().replace("\"", "");
        if (s.length() > 10 && (s.charAt(10) == 'T' || s.charAt(10) == ' ')) s = s.substring(0, 10);
        for (DateTimeFormatter f : new DateTimeFormatter[] {DateTimeFormatter.ISO_LOCAL_DATE, DAY_FIRST, DAY_FIRST_DASH,
                YEAR_FIRST_SLASH}) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // format suivant
            }
        }
        return null;
    }

    private static Double parsePrice(String raw, char sep) {
        String s = raw.strip().replace(" ", "").replace(" ", "");
        if (s.isEmpty() || s.equalsIgnoreCase("null") || s.equalsIgnoreCase("nan")) return null;
        if (sep == ';' || (sep == '\t' && s.indexOf(',') >= 0 && s.indexOf('.') < 0)) s = s.replace(',', '.');
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
