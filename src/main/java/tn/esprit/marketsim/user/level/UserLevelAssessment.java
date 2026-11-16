package tn.esprit.marketsim.user.level;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tn.esprit.marketsim.user.UserLevel;

/**
 * Dernier niveau calcule. userId sans cle etrangere : la ligne survit a la suppression du compte.
 * <p>
 * Deux niveaux sont stockes separement : {@code activityLevel} (modele IA) et {@code tradingLevel} (jeu).
 * {@code level} / {@code score} sont le niveau AFFICHE et son score, choisis par {@link UserLevelService#DISPLAY_RULE}.
 */
@Entity
@Table(name = "user_level_assessments",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_level_user", columnNames = "userId"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLevelAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private Integer score;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserLevel level;

    @Column(nullable = false)
    private boolean insufficientEvidence;

    @Column(nullable = false)
    private int successfulLoginCount;

    @Column(nullable = false, length = 512)
    private String reasonCodes;

    @Column(length = 64)
    private String modelVersion;

    @Column(nullable = false)
    private Instant computedAt;

    // ----- Deux niveaux explicites (colonnes nullables : les anciennes lignes sont migrees au demarrage) -----

    /** Niveau d'activite calcule par le modele IA du module 1 (null si activite insuffisante). */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserLevel activityLevel;

    /** Score 0-100 du modele IA (va avec activityLevel). */
    private Integer activityScore;

    /** Niveau gagne dans le jeu de progression (null si pas de profil de trader). */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserLevel tradingLevel;

    /** Score de competence 0-100 du jeu (va avec tradingLevel). */
    private Integer tradingScore;

    /** Source du niveau affiche ({@link #level} / {@link #score}) : TRADING ou ACTIVITY. */
    @Column(length = 20)
    private String levelSource;
}
