package tn.esprit.marketsim.user.score;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.anomaly.LoginRiskLevel;

/**
 * Dernier score utilisateur. userId sans cle etrangere : la ligne survit a la suppression du compte.
 */
@Entity
@Table(name = "user_security_scores",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_security_score_user", columnNames = "userId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSecurityScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private Integer score;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private LoginRiskLevel riskBand;

    @Column(nullable = false)
    private int assessmentCount;

    @Column(nullable = false)
    private boolean insufficientEvidence;

    @Column(nullable = false)
    private Instant computedAt;
}
