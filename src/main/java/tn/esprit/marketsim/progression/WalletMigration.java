package tn.esprit.marketsim.progression;

import javax.sql.DataSource;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Fusion des deux portefeuilles (une seule fois par base, marqueur dans {@code app_migrations}).
 * <p>
 * Avant : {@code users.virtual_balance} (trading, base 100 000 $) et {@code trader_profiles.balance} (jeu, base
 * 1 000 $) etaient independants. Regle de fusion, pour chaque profil de jeu existant :
 * <ul>
 *   <li>le solde de TRADING est conserve : c'est lui qui devient le solde unique (les positions ouvertes du
 *       module 2 en dependent, on ne peut pas le changer) ;</li>
 *   <li>l'ancien solde du jeu est reporte dans {@code trader_profiles.legacy_game_balance} (historique) ;</li>
 *   <li>la copie {@code trader_profiles.balance} et le record {@code peak_balance} repartent du solde unique
 *       (l'ancien record etait en base 1 000 $) ; les statistiques de competence (parties, reussite, discipline)
 *       sont gardees : elles ne dependent pas du montant.</li>
 * </ul>
 * Aucun argent n'est cree ni perdu : le solde de trading ne change pas, l'ancien solde du jeu n'etait pas
 * depensable ailleurs. Les parties deja jouees gardent leurs montants d'origine (base 1 000 $) dans l'historique.
 */
@Slf4j
@Component
@DependsOn("entityManagerFactory")
public class WalletMigration {

    static final String NAME = "2026-11-single-wallet";

    private final DataSource dataSource;

    public WalletMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE IF NOT EXISTS app_migrations (name VARCHAR(100) PRIMARY KEY, applied_at TIMESTAMP)");
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM app_migrations WHERE name = ?", Integer.class, NAME);
            if (done != null && done > 0) return;
            int users = jdbc.update("UPDATE users SET virtual_balance = 100000 WHERE virtual_balance IS NULL");
            int profiles = jdbc.update("""
                    UPDATE trader_profiles
                       SET legacy_game_balance = balance,
                           balance = COALESCE((SELECT u.virtual_balance FROM users u WHERE u.id = trader_profiles.user_id), 100000),
                           peak_balance = COALESCE((SELECT u.virtual_balance FROM users u WHERE u.id = trader_profiles.user_id), 100000)
                     WHERE legacy_game_balance IS NULL""");
            jdbc.update("INSERT INTO app_migrations (name, applied_at) VALUES (?, CURRENT_TIMESTAMP)", NAME);
            log.info("Fusion des portefeuilles : {} profil(s) de jeu rattache(s) au solde unique, {} compte(s) initialise(s)",
                    profiles, users);
        } catch (RuntimeException e) {
            log.warn("Fusion des portefeuilles non appliquee ({})", e.getMessage());
        }
    }
}
