package tn.esprit.marketsim.trading;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tn.esprit.marketsim.common.NotFoundException;
import tn.esprit.marketsim.trading.TradeOrder.Status;
import tn.esprit.marketsim.trading.TradingDtos.EquityPoint;
import tn.esprit.marketsim.trading.TradingDtos.PortfolioResponse;
import tn.esprit.marketsim.user.User;

/** Module 2 : etat du portefeuille (calculs dans {@link PortfolioCalculator}) et courbe d'equity. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PortfolioService {

    private final TradeOrderRepository orders;
    private final TradingUserRepository accounts;
    private final EquitySnapshotRepository snapshots;
    private final PriceProvider prices;
    private final TradingProperties props;

    /** Portefeuille courant de l'utilisateur (solde relu en base, prix via PriceProvider). */
    public PortfolioResponse portfolio(Long userId) {
        User account = accounts.findById(userId).orElseThrow(() -> new NotFoundException("Compte introuvable"));
        return compute(account);
    }

    /** Calcul a partir d'un compte deja charge (eventuellement verrouille et modifie dans la transaction en cours). */
    PortfolioResponse compute(User account) {
        List<TradeOrder> tickets = orders.findByOwnerIdOrderByCreatedAtDesc(account.getId());
        return PortfolioCalculator.compute(balanceOf(account), tickets, marks(tickets), props.getInitialCapital());
    }

    /** Prix courant de chaque symbole ayant un ticket OPEN (symbole disparu → prix d'entree, voir le calculateur). */
    Map<String, BigDecimal> marks(Collection<TradeOrder> tickets) {
        Map<String, BigDecimal> marks = new HashMap<>();
        for (TradeOrder t : tickets) {
            if (t.getStatus() != Status.OPEN) continue;
            String key = PortfolioCalculator.key(t.getSymbol());
            if (marks.containsKey(key)) continue;
            try {
                marks.put(key, prices.currentPrice(key));
            } catch (RuntimeException e) {
                log.warn("Portefeuille : pas de prix pour {} ({})", key, e.getMessage());
            }
        }
        return marks;
    }

    BigDecimal balanceOf(User account) {
        return account.getVirtualBalance() == null ? props.getInitialCapital() : account.getVirtualBalance();
    }

    // ===================== Courbe d'equity =====================

    public List<EquityPoint> history(Long userId) {
        return snapshots.findByOwnerIdOrderByTakenAtAscIdAsc(userId).stream()
                .map(s -> new EquityPoint(s.getTakenAt(), s.getEquity(), s.getCash(), s.getEvent()))
                .toList();
    }

    /**
     * Enregistre un point de la courbe d'equity dans la transaction de l'ordre (appele apres chaque mouvement de
     * solde). Les statuts modifies en memoire sont vus grace au flush automatique avant la requete.
     */
    @Transactional
    public void recordSnapshot(User account, String event) {
        PortfolioResponse p = compute(account);
        EquitySnapshot s = new EquitySnapshot();
        s.setOwnerId(account.getId());
        s.setTakenAt(LocalDateTime.now());
        s.setEquity(p.equity());
        s.setCash(p.cash());
        s.setEvent(event);
        snapshots.save(s);
    }
}
