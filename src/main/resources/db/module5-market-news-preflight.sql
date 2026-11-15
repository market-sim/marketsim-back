-- Module 5 / MySQL preflight (read-only).
-- Exécuter avant toute évolution d'unicité de market_news.url.
-- Le script ne corrige et ne supprime aucune ligne.

-- Valeurs URL nulles et vides :
SELECT
  SUM(url IS NULL) AS null_urls,
  SUM(url IS NOT NULL AND TRIM(url) = '') AS empty_urls,
  COUNT(*) AS total_rows
FROM market_news;

-- URLs dupliquées (comparaison exacte après trim, insensible à la casse) :
SELECT LOWER(TRIM(url)) AS normalized_url, COUNT(*) AS duplicate_count,
       GROUP_CONCAT(id ORDER BY id) AS row_ids
FROM market_news
WHERE url IS NOT NULL AND TRIM(url) <> ''
GROUP BY LOWER(TRIM(url))
HAVING COUNT(*) > 1;

-- Index et contraintes déjà présents :
SHOW INDEX FROM market_news;

-- Note : le modèle possède déjà un hash SHA-256 unique (url_hash) qui prend
-- l'URL lorsqu'elle est renseignée et fournit une clé indexable même sur MySQL
-- utf8mb4 (la colonne URL peut dépasser la taille maximale d'un index complet).
-- Ne pas ajouter un index unique direct sur url avant revue du résultat ci-dessus.
