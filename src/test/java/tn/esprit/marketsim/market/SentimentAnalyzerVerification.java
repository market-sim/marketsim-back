package tn.esprit.marketsim.market;

public class SentimentAnalyzerVerification {

    public static void main(String[] args) {
        SentimentAnalyzer analyzer = new SentimentAnalyzer();
        int passed = 0;
        int total = 0;

        total++;
        double scoreEn = analyzer.score("Apple beats earnings expectations, raises full-year guidance");
        check(scoreEn > 0, "Apple beats expectations should be positive, got " + scoreEn);
        check(analyzer.label(scoreEn) == SentimentAnalyzer.Label.POSITIVE, "Label should be POSITIVE");
        passed++;

        total++;
        double scoreBtc = analyzer.score("Bitcoin ETF inflows hit record on institutional demand surge");
        check(scoreBtc > 0, "BTC ETF inflows should be positive, got " + scoreBtc);
        check(analyzer.label(scoreBtc) == SentimentAnalyzer.Label.POSITIVE, "Label should be POSITIVE");
        passed++;

        total++;
        double scoreFr = analyzer.score("Le CAC 40 rebondit fortement et signe un nouveau sommet");
        check(scoreFr > 0, "CAC 40 rebondit should be positive, got " + scoreFr);
        check(analyzer.label(scoreFr) == SentimentAnalyzer.Label.POSITIVE, "Label should be POSITIVE");
        passed++;

        total++;
        double scoreOil = analyzer.score("Oil inventories rise unexpectedly, prices under pressure");
        check(scoreOil < 0, "Oil pressure should be negative, got " + scoreOil);
        check(analyzer.label(scoreOil) == SentimentAnalyzer.Label.NEGATIVE, "Label should be NEGATIVE");
        passed++;

        total++;
        double scoreChute = analyzer.score("Chute brutale à la Bourse de Paris : crise et krach redoutés");
        check(scoreChute < 0, "Chute brutale should be negative, got " + scoreChute);
        check(analyzer.label(scoreChute) == SentimentAnalyzer.Label.NEGATIVE, "Label should be NEGATIVE");
        passed++;

        total++;
        double scoreHikeFr = analyzer.score("La Fed procède à une nouvelle hausse des taux directeurs");
        check(scoreHikeFr <= 0, "Hausse des taux should not be positive, got " + scoreHikeFr);
        passed++;

        total++;
        double scoreHikeEn = analyzer.score("Federal Reserve signals another aggressive rate hike");
        check(scoreHikeEn < 0, "Aggressive rate hike should be negative, got " + scoreHikeEn);
        check(analyzer.label(scoreHikeEn) == SentimentAnalyzer.Label.NEGATIVE, "Label should be NEGATIVE");
        passed++;

        total++;
        double scoreNeutral = analyzer.score("La BCE publie son rapport trimestriel sur les flux de capitaux");
        check(analyzer.label(scoreNeutral) == SentimentAnalyzer.Label.NEUTRAL, "Report publication should be NEUTRAL");
        check(analyzer.score(null) == 0.0, "null should return 0.0");
        check(analyzer.score("   ") == 0.0, "blank should return 0.0");
        passed++;

        System.out.println("ALL CHECKS PASSED: " + passed + "/" + total);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("CHECK FAILED: " + message);
        }
    }
}
