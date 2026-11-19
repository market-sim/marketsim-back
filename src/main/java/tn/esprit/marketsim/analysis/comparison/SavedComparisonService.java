package tn.esprit.marketsim.analysis.comparison;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.Strategy;
import tn.esprit.marketsim.analysis.StrategyParams;
import tn.esprit.marketsim.analysis.StrategyRules;
import tn.esprit.marketsim.analysis.StrategyService;
import tn.esprit.marketsim.agent.realdata.PriceSeries;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.ComparisonResult;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.DataCheckResult;
import tn.esprit.marketsim.analysis.backtest.DataCheck;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.StrategyComparison;
import tn.esprit.marketsim.analysis.backtest.BacktestService;
import tn.esprit.marketsim.analysis.backtest.PerformanceMetrics.Metrics;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.ComparisonDetail;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.ComparisonSummary;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.RenameRequest;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.SaveComparisonRequest;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.StrategySnapshot;
import tn.esprit.marketsim.analysis.comparison.ComparisonDtos.StrategyStatus;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.user.User;
import tools.jackson.databind.json.JsonMapper;

/**
 * Module 3 : historique des comparaisons de strategies (enregistrer, lister, relire, renommer, supprimer).
 *
 * <p>Regle principale : le serveur ne fait jamais confiance a un resultat envoye par le front. Pour enregistrer,
 * il relance lui-meme la comparaison avec le moteur existant ({@link BacktestService#compare}) et garde ce
 * resultat-la. Une {@link IllegalArgumentException} devient un HTTP 400, une {@link NotFoundException} un 404.
 */
@Service
@RequiredArgsConstructor
public class SavedComparisonService {

    /** Longueur maximale du nom d'une comparaison. */
    static final int NAME_MAX = 100;

    private final SavedComparisonRepository repository;
    private final BacktestService backtests;
    private final StrategyService strategies;
    private final JsonMapper json;

    // ===================== Enregistrer =====================

    /** Relance la comparaison demandee, puis l'enregistre dans l'historique de l'utilisateur. */
    @Transactional
    public ComparisonDetail save(SaveComparisonRequest req, User user) {
        if (req == null) {
            throw new IllegalArgumentException("La demande d'enregistrement est obligatoire");
        }
        String name = validName(req.name());
        ComparisonRanking rankBy = ranking(req.rankBy());

        // calcul refait ici, avec les strategies et les cours actuels : memes controles que la comparaison a la demande
        ComparisonResult result = backtests.compare(req.request(), user);
        List<StrategySnapshot> snapshots = result.strategies().stream().map(SavedComparisonService::snapshot).toList();

        SavedComparison c = new SavedComparison();
        c.setOwner(user);
        c.setName(name);
        c.setSymbol(result.symbol());
        c.setFromDate(result.from());
        c.setToDate(result.to());
        c.setSplitDate(result.splitDate());
        c.setRankBy(rankBy);
        c.setBuyHoldReturn(result.strategies().get(0).metrics().buyHoldReturn());
        c.setDataSource(result.dataSource());
        c.setDataHash(result.dataHash());
        c.setPeriodHash(result.periodHash());
        c.setStrategiesJson(json.writeValueAsString(snapshots));
        c.setResultJson(json.writeValueAsString(result));
        repository.save(c);

        return new ComparisonDetail(c.getId(), c.getName(), c.getRankBy(), c.getCreatedAt(),
                statuses(snapshots, user), result);
    }

    // ===================== Lire =====================

    /** Mes comparaisons, de la plus recente a la plus ancienne, sans les courbes. */
    @Transactional(readOnly = true)
    public List<ComparisonSummary> findMine(User user) {
        Map<Long, Strategy> current = currentStrategies(user);    // une seule lecture des strategies pour toute la liste
        return repository.rows(user.getId()).stream().map(row -> {
            List<StrategySnapshot> snapshots = readSnapshots(row.strategiesJson());
            return new ComparisonSummary(row.id(), row.name(), row.symbol(), row.from(), row.to(), row.splitDate(),
                    row.rankBy(), row.createdAt(), row.dataSource(), row.dataHash(), row.buyHoldReturn(),
                    snapshots, statuses(snapshots, current));
        }).toList();
    }

    /** Une comparaison complete (courbes, metriques, buy & hold), telle qu'elle a ete enregistree. */
    @Transactional(readOnly = true)
    public ComparisonDetail findOne(Long id, User user) {
        SavedComparison c = mine(id, user);
        ComparisonResult result = json.readValue(c.getResultJson(), ComparisonResult.class);
        return new ComparisonDetail(c.getId(), c.getName(), c.getRankBy(), c.getCreatedAt(),
                statuses(readSnapshots(c.getStrategiesJson()), user), result);
    }

    /**
     * Les cours de la periode comparee sont-ils encore les memes ? (voir {@link DataCheck}). Une ancienne
     * comparaison, enregistree sans empreinte de periode, est « non verifiable ».
     */
    @Transactional(readOnly = true)
    public DataCheckResult dataCheck(Long id, User user) {
        SavedComparison c = mine(id, user);
        PriceSeries current = backtests.currentPrices(c.getSymbol(), c.getPeriodHash());
        return DataCheck.check(c.getPeriodHash(), c.getFromDate(), c.getToDate(), current);
    }

    // ===================== Modifier / supprimer =====================

    /** Renomme une comparaison ; renvoie son resume a jour. */
    @Transactional
    public ComparisonSummary rename(Long id, RenameRequest req, User user) {
        String name = validName(req == null ? null : req.name());
        SavedComparison c = mine(id, user);
        c.setName(name);
        repository.save(c);
        List<StrategySnapshot> snapshots = readSnapshots(c.getStrategiesJson());
        return new ComparisonSummary(c.getId(), c.getName(), c.getSymbol(), c.getFromDate(), c.getToDate(),
                c.getSplitDate(), c.getRankBy(), c.getCreatedAt(), c.getDataSource(), c.getDataHash(),
                c.getBuyHoldReturn(), snapshots, statuses(snapshots, user));
    }

    @Transactional
    public void delete(Long id, User user) {
        repository.delete(mine(id, user));
    }

    // ===================== Administration (routes reservees a l'ADMIN) =====================

    /**
     * Une comparaison de n'importe quel utilisateur, en lecture seule. L'etat des strategies est calcule par
     * rapport aux strategies de son proprietaire.
     */
    @Transactional(readOnly = true)
    public ComparisonDetail findAny(Long id) {
        SavedComparison c = any(id);
        ComparisonResult result = json.readValue(c.getResultJson(), ComparisonResult.class);
        return new ComparisonDetail(c.getId(), c.getName(), c.getRankBy(), c.getCreatedAt(),
                statuses(readSnapshots(c.getStrategiesJson()), c.getOwner()), result);
    }

    @Transactional
    public void deleteAny(Long id) {
        repository.delete(any(id));
    }

    private SavedComparison any(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Comparaison introuvable"));
    }

    /** Instantane des strategies d'une ligne (pour les listes de l'administration). */
    public List<StrategySnapshot> snapshots(String strategiesJson) {
        return readSnapshots(strategiesJson);
    }

    // ===================== Outils =====================

    /** Un utilisateur ne voit que ses propres comparaisons : celle d'un autre est "introuvable". */
    private SavedComparison mine(Long id, User user) {
        return repository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new NotFoundException("Comparaison introuvable"));
    }

    /** Nom obligatoire, sans les espaces autour, {@value #NAME_MAX} caracteres au plus. */
    static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name : le nom de la comparaison est obligatoire");
        }
        if (name.length() > NAME_MAX) {
            throw new IllegalArgumentException("name : " + NAME_MAX + " caractères au maximum");
        }
        return name;
    }

    /** Critere de classement : Sharpe par defaut, sinon l'une des valeurs de {@link ComparisonRanking}. */
    static ComparisonRanking ranking(String raw) {
        if (raw == null || raw.isBlank()) {
            return ComparisonRanking.SHARPE;
        }
        try {
            return ComparisonRanking.valueOf(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rankBy : critère inconnu « " + raw.strip()
                    + " » (SHARPE, ANNUALIZED_RETURN ou MAX_DRAWDOWN)");
        }
    }

    /** Copie d'une strategie comparee : identite, regle et metriques principales de la periode complete. */
    private static StrategySnapshot snapshot(StrategyComparison s) {
        Metrics m = s.metrics();
        return new StrategySnapshot(s.strategyId(), s.strategyName(), s.strategyType(), s.params(),
                m.totalReturn(), m.annualizedReturn(), m.sharpe(), m.maxDrawdown(), m.tradeCount());
    }

    private List<StrategySnapshot> readSnapshots(String raw) {
        return Arrays.asList(json.readValue(raw, StrategySnapshot[].class));
    }

    private Map<Long, Strategy> currentStrategies(User user) {
        return strategies.findMine(user).stream()
                .filter(s -> s.getId() != null)
                .collect(Collectors.toMap(Strategy::getId, Function.identity()));
    }

    private List<StrategyStatus> statuses(List<StrategySnapshot> snapshots, User user) {
        return statuses(snapshots, currentStrategies(user));
    }

    /** Etat de chaque strategie comparee par rapport a ce qu'elle est aujourd'hui (meme ordre que l'instantane). */
    static List<StrategyStatus> statuses(List<StrategySnapshot> snapshots, Map<Long, Strategy> current) {
        return snapshots.stream().map(s -> status(s, current.get(s.strategyId()))).toList();
    }

    private static StrategyStatus status(StrategySnapshot saved, Strategy now) {
        if (now == null) {
            return StrategyStatus.DELETED;
        }
        if (now.getType() != saved.type()) {
            return StrategyStatus.MODIFIED;
        }
        // parametres compares une fois completes par les regles du type (comme au moment de la comparaison)
        StrategyParams params;
        try {
            params = StrategyRules.normalize(now.getType(), now.getParams());
        } catch (IllegalArgumentException e) {
            return StrategyStatus.MODIFIED;
        }
        return sameParams(params, saved.params()) ? StrategyStatus.CURRENT : StrategyStatus.MODIFIED;
    }

    private static boolean sameParams(StrategyParams a, StrategyParams b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.getFastPeriod(), b.getFastPeriod())
                && Objects.equals(a.getSlowPeriod(), b.getSlowPeriod())
                && Objects.equals(a.getSignalPeriod(), b.getSignalPeriod())
                && Objects.equals(a.getPeriod(), b.getPeriod())
                && Objects.equals(a.getBuyBelow(), b.getBuyBelow())
                && Objects.equals(a.getSellAbove(), b.getSellAbove())
                && Objects.equals(a.getBandK(), b.getBandK());
    }
}
