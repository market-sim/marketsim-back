package tn.esprit.marketsim.trading;

import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Migration des bases locales existantes (MySQL / MariaDB de XAMPP) vers le moteur de trading.
 * <p>
 * {@code ddl-auto=update} ajoute les colonnes manquantes mais ne modifie jamais une colonne existante. Or l'ancienne
 * table avait :
 * <ul>
 *   <li>{@code status enum('CANCELLED','CLOSED','OPEN')} : PENDING / REJECTED y seraient refuses → passage en VARCHAR ;</li>
 *   <li>{@code price, quantity, stop_loss, take_profit decimal(38,2)} : 0,0123 BTC serait tronque → DECIMAL(19,6).</li>
 * </ul>
 * Les anciennes lignes sont completees : version = 0 ; tickets OPEN / CLOSED sans prix d'execution →
 * executedPrice = price, executedAt = createdAt, fees = 0 et legacy = true (leur ouverture n'a jamais debite le
 * solde, voir {@link TradeAccounting}). Idempotent : sans effet au 2e demarrage. S'execute apres la creation du
 * schema par Hibernate ({@code @DependsOn("entityManagerFactory")}) et avant le demarrage du serveur web.
 */
@Slf4j
@Component
@DependsOn("entityManagerFactory")
public class TradingSchemaMigration {

    private static final String TABLE = "trade_order";
    private static final Map<String, String> DECIMAL_COLUMNS = Map.of(
            "price", "NOT NULL", "quantity", "NOT NULL", "stop_loss", "NULL", "take_profit", "NULL");

    private final DataSource dataSource;

    public TradingSchemaMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrate() {
        try {
            if (!isMySql()) {
                log.info("Migration trading ignoree : base non MySQL/MariaDB");
                return;
            }
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if (!tableExists(jdbc)) return;
            migrateStatusColumn(jdbc);
            migrateDecimalColumns(jdbc);
            int versions = jdbc.update("UPDATE trade_order SET version = 0 WHERE version IS NULL");
            int filled = jdbc.update("""
                    UPDATE trade_order
                       SET executed_price = price, executed_at = created_at, fees = 0, legacy = 1
                     WHERE status IN ('OPEN', 'CLOSED') AND executed_price IS NULL""");
            int flags = jdbc.update("UPDATE trade_order SET legacy = 0 WHERE legacy IS NULL");
            if (versions + filled + flags > 0) {
                log.info("Migration trading : {} versions initialisees, {} anciens tickets completes (legacy), {} lignes marquees non-legacy",
                        versions, filled, flags);
            }
        } catch (Exception e) {
            // Ne bloque pas le demarrage : l'erreur est visible dans les logs.
            log.error("Migration du schema trading impossible", e);
        }
    }

    private boolean isMySql() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            return product.contains("mysql") || product.contains("mariadb");
        }
    }

    private boolean tableExists(JdbcTemplate jdbc) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, TABLE);
        return n != null && n > 0;
    }

    private void migrateStatusColumn(JdbcTemplate jdbc) {
        List<String> types = jdbc.queryForList("""
                SELECT data_type FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ? AND column_name = 'status'""", String.class, TABLE);
        if (!types.isEmpty() && "enum".equalsIgnoreCase(types.get(0))) {
            jdbc.execute("ALTER TABLE trade_order MODIFY status VARCHAR(20) NULL");
            log.info("Migration trading : colonne status ENUM → VARCHAR(20)");
        }
    }

    private void migrateDecimalColumns(JdbcTemplate jdbc) {
        for (Map.Entry<String, String> col : DECIMAL_COLUMNS.entrySet()) {
            List<Integer> scales = jdbc.queryForList("""
                    SELECT numeric_scale FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?""",
                    Integer.class, TABLE, col.getKey());
            if (!scales.isEmpty() && scales.get(0) != null && scales.get(0) < Money.PRICE_SCALE) {
                jdbc.execute("ALTER TABLE trade_order MODIFY " + col.getKey() + " DECIMAL(19,6) " + col.getValue());
                log.info("Migration trading : colonne {} → DECIMAL(19,6)", col.getKey());
            }
        }
    }
}
