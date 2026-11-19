package tn.esprit.marketsim.analysis.indicator;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.User;

/**
 * Module 3 : configuration d'indicateurs enregistree par un utilisateur (periodes et indicateurs affiches dans
 * l'onglet Analyse technique). Elle n'est liee a aucun actif : on l'applique a l'actif affiche.
 * Le nom est unique pour un meme utilisateur (controle par le service, contrainte en base en filet de securite).
 * Cette entite n'est jamais renvoyee telle quelle au front : voir {@link IndicatorConfigDtos}.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_indicator_config_owner_name", columnNames = { "owner_id", "name" }))
@Getter
@Setter
@NoArgsConstructor
public class IndicatorConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @Column(nullable = false, length = IndicatorConfigService.NAME_MAX)
    private String name;

    @Column(length = IndicatorConfigService.DESCRIPTION_MAX)
    private String description;

    @Embedded
    private IndicatorPeriods periods;

    @Embedded
    private IndicatorVisibility show;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // dates a la seconde : la base n'en garde pas plus, la reponse a la creation est ainsi identique aux lectures suivantes
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
