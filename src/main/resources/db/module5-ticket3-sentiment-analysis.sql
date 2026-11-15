-- Module 5 / Ticket 3 — migration additive, MySQL.
-- Les colonnes historiques sentiment_score et sentiment_label de market_news
-- ne sont pas modifiées. Chaque tentative FinBERT reçoit sa propre ligne.

CREATE TABLE IF NOT EXISTS market_news_analysis (
    id BIGINT NOT NULL AUTO_INCREMENT,
    news_id BIGINT NOT NULL,
    headline VARCHAR(512) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sentiment_label VARCHAR(16) NULL,
    positive_probability DECIMAL(8,7) NULL,
    negative_probability DECIMAL(8,7) NULL,
    neutral_probability DECIMAL(8,7) NULL,
    sentiment_score DECIMAL(8,7) NULL,
    model_name VARCHAR(128) NULL,
    model_version VARCHAR(96) NULL,
    analyzed_at TIMESTAMP(6) NOT NULL,
    error_code VARCHAR(80) NULL,
    PRIMARY KEY (id),
    INDEX idx_mna_symbol_published (symbol, published_at),
    INDEX idx_mna_news_status_model (news_id, status, model_name, model_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pas de clé étrangère volontaire : les snapshots d'analyse restent consultables
-- même si un administrateur supprime ensuite un article via l'ancienne API.
