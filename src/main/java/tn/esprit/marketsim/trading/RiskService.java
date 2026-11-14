package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.agent.engine.Stats;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.trading.TradeOrder.OrderType;
import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradingDtos.PortfolioResponse;
import tn.esprit.marketsim.trading.TradingDtos.PortfolioRiskResponse;
import tn.esprit.marketsim.trading.TradingDtos.PositionDto;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessRequest;
import tn.esprit.marketsim.trading.TradingDtos.RiskAssessment;
import tn.esprit.marketsim.user.User;

/**
 * Module 2 : gestion du risque. Evaluation d'un ordre avant execution (formule dans {@link RiskCalculator}) et
 * risque du portefeuille (VaR, CVaR, expositions, concentration, drawdown).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RiskService {

    static final int VAR_WINDOW = 250;
    static final int MIN_OBSERVATIONS = 60;
    static final String ASSUMPTIONS = "Horizon 1 jour. VaR historique sur les rendements réels alignés (dates communes, "
            + "250 séances max) si tous les actifs ouverts ont une série réelle ; sinon VaR paramétrique normale avec "
            + "corrélation constante entre actifs (trading.risk.default-correlation) et σ par classe d'actif. "
            + "Expositions signées : LONG positif, SHORT négatif.";

    private final PortfolioService portfolio;
    private final VolatilityService volatility;
    private final PriceProvider prices;
    private final TradingProperties props;
    private final TradingUserRepository accounts;
    private final EquitySnapshotRepository snapshots;

    // ===================== Ordre =====================

    /** Simulation a blanc (POST /risk/assess) : ne cree rien. */
    public RiskAssessment assess(Long userId, RiskAssessRequest req) {
        User account = accounts.findById(userId).orElseThrow(() -> new NotFoundException("Compte introuvable"));
        String symbol = req.symbol().trim().toUpperCase(Locale.ROOT);
        BigDecimal market = prices.currentPrice(symbol);
        OrderType type = req.orderType() == null ? OrderType.MARKET : req.orderType();
        BigDecimal reference = type == OrderType.MARKET || req.limitPrice() == null ? market : Money.price(req.limitPrice());
        return assess(account, symbol, req.side(), OrderRules.normalizeQuantity(req.quantity()), reference,
                req.stopLoss() != null);
    }

    /**
     * Evaluation d'un ordre pour un compte deja charge (utilisee aussi par la creation d'ordre, sous verrou).
     *
     * @param referencePrice prix courant (MARKET) ou niveau limite / stop (LIMIT, STOP)
     */
    public RiskAssessment assess(User account, String symbol, Side side, BigDecimal quantity, BigDecimal referencePrice,
                                 boolean hasStopLoss) {
        PortfolioResponse p = portfolio.compute(account);
        String key = PortfolioCalculator.key(symbol);
        BigDecimal symbolGross = p.positions().stream().filter(x -> x.symbol().equals(key))
                .map(PositionDto::grossExposure).findFirst().orElse(Money.zero());
        VolatilityService.Profile vol = volatility.profile(key);
        return RiskCalculator.assess(new RiskCalculator.Input(side, quantity, referencePrice, props.getFeeRate(),
                hasStopLoss, p.cash(), p.equity(), symbolGross, p.grossExposure(),
                p.openPositions() + p.pendingOrders(), vol.sigmaAnnual(), vol.sortedReturns(), vol.source(),
                props.getRisk()));
    }

    // ===================== Portefeuille =====================

    public PortfolioRiskResponse portfolioRisk(Long userId) {
        User account = accounts.findById(userId).orElseThrow(() -> new NotFoundException("Compte introuvable"));
        PortfolioResponse p = portfolio.compute(account);
        List<PositionDto> positions = p.positions();
        int n = positions.size();
        double[] exposures = new double[n];
        double[] gross = new double[n];
        List<VolatilityService.Profile> profiles = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            exposures[i] = positions.get(i).marketValue().doubleValue();
            gross[i] = positions.get(i).grossExposure().doubleValue();
            profiles.add(volatility.profile(positions.get(i).symbol()));
        }

        double var95 = 0;
        double var99 = 0;
        double cvar95 = 0;
        String method = "NONE";
        int observations = 0;
        if (n > 0) {
            double[] pnl = profiles.stream().allMatch(VolatilityService.Profile::real)
                    ? RiskCalculator.historicalPnl(exposures,
                            profiles.stream().map(VolatilityService.Profile::closesByDate).toList(), VAR_WINDOW)
                    : new double[0];
            if (pnl.length >= MIN_OBSERVATIONS) {
                double[] sorted = Stats.sortedCopy(pnl);
                var95 = Math.max(0, Stats.valueAtRisk(sorted, 0.95));
                var99 = Math.max(0, Stats.valueAtRisk(sorted, 0.99));
                cvar95 = Math.max(0, Stats.expectedShortfall(sorted, 0.95));
                method = "HISTORICAL";
                observations = pnl.length;
            } else {
                double[] sigmas = profiles.stream().mapToDouble(VolatilityService.Profile::sigmaDaily).toArray();
                double sigmaP = RiskCalculator.parametricSigma(exposures, sigmas, props.getRisk().getDefaultCorrelation());
                var95 = RiskCalculator.Z95 * sigmaP;
                var99 = RiskCalculator.Z99 * sigmaP;
                cvar95 = RiskCalculator.ES95_FACTOR * sigmaP;
                method = "PARAMETRIC";
            }
        }

        double hhi = RiskCalculator.hhi(gross);
        BigDecimal[] drawdowns = drawdowns(userId, p.equity());
        return new PortfolioRiskResponse(p.equity(), amount(var95), amount(var99), amount(cvar95),
                PortfolioCalculator.pct(amount(var95), p.equity()), method, observations, p.grossExposure(),
                p.netExposure(), PortfolioCalculator.pct(p.grossExposure(), p.equity()),
                PortfolioCalculator.pct(p.netExposure(), p.equity()),
                BigDecimal.valueOf(hhi).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(RiskCalculator.diversification(hhi, n)).setScale(2, RoundingMode.HALF_UP),
                drawdowns[0], drawdowns[1], n, ASSUMPTIONS);
    }

    /** [drawdown max, drawdown courant] en % (negatifs), d'apres la courbe d'equity ; null sans historique. */
    private BigDecimal[] drawdowns(Long userId, BigDecimal equityNow) {
        List<EquitySnapshot> history = snapshots.findByOwnerIdOrderByTakenAtAscIdAsc(userId);
        if (history.isEmpty()) return new BigDecimal[] {null, null};
        double[] curve = new double[history.size() + 2];
        curve[0] = props.getInitialCapital().doubleValue();
        for (int i = 0; i < history.size(); i++) curve[i + 1] = history.get(i).getEquity().doubleValue();
        curve[curve.length - 1] = equityNow.doubleValue();
        double peak = Double.NEGATIVE_INFINITY;
        for (double v : curve) peak = Math.max(peak, v);
        double max = Stats.maxDrawdown(curve) * 100;
        double current = peak > 0 ? (equityNow.doubleValue() / peak - 1) * 100 : 0;
        return new BigDecimal[] {pct2(max), pct2(current)};
    }

    private static BigDecimal amount(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
