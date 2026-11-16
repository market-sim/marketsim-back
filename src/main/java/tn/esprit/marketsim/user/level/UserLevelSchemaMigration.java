package tn.esprit.marketsim.user.level;

import javax.sql.DataSource;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Migration des evaluations de niveau existantes vers les deux niveaux explicites (activite / trading).
 * <p>
 * Avant : {@code score} etait toujours celui du modele IA, mais {@code level} pouvait etre ecrase par le niveau du
 * jeu (raison TRADING_PROGRESSION). Les anciennes lignes recoivent donc :
 * <ul>
 *   <li>{@code activity_score = score} et {@code activity_level} deduit du score (seuils du modele : 25 / 50 / 75) ;</li>
 *   <li>{@code level_source = TRADING} si la raison TRADING_PROGRESSION est presente, sinon ACTIVITY ;</li>
 *   <li>pour les lignes TRADING, {@code trading_level = level} et {@code score} est vide (il etait incoherent) :
 *       le score de competence est recalcule au prochain affichage du niveau.</li>
 * </ul>
 * Idempotent (ne touche que les lignes dont {@code level_source} est vide). S'execute apres la creation du schema.
 */
@Slf4j
@Component
@DependsOn("entityManagerFactory")
public class UserLevelSchemaMigration {

    private final DataSource dataSource;

    public UserLevelSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int activity = jdbc.update("""
                    UPDATE user_level_assessments
                       SET activity_score = score,
                           activity_level = CASE WHEN score IS NULL THEN NULL
                                                 WHEN score < 25 THEN 'DEBUTANT'
                                                 WHEN score < 50 THEN 'INTERMEDIAIRE'
                                                 WHEN score < 75 THEN 'AVANCE'
                                                 ELSE 'EXPERT' END
                     WHERE level_source IS NULL""");
            int trading = jdbc.update("""
                    UPDATE user_level_assessments
                       SET level_source = 'TRADING', trading_level = level, score = NULL
                     WHERE level_source IS NULL AND reason_codes LIKE '%TRADING_PROGRESSION%'""");
            jdbc.update("UPDATE user_level_assessments SET level_source = 'ACTIVITY' WHERE level_source IS NULL");
            if (activity > 0) {
                log.info("Migration niveaux : {} evaluation(s) completee(s), dont {} issue(s) du jeu", activity, trading);
            }
        } catch (RuntimeException e) {
            // table absente (base neuve) ou base non SQL : rien a migrer
            log.debug("Migration niveaux ignoree ({})", e.getMessage());
        }
    }
}
