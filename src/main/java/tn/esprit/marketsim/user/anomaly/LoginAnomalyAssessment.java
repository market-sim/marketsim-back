package tn.esprit.marketsim.user.anomaly;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "login_anomaly_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uk_login_anomaly_activity_log", columnNames = "activityLogId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginAnomalyAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lien vers le journal d'activite (sans FK pour survivre aux suppressions de comptes). */
    @Column(nullable = false)
    private Long activityLogId;

    private Long actorId;

    @Column(nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LoginAnomalyStatus status;

    private Double riskScore;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private LoginRiskLevel riskLevel;

    /** Codes d'explication separes par virgule. */
    @Column(nullable = false, length = 512)
    private String reasonCodes;

    @Column(length = 64)
    private String modelVersion;

    /** Resume sur des features (JSON), sans secrets. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String featureSummaryJson;
}
