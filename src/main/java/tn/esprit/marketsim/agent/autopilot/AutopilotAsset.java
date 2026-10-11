package tn.esprit.marketsim.agent.autopilot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Un actif du marche simule d'une session : historique de prix, regime courant et position. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class AutopilotAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AutopilotSession session;

    private String symbol;

    /** Derniers prix (fenetre glissante), CSV ; le dernier est le prix du jour. */
    @Column(columnDefinition = "TEXT")
    private String pricesCsv;

    /** Regime de marche courant (true = baissier). */
    private boolean bear;

    private double startPrice;

    // ----- Position -----
    private double units;
    private double entryPrice;
    /** Distance du stop-loss en fraction (take-profit = 2x). */
    private double stopPct;
}
