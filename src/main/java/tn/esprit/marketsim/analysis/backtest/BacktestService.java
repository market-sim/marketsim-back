package tn.esprit.marketsim.analysis.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.realdata.MarketDataService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.Strategy;
import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyRules;
import tn.esprit.marketsim.analysis.StrategyService;
import tn.esprit.marketsim.analysis.StrategyType;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestDetail;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestResult;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.CompareRequest;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.ComparisonCurve;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.ComparisonResult;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.Curves;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.Segment;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.StrategyComparison;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.StoredSettings;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Run;
import tn.esprit.marketsim.analysis.backtest.BacktestEngine.Trade;
import tn.esprit.marketsim.analysis.backtest.PerformanceMetrics.Metrics;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;
import tools.jackson.databind.json.JsonMapper;

/**
 * Module 3 : lance, enregistre et relit les backtests d'un utilisateur.
 *
 * <p>Chaine complete : strategie (type + parametres) -> historique reel (module 6) -> signaux
 * ({@link Signals}) -> simulation ({@link BacktestEngine}) -> metriques ({@link PerformanceMetrics}) -> base.
 * Une {@link IllegalArgumentException} devient un HTTP 400, une {@link NotFoundException} un HTTP 404.
 */
@Service
@RequiredArgsConstructor
public class BacktestService {

    /** Nombre minimum de seances pour qu'un backtest (ou une sous-periode) ait un sens. */
    static final int MIN_BARS = 30;

    private final BacktestRepository repository;
    private final StrategyService strategies;
    private final MarketDataService marketData;
    private final JsonMapper json;

    // ===================== Lancer =====================

    @Transactional
    public BacktestDetail run(BacktestRequest req, User user) {
        if (req.strategyId() == null) {
            throw new IllegalArgumentException("strategyId : la stratégie à tester est obligatoire");
        }
        if (req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol : l'actif est obligatoire");
        }
        Strategy strategy = strategies.findOne(req.strategyId(), user);   // 404 si elle n'est pas a moi
        if (strategy.getType() == null) {
            throw new IllegalArgumentException("Cette stratégie n'a pas de type : choisissez-en un "
                    + "(croisement de moyennes, RSI, MACD, Bollinger, buy & hold) avant de lancer un backtest");
        }
        StrategyParams params = StrategyRules.normalize(strategy.getType(), strategy.getParams());
        BacktestSettings settings = settings(req);                        // valide avant de telecharger
        PriceSeries series = marketData.series(req.symbol());             // 404 si l'actif est inconnu

        BacktestResult result = simulate(series, strategy.getType(), params, settings,
                req.from(), req.to(), req.splitDate());
        Metrics m = result.metrics();
        LocalDate[] dates = result.curves().dates();

        Backtest b = new Backtest();
        b.setOwner(user);
        b.setStrategy(strategy);
        b.setStrategyName(strategy.getName());
        b.setStrategyType(strategy.getType());
        b.setSymbol(series.symbol());
        b.setFromDate(dates[0]);
        b.setToDate(dates[dates.length - 1]);
        b.setSplitDate(req.splitDate());
        b.setTotalReturn(m.totalReturn());
        b.setAnnualizedReturn(m.annualizedReturn());
        b.setSharpe(m.sharpe());
        b.setMaxDrawdown(m.maxDrawdown());
        b.setTrades(m.tradeCount());
        b.setWinRate(m.winRate());
        b.setBuyHoldReturn(m.buyHoldReturn());
        b.setExcessReturn(m.excessReturn());
        b.setDataSource(series.source());
        b.setDataHash(series.hash());
        b.setSettingsJson(json.writeValueAsString(new StoredSettings(params, settings)));
        b.setResultJson(json.writeValueAsString(result));
        repository.save(b);

        updateStrategySummary(strategy, b);
        return detail(b, params, settings, result);
    }

    /** Compare deux a cinq strategies sur une seule serie de cours, sans enregistrer les resultats. */
    public ComparisonResult compare(CompareRequest req, User user) {
        if (req == null) {
            throw new IllegalArgumentException("La demande de comparaison est obligatoire");
        }
        if (req.strategyIds() == null || req.strategyIds().size() < 2 || req.strategyIds().size() > 5) {
            throw new IllegalArgumentException("strategyIds : choisissez entre 2 et 5 stratégies");
        }
        if (req.strategyIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("strategyIds : chaque identifiant doit être strictement positif");
        }
        if (req.strategyIds().stream().distinct().count() != req.strategyIds().size()) {
            throw new IllegalArgumentException("strategyIds : les stratégies doivent être distinctes");
        }
        if (req.symbol() == null || req.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol : l'actif est obligatoire");
        }

        Long firstId = req.strategyIds().get(0);
        BacktestSettings settings = settings(new BacktestRequest(firstId, req.symbol(), req.from(), req.to(),
                req.initialCapital(), req.feeRate(), req.slippage(), req.stopLoss(), req.takeProfit(),
                req.positionFraction(), req.splitDate()));

        List<Strategy> selected = new ArrayList<>(req.strategyIds().size());
        List<StrategyParams> params = new ArrayList<>(req.strategyIds().size());
        for (Long id : req.strategyIds()) {
            Strategy strategy = strategies.findOne(id, user);
            if (strategy.getType() == null) {
                throw new IllegalArgumentException("La stratégie « " + strategy.getName()
                        + " » n'a pas de type : choisissez sa règle avant de la comparer");
            }
            selected.add(strategy);
            params.add(StrategyRules.normalize(strategy.getType(), strategy.getParams()));
        }

        PriceSeries series = marketData.series(req.symbol());
        List<StrategyComparison> results = new ArrayList<>(selected.size());
        Curves referenceCurves = null;
        for (int i = 0; i < selected.size(); i++) {
            Strategy strategy = selected.get(i);
            BacktestResult result = simulate(series, strategy.getType(), params.get(i), settings,
                    req.from(), req.to(), req.splitDate());
            Curves curves = result.curves();
            if (referenceCurves == null) referenceCurves = curves;
            results.add(new StrategyComparison(strategy.getId(), strategy.getName(), strategy.getType(),
                    params.get(i), result.metrics(), result.train(), result.test(),
                    new ComparisonCurve(curves.dates(), curves.equity(), curves.drawdown())));
        }

        LocalDate[] dates = referenceCurves.dates();
        return new ComparisonResult(series.symbol(), dates[0], dates[dates.length - 1], req.splitDate(),
                series.source(), series.hash(), settings, dates, referenceCurves.buyHold(), results);
    }

    /**
     * Simulation complete sur une serie deja chargee (methode statique : testable sans base ni reseau).
     * Avec une date de coupure, le moteur tourne trois fois, chaque fois depuis le capital initial et sans
     * position : periode entiere, entrainement (avant la coupure), test (a partir de la coupure).
     */
    static BacktestResult simulate(PriceSeries series, StrategyType type, StrategyParams params,
                                   BacktestSettings settings, LocalDate from, LocalDate to, LocalDate split) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("La date de début doit précéder la date de fin");
        }
        LocalDate[] dates = series.dates();
        int lo = 0;
        while (lo < dates.length && from != null && dates[lo].isBefore(from)) lo++;
        int hi = dates.length - 1;
        while (hi >= 0 && to != null && dates[hi].isAfter(to)) hi--;
        if (lo > hi) {
            throw new IllegalArgumentException("Aucune donnée pour " + series.symbol() + " sur la période demandée");
        }

        Run run = runOn(series, type, params, settings, lo, hi, "Période");
        Segment train = null;
        Segment test = null;
        if (split != null) {
            if (!split.isAfter(dates[lo]) || split.isAfter(dates[hi])) {
                throw new IllegalArgumentException("La date de coupure (splitDate) doit être comprise dans la période du "
                        + "backtest, entre le " + dates[lo] + " et le " + dates[hi]);
            }
            int k = lo;                                  // premiere seance de la periode de test
            while (dates[k].isBefore(split)) k++;
            train = segment(runOn(series, type, params, settings, lo, k - 1, "Période d'entraînement"));
            test = segment(runOn(series, type, params, settings, k, hi, "Période de test"));
        }

        Curves curves = new Curves(run.dates(), Rounding.round(run.equity(), 2), Rounding.round(run.buyHold(), 2),
                Rounding.round(PerformanceMetrics.drawdownCurve(run.equity()), 6));
        List<Trade> trades = run.trades().stream().map(Trade::rounded).toList();
        return new BacktestResult(PerformanceMetrics.compute(run).rounded(), train, test, curves, trades);
    }

    /** Verifie que la fenetre [lo, hi] est exploitable, calcule les signaux et lance le moteur. */
    private static Run runOn(PriceSeries series, StrategyType type, StrategyParams params, BacktestSettings settings,
                             int lo, int hi, String label) {
        LocalDate[] dates = series.dates();
        int bars = hi - lo + 1;
        if (bars < MIN_BARS) {
            throw new IllegalArgumentException(label + " trop courte : " + bars + " séance(s)"
                    + (bars > 0 ? " du " + dates[lo] + " au " + dates[hi] : "")
                    + ", il en faut au moins " + MIN_BARS);
        }
        // Le premier signal possible est au jour d'indice warmup-1 (compte depuis le debut de l'historique)
        int warmup = Signals.warmup(type, params);
        int usable = hi - Math.max(lo, warmup - 1) + 1;
        if (usable < MIN_BARS) {
            throw new IllegalArgumentException(label + " : historique insuffisant. Cette stratégie a besoin de "
                    + warmup + " séances avant son premier signal ; il n'en reste que " + Math.max(usable, 0)
                    + " exploitable(s), il en faut au moins " + MIN_BARS);
        }
        Signal[] signals = Signals.compute(type, params, series.closes(), lo);
        return BacktestEngine.run(dates, series.closes(), signals, lo, hi, settings);
    }

    private static Segment segment(Run run) {
        LocalDate[] d = run.dates();
        return new Segment(d[0], d[d.length - 1], PerformanceMetrics.compute(run).rounded());
    }

    /** Reglages du backtest : valeurs par defaut puis controle des bornes. */
    static BacktestSettings settings(BacktestRequest req) {
        double capital = req.initialCapital() == null ? BacktestSettings.DEFAULT_CAPITAL : req.initialCapital();
        double fee = req.feeRate() == null ? BacktestSettings.DEFAULT_FEE_RATE : req.feeRate();
        double slippage = req.slippage() == null ? BacktestSettings.DEFAULT_SLIPPAGE : req.slippage();
        double fraction = req.positionFraction() == null
                ? BacktestSettings.DEFAULT_POSITION_FRACTION : req.positionFraction();
        // ecriture en "!(...)" pour refuser aussi NaN
        if (!(capital > 0 && capital <= 1e12)) {
            throw new IllegalArgumentException("initialCapital : le capital initial doit être strictement positif");
        }
        if (!(fee >= 0 && fee <= 0.05)) {
            throw new IllegalArgumentException("feeRate : les frais doivent être entre 0 et 0.05 (5 %) ; 0.001 = 0,1 %");
        }
        if (!(slippage >= 0 && slippage <= 0.05)) {
            throw new IllegalArgumentException("slippage : le glissement doit être entre 0 et 0.05 (5 %)");
        }
        if (req.stopLoss() != null && !(req.stopLoss() > 0 && req.stopLoss() < 1)) {
            throw new IllegalArgumentException("stopLoss : le stop-loss doit être entre 0 et 1 (0.05 = 5 %)");
        }
        if (req.takeProfit() != null && !(req.takeProfit() > 0 && req.takeProfit() <= 10)) {
            throw new IllegalArgumentException("takeProfit : le take-profit doit être entre 0 et 10 (0.10 = 10 %)");
        }
        if (!(fraction > 0 && fraction <= 1)) {
            throw new IllegalArgumentException(
                    "positionFraction : la part du capital engagée doit être supérieure à 0 et au plus 1 (1 = 100 %)");
        }
        return new BacktestSettings(capital, fee, slippage, req.stopLoss(), req.takeProfit(), fraction);
    }

    // ===================== Lire =====================

    /** Mes backtests, du plus recent au plus ancien ; {@code strategyId} (facultatif) filtre sur une strategie. */
    public List<BacktestSummary> findMine(User user, Long strategyId) {
        return repository.summaries(user.getId()).stream()
                .filter(s -> strategyId == null || strategyId.equals(s.strategyId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public BacktestDetail findOne(Long id, User user) {
        Backtest b = mine(id, user);
        StoredSettings stored = json.readValue(b.getSettingsJson(), StoredSettings.class);
        BacktestResult result = json.readValue(b.getResultJson(), BacktestResult.class);
        return detail(b, stored.params(), stored.settings(), result);
    }

    // ===================== Supprimer =====================

    /** Supprime un backtest, puis remet le resume de la strategie sur son backtest restant le plus recent. */
    @Transactional
    public void delete(Long id, User user) {
        Backtest b = mine(id, user);
        Strategy strategy = b.getStrategy();
        repository.delete(b);
        Backtest latest = repository.findFirstByStrategyIdOrderByCreatedAtDescIdDesc(strategy.getId()).orElse(null);
        updateStrategySummary(strategy, latest);
    }

    // ===================== Outils =====================

    /** Un utilisateur ne voit que ses propres backtests : celui d'un autre est "introuvable". */
    private Backtest mine(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Backtest introuvable"));
    }

    /**
     * Recopie dans la strategie le resume de son dernier backtest (null = plus aucun backtest).
     * winRate et maxDrawdown y sont en pourcentage, arrondis a 2 decimales (affichage de la liste).
     */
    private void updateStrategySummary(Strategy strategy, Backtest last) {
        if (last == null) {
            strategies.recordBacktest(strategy, null, null, null, null);
            return;
        }
        strategies.recordBacktest(strategy,
                Rounding.round(last.getSharpe(), 2),
                last.getWinRate() == null ? null : Rounding.round(last.getWinRate() * 100, 2),
                last.getMaxDrawdown() == null ? null : Rounding.round(last.getMaxDrawdown() * 100, 2),
                last.getTrades());
    }

    private static BacktestDetail detail(Backtest b, StrategyParams params, BacktestSettings settings,
                                         BacktestResult r) {
        return new BacktestDetail(b.getId(), b.getStrategy().getId(), b.getStrategyName(), b.getStrategyType(),
                params, b.getSymbol(), b.getFromDate(), b.getToDate(), b.getSplitDate(), b.getCreatedAt(),
                b.getDataSource(), b.getDataHash(), settings, r.metrics(), r.train(), r.test(), r.curves(), r.trades());
    }
}
