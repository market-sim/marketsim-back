package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tn.esprit.marketsim.trading.TradeOrder.Side;
import tn.esprit.marketsim.trading.TradeOrder.Status;
import tn.esprit.marketsim.trading.TradingDtos.PortfolioResponse;
import tn.esprit.marketsim.trading.TradingDtos.PositionDto;

/**
 * Calcul du portefeuille (classe pure, sans Spring, facile a tester).
 * <pre>
 *   equity         = cash + Σ valeur des tickets OPEN
 *                    (LONG : qte x prixActuel ; SHORT : marge + (entree - prixActuel) x qte ; voir TradeAccounting)
 *   investedValue  = equity - cash
 *   grossExposure  = Σ |qte| x prixActuel        netExposure = Σ LONG - Σ SHORT (en valeur de marche)
 *   unrealizedPnl  = Σ P&L latent des tickets OPEN ;  realizedPnl = Σ P&L realise des tickets CLOSED (hors frais)
 *   totalReturnPct = (equity - capitalInitial) / capitalInitial x 100
 * </pre>
 * Positions agregees par symbole : netQuantity = LONG - SHORT, avgEntryPrice pondere par les quantites de tous les
 * tickets du symbole, marketValue = netQuantity x prix (negatif si net SHORT), weightPct = exposition brute du
 * symbole / equity.
 */
public final class PortfolioCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PortfolioCalculator() {
    }

    /**
     * @param cash     solde du compte
     * @param tickets  tous les tickets de l'utilisateur (seuls OPEN, CLOSED et PENDING sont utilises)
     * @param marks    prix courant par symbole (MAJUSCULES) ; a defaut, le prix d'entree est utilise
     */
    public static PortfolioResponse compute(BigDecimal cash, Collection<TradeOrder> tickets, Map<String, BigDecimal> marks,
                                   BigDecimal initialCapital) {
        BigDecimal positionsValue = Money.zero();
        BigDecimal unrealized = Money.zero();
        BigDecimal realized = Money.zero();
        BigDecimal fees = Money.zero();
        BigDecimal gross = Money.zero();
        BigDecimal net = Money.zero();
        int open = 0;
        int pending = 0;
        Map<String, Acc> bySymbol = new LinkedHashMap<>();

        for (TradeOrder t : tickets) {
            if (t.getStatus() == Status.PENDING) pending++;
            if (t.getStatus() == Status.CLOSED) {
                realized = realized.add(Money.orZero(t.getRealizedPnl()));
                fees = fees.add(Money.orZero(t.getFees()));
            }
            if (t.getStatus() != Status.OPEN) continue;
            open++;
            fees = fees.add(Money.orZero(t.getFees()));
            BigDecimal mark = markOf(t, marks);
            BigDecimal pnl = TradeAccounting.pnl(t.getSide(), t.entryPrice(), mark, t.getQuantity());
            BigDecimal mv = Money.amount(t.getQuantity().multiply(mark));
            positionsValue = positionsValue.add(TradeAccounting.openValue(t, mark));
            unrealized = unrealized.add(pnl);
            gross = gross.add(mv);
            net = t.getSide() == Side.BUY ? net.add(mv) : net.subtract(mv);
            bySymbol.computeIfAbsent(key(t.getSymbol()), k -> new Acc(k, mark)).add(t, pnl, mv);
        }

        BigDecimal c = Money.amount(cash);
        BigDecimal equity = c.add(positionsValue);
        List<PositionDto> positions = new ArrayList<>();
        for (Acc a : bySymbol.values()) positions.add(a.toPosition(equity));
        positions.sort(Comparator.comparing(PositionDto::grossExposure).reversed());

        BigDecimal initial = initialCapital == null || initialCapital.signum() == 0 ? null : initialCapital;
        BigDecimal ret = initial == null ? BigDecimal.ZERO.setScale(2)
                : equity.subtract(initial).multiply(HUNDRED).divide(initial, 2, RoundingMode.HALF_UP);
        return new PortfolioResponse(c, equity, positionsValue, gross, net, unrealized, realized, fees, ret,
                Money.amount(initialCapital), open, pending, positions);
    }

    static BigDecimal markOf(TradeOrder t, Map<String, BigDecimal> marks) {
        BigDecimal m = marks == null ? null : marks.get(key(t.getSymbol()));
        return m != null ? m : t.entryPrice();
    }

    static String key(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }

    /** Pourcentage a 2 decimales (0 si le denominateur est nul ou negatif). */
    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() <= 0) return BigDecimal.ZERO.setScale(2);
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static final class Acc {
        final String symbol;
        final BigDecimal mark;
        BigDecimal longQty = BigDecimal.ZERO;
        BigDecimal shortQty = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal pnl = Money.zero();
        BigDecimal gross = Money.zero();
        int tickets;

        Acc(String symbol, BigDecimal mark) {
            this.symbol = symbol;
            this.mark = mark;
        }

        void add(TradeOrder t, BigDecimal ticketPnl, BigDecimal mv) {
            if (t.getSide() == Side.BUY) longQty = longQty.add(t.getQuantity());
            else shortQty = shortQty.add(t.getQuantity());
            cost = cost.add(t.getQuantity().multiply(t.entryPrice()));
            pnl = pnl.add(ticketPnl);
            gross = gross.add(mv);
            tickets++;
        }

        PositionDto toPosition(BigDecimal equity) {
            BigDecimal totalQty = longQty.add(shortQty);
            BigDecimal avg = totalQty.signum() == 0 ? BigDecimal.ZERO
                    : cost.divide(totalQty, Money.PRICE_SCALE, RoundingMode.HALF_UP);
            BigDecimal netQty = longQty.subtract(shortQty);
            return new PositionDto(symbol, Money.price(netQty), Money.price(longQty), Money.price(shortQty), avg,
                    Money.price(mark), Money.amount(netQty.multiply(mark)), gross, pnl, pct(gross, equity), tickets);
        }
    }
}
