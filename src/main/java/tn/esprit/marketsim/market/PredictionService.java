package tn.esprit.marketsim.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.common.InsufficientDataException;
import tn.esprit.marketsim.common.ModelUnavailableException;
import tn.esprit.marketsim.common.NotFoundException;

/**
 * Module 5 — Génération, persistance et résolution des prédictions de tendance.
 *
 * <p>Les prédictions sont basées sur des <b>données historiques réelles</b> (Yahoo/CSV)
 * et produites par un service Python isolé d'ensemble (ARIMA + Holt + naive) qui sélectionne
 * automatiquement le meilleur modèle par walk-forward MAE/RMSE. Spring Boot reste responsable
 * de la logique métier et de la persistance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionService {

    private final MarketPredictionRepository repository;
    private final AssetRepository assetRepository;
    private final MarketDataService marketDataService;
    private final ForecastClient forecastClient;
    private final FinancialSentimentService sentimentService;

    private static final int    DEFAULT_HORIZON = 5;
    private static final int    MIN_HORIZON     = 1;
    private static final int    MAX_HORIZON     = 30;
    private static final int    MIN_POINTS      = 60;

    // ─── Lecture ──────────────────────────────────────────────────────────

    public List<MarketPrediction> findAll() {
        return repository.findAllByOrderByGeneratedAtDesc();
    }

    public List<MarketPrediction> findBySymbol(String symbol) {
        return repository.findBySymbolIgnoreCaseOrderByGeneratedAtDesc(symbol);
    }

    public MarketPrediction findOne(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Prédiction introuvable"));
    }

    public PredictionEvaluation evaluation(Long id) {
        MarketPrediction p = findOne(id);
        String status = (p.getStatus() == null) ? "GENERATED" : p.getStatus().name();
        return new PredictionEvaluation(
                p.getId(), p.getModelName(), p.getModelVersion(),
                p.getEvaluationPoints(), p.getMae(), p.getRmse(), p.getMape(), status);
    }

    public List<PredictionModelInfo> models() {
        return List.of(new PredictionModelInfo(
                "ensemble-arima-holt-naive", "Ensemble (ARIMA + Holt + naive)", "ensemble-v1",
                "Service Python isolé : sélection automatique par walk-forward MAE/RMSE parmi ARIMA, Holt et naïf. "
                        + "Données historiques réelles uniquement.", forecastClient.isAvailable()));
    }

    // ─── Génération ───────────────────────────────────────────────────────

    public MarketPrediction generateFor(String symbol) {
        return generateFor(symbol, null);
    }

    public MarketPrediction generateFor(String symbol, Integer horizon) {
        int h = (horizon == null) ? DEFAULT_HORIZON : horizon;
        if (h < MIN_HORIZON || h > MAX_HORIZON) {
            throw new IllegalArgumentException("Horizon invalide : doit être entre "
                    + MIN_HORIZON + " et " + MAX_HORIZON + " jours");
        }

        tn.esprit.marketsim.market.Asset asset = assetRepository.findAll().stream()
                .filter(a -> a.getSymbol().equalsIgnoreCase(symbol))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Actif introuvable : " + symbol));

        PriceSeries series;
        try {
            series = marketDataService.series(asset.getSymbol());
        } catch (RuntimeException e) {
            throw new ModelUnavailableException("Données historiques indisponibles pour " + asset.getSymbol()
                    + " : " + e.getMessage(), e);
        }
        if (series == null || series.size() == 0) {
            throw new ModelUnavailableException("Aucune donnée historique pour " + asset.getSymbol());
        }

        double[] y = series.closes();
        int n = y.length;
        if (n < MIN_POINTS) {
            throw new InsufficientDataException("Historique insuffisant pour " + asset.getSymbol()
                    + " : " + n + " points disponibles, il en faut au moins " + (MIN_POINTS + 1));
        }

        // Limite la série envoyée au service (fenêtre récente) pour garder des temps de réponse acceptables
        int window = Math.min(n, 200);
        List<Double> prices = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (int i = n - window; i < n; i++) {
            prices.add(y[i]);
            dates.add(series.dates()[i].toString());
        }

        // Score de sentiment FinBERT (variable exogène) — peut être null
        Double sentimentScore = null;
        try {
            var summary = sentimentService.summary(asset.getSymbol(), 30);
            sentimentScore = summary.sentimentScore();
        } catch (Exception e) {
            log.warn("Sentiment indisponible pour {} : {}", asset.getSymbol(), e.getMessage());
        }
        double sentimentInput = (sentimentScore == null) ? 0.0 : sentimentScore;

        JsonNode result;
        try {
            result = forecastClient.forecast(prices, dates, h, sentimentInput);
        } catch (RuntimeException e) {
            throw new ModelUnavailableException("Service de prévision indisponible : " + e.getMessage(), e);
        }
        if (result == null || !"ANALYZED".equals(result.path("status").asText())) {
            throw new ModelUnavailableException("Prévision indisponible : " + result);
        }

        double currentPrice = y[n - 1];
        double targetPrice = result.path("forecast").get(h - 1).path("value").asDouble();
        if (!Double.isFinite(targetPrice) || targetPrice <= 0) {
            throw new ModelUnavailableException("Prédiction numérique invalide pour " + asset.getSymbol());
        }

        MarketPrediction.Direction direction;
        double ratio = targetPrice / currentPrice;
        if (ratio > 1.001) direction = MarketPrediction.Direction.UP;
        else if (ratio < 0.999) direction = MarketPrediction.Direction.DOWN;
        else direction = MarketPrediction.Direction.FLAT;

        // metrics from selected candidate
        JsonNode metrics = result.path("metrics");
        Double mae = metrics.has("mae") ? metrics.get("mae").asDouble() : null;
        Double rmse = metrics.has("rmse") ? metrics.get("rmse").asDouble() : null;
        // MAPE approximated from residuals
        JsonNode residualsNode = result.path("residuals");
        Double mape = null;
        if (residualsNode.isArray() && residualsNode.size() > 0) {
            double sum = 0; int cnt = 0;
            for (JsonNode r : residualsNode) {
                // residual stored as price diff; convert to pct using current price proxy
            }
            // compute MAPE from residuals / price
            double s = 0; int c = 0;
            for (int i = 0; i < residualsNode.size(); i++) {
                double res = residualsNode.get(i).asDouble();
                double actual = (i + 1 < n) ? y[i + 1] : y[n - 1];
                if (actual != 0.0) { s += Math.abs(res / actual); c++; }
            }
            if (c > 0) mape = (s / c) * 100.0;
        }

        // confidence from in-sample RMSE proxy (provided by service candidate rmse)
        double cv = (currentPrice > 0 && rmse != null) ? (rmse / currentPrice) : 1.0;
        double confidence = 1.0 / (1.0 + cv);
        confidence = Math.max(0.1, Math.min(0.95, confidence));

        // residuals for fan chart
        List<Double> residuals = new ArrayList<>();
        if (residualsNode.isArray()) {
            for (JsonNode r : residualsNode) residuals.add(r.asDouble());
        }
        if (residuals.isEmpty()) residuals.add(0.0);

        // bootstrap fan chart
        int hz = h;
        int B = 200;
        double[][] paths = new double[B][hz];
        java.util.Random rng = new java.util.Random(42L);
        double last = y[n - 1];
        // simple random-walk bootstrap using residuals
        for (int b = 0; b < B; b++) {
            double cur = last;
            for (int k = 0; k < hz; k++) {
                double e = residuals.get(rng.nextInt(residuals.size()));
                cur = cur + e;
                if (cur <= 0) cur = 0.01;
                paths[b][k] = cur;
            }
        }
        double[] fanP10 = new double[hz];
        double[] fanP90 = new double[hz];
        for (int k = 0; k < hz; k++) {
            double[] col = new double[B];
            for (int b = 0; b < B; b++) col[b] = paths[b][k];
            java.util.Arrays.sort(col);
            fanP10[k] = col[(int) Math.round(0.10 * (B - 1))];
            fanP90[k] = col[(int) Math.round(0.90 * (B - 1))];
        }
        StringBuilder fanSb = new StringBuilder("[");
        for (int k = 0; k < hz; k++) {
            if (k > 0) fanSb.append(",");
            fanSb.append(String.format(java.util.Locale.ROOT,
                    "{\"day\":%d,\"p10\":%.6f,\"p50\":%.6f,\"p90\":%.6f}",
                    k + 1, fanP10[k], targetPrice, fanP90[k]));
        }
        fanSb.append("]");
        String fanJson = fanSb.toString();

        // candidate metrics json
        JsonNode candidates = result.path("candidates");
        String candidateJson = (candidates.isArray()) ? candidates.toString() : "[]";

        LocalDate lastDate = series.dates()[n - 1];
        LocalDate firstDate = series.dates()[n - window];
        Instant dataAsOf = lastDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = Instant.now();

        String modelName = result.path("selectedModel").asText("ensemble");
        String modelVersion = result.path("modelVersion").asText("ensemble-v1");

        MarketPrediction prediction = new MarketPrediction();
        prediction.setSymbol(asset.getSymbol());
        prediction.setDirection(direction);
        prediction.setConfidence(confidence);
        prediction.setCurrentPrice(BigDecimal.valueOf(currentPrice).setScale(6, RoundingMode.HALF_UP));
        prediction.setTargetPrice(BigDecimal.valueOf(targetPrice).setScale(6, RoundingMode.HALF_UP));
        prediction.setHorizonDays(h);
        prediction.setGeneratedAt(now);
        prediction.setDataAsOf(dataAsOf);
        prediction.setModelName(modelName);
        prediction.setModelVersion(modelVersion);
        prediction.setDataSource(series.source());
        prediction.setDataHash(series.hash());
        prediction.setDataFrom(firstDate);
        prediction.setDataTo(lastDate);
        prediction.setDataPoints(n);
        prediction.setMae(mae);
        prediction.setRmse(rmse);
        prediction.setMape(mape);
        prediction.setEvaluationPoints(Math.max(1, n / 5));
        prediction.setFanP10(fanP10[hz - 1]);
        prediction.setFanP90(fanP90[hz - 1]);
        prediction.setFanJson(fanJson);
        prediction.setStatus(MarketPrediction.PredictionStatus.GENERATED);

        // store candidate metrics in fanJson? reuse candidateMetricsJson field (add to entity)
        prediction.setCandidateMetricsJson(candidateJson);
        prediction.setSentimentScore(sentimentScore);
        prediction.setSentimentContribution(result.has("sentimentContribution") ? result.get("sentimentContribution").asDouble() : null);

        return repository.save(prediction);
    }

    // ─── Backtesting & calibration ────────────────────────────────────────

    public PredictionBacktest backtest(String symbol) {
        List<MarketPrediction> list = (symbol == null || symbol.isBlank())
                ? repository.findAllByOrderByGeneratedAtDesc()
                : repository.findBySymbolIgnoreCaseOrderByGeneratedAtDesc(symbol);

        PredictionBacktest bt = new PredictionBacktest();
        bt.setTotal(list.size());
        int resolved = 0, correct = 0, pending = 0;
        double brierSum = 0;
        java.util.Map<String, PredictionBacktest.DirectionStat> byDir = new java.util.HashMap<>();
        // calibration bins of width 0.1
        int bins = 10;
        int[] binCount = new int[bins];
        double[] binConfSum = new double[bins];
        int[] binCorrect = new int[bins];

        for (MarketPrediction p : list) {
            if (p.getOutcome() == null) { pending++; continue; }
            resolved++;
            boolean ok = (p.getOutcome() == MarketPrediction.Outcome.CORRECT);
            if (ok) correct++;
            double conf = (p.getConfidence() == null) ? 0.0 : p.getConfidence();
            brierSum += (conf - (ok ? 1.0 : 0.0)) * (conf - (ok ? 1.0 : 0.0));

            String dir = p.getDirection().name();
            PredictionBacktest.DirectionStat ds = byDir.computeIfAbsent(dir, k -> new PredictionBacktest.DirectionStat());
            ds.setTotal(ds.getTotal() + 1);
            if (ok) ds.setCorrect(ds.getCorrect() + 1);

            int bi = (int) Math.min(bins - 1, Math.max(0, conf * bins));
            if (bi == bins) bi = bins - 1;
            binCount[bi]++;
            binConfSum[bi] += conf;
            if (ok) binCorrect[bi]++;
        }

        bt.setResolved(resolved);
        bt.setPending(pending);
        bt.setSuccessRate(resolved > 0 ? (double) correct / resolved : null);
        bt.setBrierScore(resolved > 0 ? brierSum / resolved : null);

        for (PredictionBacktest.DirectionStat ds : byDir.values()) {
            ds.setRate(ds.getTotal() > 0 ? (double) ds.getCorrect() / ds.getTotal() : 0.0);
        }
        bt.setByDirection(byDir);

        List<PredictionBacktest.CalibrationBin> cal = new ArrayList<>();
        for (int i = 0; i < bins; i++) {
            if (binCount[i] == 0) continue;
            PredictionBacktest.CalibrationBin cb = new PredictionBacktest.CalibrationBin();
            cb.setFrom(i / 10.0);
            cb.setTo((i + 1) / 10.0);
            cb.setCount(binCount[i]);
            cb.setAvgConfidence(binConfSum[i] / binCount[i]);
            cb.setActualRate((double) binCorrect[i] / binCount[i]);
            cal.add(cb);
        }
        bt.setCalibration(cal);
        return bt;
    }

    // ─── Résolution ───────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000, initialDelay = 10_000)
    public void resolveExpired() {
        List<MarketPrediction> pending = repository.findByOutcomeIsNull();
        if (pending.isEmpty()) return;
        Instant now = Instant.now();
        for (MarketPrediction pred : pending) {
            Instant expiry = pred.getGeneratedAt().plus(pred.getHorizonDays(), ChronoUnit.DAYS);
            if (expiry.isBefore(now)) resolve(pred, now);
        }
    }

    private void resolve(MarketPrediction pred, Instant now) {
        tn.esprit.marketsim.market.Asset asset = assetRepository.findAll().stream()
                .filter(a -> a.getSymbol().equalsIgnoreCase(pred.getSymbol()))
                .findFirst().orElse(null);
        if (asset == null) { log.warn("Résolution ignorée : actif {} introuvable", pred.getSymbol()); return; }
        double originalPrice = pred.getCurrentPrice().doubleValue();
        double actualPrice = asset.getPrice().doubleValue();
        double change = (actualPrice - originalPrice) / originalPrice;
        boolean correct = switch (pred.getDirection()) {
            case UP -> change > 0;
            case DOWN -> change < 0;
            case FLAT -> Math.abs(change) < 0.02;
        };
        pred.setOutcome(correct ? MarketPrediction.Outcome.CORRECT : MarketPrediction.Outcome.INCORRECT);
        pred.setResolvedAt(now);
        pred.setStatus(MarketPrediction.PredictionStatus.RESOLVED);
        repository.save(pred);
    }
}
