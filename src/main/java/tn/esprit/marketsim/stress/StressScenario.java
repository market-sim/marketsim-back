package tn.esprit.marketsim.stress;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Module 7 : scenario de stress applique aux banques simulees. */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class StressScenario {

    public enum Severity { MODERATE, HIGH, SEVERE, EXTREME }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    private Severity severity = Severity.MODERATE;

    /** Choc sur le PIB en %. */
    private Double gdpShock;

    /** Choc sur les taux d'interet en points de %. */
    private Double rateShock;

    /** Contraction du credit en %. */
    private Double creditShock;

    private LocalDateTime createdAt;

    /** Fabrique utilisee par DataInitializer (pas de constructeur : Jackson l'utiliserait pour le JSON). */
    public static StressScenario of(String name, Severity severity, double gdpShock, double rateShock,
                                    double creditShock) {
        StressScenario s = new StressScenario();
        s.name = name;
        s.severity = severity;
        s.gdpShock = gdpShock;
        s.rateShock = rateShock;
        s.creditShock = creditShock;
        return s;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
