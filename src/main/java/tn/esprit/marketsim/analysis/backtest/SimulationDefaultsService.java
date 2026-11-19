package tn.esprit.marketsim.analysis.backtest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.analysis.backtest.BacktestDtos.BacktestRequest;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : parametres par defaut des simulations. Lus par tous (formulaires preremplis, valeurs utilisees
 * quand l'utilisateur n'en donne pas), modifies par l'administrateur seulement (route protegee par
 * {@code @PreAuthorize} dans le controleur d'administration).
 *
 * <p>Validation : exactement celle du backtest ({@link BacktestService#settings(BacktestRequest, BacktestSettings)}).
 * Chaque champ modifie laisse une trace ({@link SimulationDefaultsChange}) : qui, quand, avant, apres.
 */
@Service
@RequiredArgsConstructor
public class SimulationDefaultsService {

    /** Nombre maximal de traces renvoyees par {@link #history}. */
    static final int HISTORY_MAX = 200;

    private final SimulationDefaultsRepository repository;
    private final SimulationDefaultsChangeRepository changes;

    /** Valeurs a saisir pour modifier les parametres par defaut ; stop-loss et take-profit : null ou 0 = aucun. */
    public record DefaultsRequest(Double initialCapital, Double feeRate, Double slippage, Double stopLoss,
                                  Double takeProfit, Double positionFraction, Double riskFreeRate) {
    }

    /**
     * Parametres actuels, valeurs d'origine et derniere modification.
     *
     * @param updatedAt null tant que l'administrateur n'a rien modifie
     */
    public record DefaultsView(BacktestSettings current, BacktestSettings original, LocalDateTime updatedAt,
                               String updatedBy) {
    }

    /** Une trace de modification. */
    public record ChangeView(Long id, LocalDateTime changedAt, String changedBy, String field, String oldValue,
                             String newValue) {
    }

    // ===================== Lire =====================

    /** Parametres utilises quand une demande de backtest ou de comparaison n'en donne pas. */
    @Transactional(readOnly = true)
    public BacktestSettings current() {
        return repository.findById(SimulationDefaults.ID).map(SimulationDefaults::toSettings)
                .orElse(BacktestSettings.BUILT_IN);
    }

    @Transactional(readOnly = true)
    public DefaultsView view() {
        SimulationDefaults row = repository.findById(SimulationDefaults.ID).orElse(null);
        return new DefaultsView(row == null ? BacktestSettings.BUILT_IN : row.toSettings(), BacktestSettings.BUILT_IN,
                row == null ? null : row.getUpdatedAt(), row == null ? null : row.getUpdatedBy());
    }

    /** Dernieres modifications, de la plus recente a la plus ancienne. */
    @Transactional(readOnly = true)
    public List<ChangeView> history() {
        return changes.findAllByOrderByChangedAtDescIdDesc(PageRequest.of(0, HISTORY_MAX)).stream()
                .map(c -> new ChangeView(c.getId(), c.getChangedAt(), c.getChangedByEmail(), c.getField(),
                        c.getOldValue(), c.getNewValue()))
                .toList();
    }

    // ===================== Modifier =====================

    /** Remplace les parametres par defaut ; ne trace que les champs qui changent vraiment. */
    @Transactional
    public DefaultsView update(DefaultsRequest req, User admin) {
        BacktestSettings next = validate(req);
        SimulationDefaults row = repository.findById(SimulationDefaults.ID).orElseGet(SimulationDefaultsService::originalRow);
        BacktestSettings before = row.toSettings();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        List<SimulationDefaultsChange> log = new ArrayList<>();
        trace(log, "initialCapital", before.initialCapital(), next.initialCapital(), now, admin);
        trace(log, "feeRate", before.feeRate(), next.feeRate(), now, admin);
        trace(log, "slippage", before.slippage(), next.slippage(), now, admin);
        trace(log, "stopLoss", before.stopLoss(), next.stopLoss(), now, admin);
        trace(log, "takeProfit", before.takeProfit(), next.takeProfit(), now, admin);
        trace(log, "positionFraction", before.positionFraction(), next.positionFraction(), now, admin);
        trace(log, "riskFreeRate", before.riskFreeRate(), next.riskFreeRate(), now, admin);
        if (log.isEmpty()) {
            return view();          // rien n'a change : ni trace, ni date de modification
        }

        row.setInitialCapital(next.initialCapital());
        row.setFeeRate(next.feeRate());
        row.setSlippage(next.slippage());
        row.setStopLoss(next.stopLoss());
        row.setTakeProfit(next.takeProfit());
        row.setPositionFraction(next.positionFraction());
        row.setRiskFreeRate(next.riskFreeRate());
        row.setUpdatedAt(now);
        row.setUpdatedBy(admin.getEmail());
        repository.save(row);
        changes.saveAll(log);
        return new DefaultsView(row.toSettings(), BacktestSettings.BUILT_IN, now, admin.getEmail());
    }

    /**
     * Memes controles que le backtest. Tous les champs sont obligatoires, sauf stop-loss et take-profit
     * (null ou 0 = aucun).
     */
    static BacktestSettings validate(DefaultsRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Les paramètres par défaut sont obligatoires");
        }
        required("initialCapital", req.initialCapital());
        required("feeRate", req.feeRate());
        required("slippage", req.slippage());
        required("positionFraction", req.positionFraction());
        required("riskFreeRate", req.riskFreeRate());
        // 0 (et non null) pour « aucun » : sinon la regle du backtest reprendrait la valeur par defaut
        BacktestRequest asRequest = new BacktestRequest(null, null, null, null, req.initialCapital(), req.feeRate(),
                req.slippage(), req.stopLoss() == null ? 0.0 : req.stopLoss(),
                req.takeProfit() == null ? 0.0 : req.takeProfit(), req.positionFraction(), null, req.riskFreeRate());
        return BacktestService.settings(asRequest, BacktestSettings.BUILT_IN);
    }

    private static void required(String name, Double value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " : valeur obligatoire");
        }
    }

    private static SimulationDefaults originalRow() {
        BacktestSettings s = BacktestSettings.BUILT_IN;
        SimulationDefaults row = new SimulationDefaults();
        row.setId(SimulationDefaults.ID);
        row.setInitialCapital(s.initialCapital());
        row.setFeeRate(s.feeRate());
        row.setSlippage(s.slippage());
        row.setStopLoss(s.stopLoss());
        row.setTakeProfit(s.takeProfit());
        row.setPositionFraction(s.positionFraction());
        row.setRiskFreeRate(s.riskFreeRate());
        return row;
    }

    private static void trace(List<SimulationDefaultsChange> log, String field, Double before, Double after,
                              LocalDateTime when, User admin) {
        if (Objects.equals(before, after)) return;
        SimulationDefaultsChange c = new SimulationDefaultsChange();
        c.setChangedAt(when);
        c.setChangedById(admin.getId());
        c.setChangedByEmail(admin.getEmail());
        c.setField(field);
        c.setOldValue(text(before));
        c.setNewValue(text(after));
        log.add(c);
    }

    /** 0.0010 -> "0.001", null -> "aucun". */
    static String text(Double value) {
        return value == null ? "aucun" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
