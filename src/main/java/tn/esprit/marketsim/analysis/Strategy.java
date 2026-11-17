package tn.esprit.marketsim.analysis;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.User;

/** Module 3 : strategie de trading sauvegardee par l'utilisateur (pour le backtesting). */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Strategy {

    public enum Status { ACTIVE, PAUSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    /** Indicateurs utilises, ex : "RSI(14),MACD(12,26,9)". */
    private String indicators;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    // Resultats du dernier backtest (remplis par la partie IA / backtesting)
    private Double sharpe;
    private Double winRate;
    private Double maxDrawdown;
    private Integer trades;

    private LocalDateTime createdAt;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
