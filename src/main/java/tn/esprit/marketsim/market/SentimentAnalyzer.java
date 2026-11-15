package tn.esprit.marketsim.market;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Module 5 — estimation lexicale de sentiment sur les actualités financières.
 *
 * <p>Estime le ton des titres financiers (en français et en anglais) à l'aide d'un
 * lexique financier spécialisé, pondère les termes contextuels (ex. hausses de taux),
 * et calcule un score normalisé entre -1.0 (très négatif) et +1.0 (très positif),
 * ainsi qu'un label catégoriel (POSITIVE, NEUTRAL, NEGATIVE).
 */
@Component
public class SentimentAnalyzer {

    public enum Label {
        POSITIVE,
        NEUTRAL,
        NEGATIVE
    }

    // Termes positifs en français et en anglais
    private static final Pattern POSITIVE_WORDS = Pattern.compile(
            "\\b(haussier|hausse|rebond|record|progress|progresse|gagne|bondit|grimpe|optimis|optimisme|" +
            "envol|sommet|soutien|accord|rallye|croissance|surpasse|depasse|benefice|benefices|" +
            "beat|beats|surge|surges|jump|jumps|rally|rallies|gain|gains|growth|bullish|optimism|" +
            "soar|soars|inflows|boost|breakthrough|profit|profits|outperform|outperforms|upgrade)\\b",
            Pattern.CASE_INSENSITIVE);

    // Termes négatifs en français et en anglais
    private static final Pattern NEGATIVE_WORDS = Pattern.compile(
            "\\b(baissier|baisse|chute|recul|krach|inquiet|inquiete|crise|plonge|perte|pertes|" +
            "recession|tension|menace|fraude|faillite|degringol|peur|effondr|sanction|sanctions|" +
            "drop|drops|fall|falls|plunge|plunges|slump|slumps|crash|crashes|loss|losses|bearish|" +
            "crisis|concern|concerns|fear|fears|pressure|under pressure|decline|declines|default|collapse|warning)\\b",
            Pattern.CASE_INSENSITIVE);

    // Contextes spécifiques : hausse des taux (négatif pour les marchés d'actions/crypto, à neutraliser si interprété naïvement comme positif)
    private static final Pattern RATE_HIKE_FR = Pattern.compile(
            "(hausses?|relev\\w*|augment\\w*)\\s+(?:a nouveau\\s+)?(?:de\\s+|des\\s+|du\\s+|ses\\s+|les\\s+|son\\s+|le\\s+)?taux(?:\\s+(?:d'interet|directeurs?))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE_HIKE_EN = Pattern.compile(
            "\\b(rate\\s+hike|rate\\s+hikes|hikes?\\s+rates?|raising\\s+rates?|tightening)\\b",
            Pattern.CASE_INSENSITIVE);

    // Contextes d'apaisement : pause dans les hausses de taux / baisse de l'inflation
    private static final Pattern RATE_PAUSE_EN = Pattern.compile(
            "\\b(pause\\s+in\\s+rate\\s+hikes|cooling\\s+inflation|inflation\\s+cools|disinflation)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Analyse un titre et produit un score de sentiment normalisé entre -1.0 et +1.0.
     *
     * @param headline titre ou extrait de l'actualité
     * @return score entre -1.0 (baissier) et +1.0 (haussier), 0.0 pour neutre
     */
    public double score(String headline) {
        if (headline == null || headline.isBlank()) {
            return 0.0;
        }

        String normalized = normalize(headline);

        // Détection de contexte spécial : pause / apaisement monétaire
        boolean easingContext = RATE_PAUSE_EN.matcher(normalized).find();

        // Neutraliser les mentions de hausse de taux avant de compter les occurrences de "hausse"
        String cleaned = RATE_HIKE_FR.matcher(normalized).replaceAll(" ");
        cleaned = RATE_HIKE_EN.matcher(cleaned).replaceAll(" ");

        long posCount = POSITIVE_WORDS.matcher(cleaned).results().count();
        long negCount = NEGATIVE_WORDS.matcher(cleaned).results().count();

        // Réinjecter l'impact du contexte
        if (RATE_HIKE_FR.matcher(normalized).find() || RATE_HIKE_EN.matcher(normalized).find()) {
            negCount++;
        }
        if (easingContext) {
            posCount++;
        }

        if (posCount == 0 && negCount == 0) {
            return 0.0;
        }

        double rawScore = (double) (posCount - negCount) / (posCount + negCount);
        return Math.round(rawScore * 100.0) / 100.0;
    }

    /**
     * Détermine le label à partir du score de sentiment.
     *
     * @param score score entre -1.0 et +1.0
     * @return POSITIVE si score > 0.15, NEGATIVE si score < -0.15, sinon NEUTRAL
     */
    public Label label(double score) {
        if (score > 0.15) {
            return Label.POSITIVE;
        }
        if (score < -0.15) {
            return Label.NEGATIVE;
        }
        return Label.NEUTRAL;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
