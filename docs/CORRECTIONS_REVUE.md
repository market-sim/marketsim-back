# Corrections de la revue — branche `corrections-revue`

Branche créée depuis `main` dans les deux dépôts (`marketsim-back`, `marketsim-front`), un commit par phase.
Rien n'a été poussé ni fusionné dans `main`.

## Constats de départ (tous confirmés dans le code)

| # | Constat | Vérification |
|---|---|---|
| 1 | Prix figés | Seul `AssetService.update` (admin) modifiait `Asset.price`. Le bandeau affichait le vrai cours, le trading 192,45 $. |
| 2 | Deux portefeuilles | `users.virtual_balance` (100 000 $, BigDecimal) et `TraderProfile.balance` (1 000 $, double), indépendants. |
| 3 | Niveau incohérent | `UserLevelService` remplaçait le niveau IA par celui du jeu en gardant le score IA. |
| 4 | Données fictives | Blockchain, Marché, Stress, Analyse sur `mockData`, faux signal BUY/SELL, faux classement. |
| 5 | Secrets commités | `app.jwt.secret` en clair, `app.admin.password=admin123`. |
| 6 | SL/TP selon la volatilité | Absent. |
| 7 | `module1-ui-upgrade` | Un commit d'Ahmed non fusionné ; `ProfilePage.tsx` modifié des deux côtés. |

## Décision : capital de départ unique = 100 000 $

Le trading (module 2) a des règles de risque en % de l'equity et des positions ouvertes qui dépendent de son
solde : on ne peut pas le ramener à 1 000 $ (avec 1 000 $, la règle « 25 % max par ordre » interdit même
d'acheter une action AAPL). Le jeu de progression passe donc à 100 000 $ ; ses seuils deviennent relatifs au
départ (ruine sous 1 %, alerte sous 30 %, niveaux selon le multiple du capital).

**Migration** (`WalletMigration`, une seule fois par base, table `app_migrations`) : le solde de trading est
conservé et devient le solde unique ; l'ancien solde du jeu est reporté dans
`trader_profiles.legacy_game_balance` ; le record (`peak_balance`) repart du solde unique. Aucun argent créé ni
perdu : l'ancien solde du jeu n'était dépensable nulle part ailleurs.

## Fichiers par phase

Les fichiers **hors du module de la phase** sont marqués ⚠.

### Phase 5 — secrets (back)
`security/JwtService`, `config/DataInitializer`, `config/Secrets` (nouveau), `ledger/AuditLedger` ⚠ (avertissement seulement),
`application.properties`, `config/application.properties.example`, `README.md`, `test/config/SecretsTest` (nouveau),
`test/user/account/AccountServiceTest` (clé de test remplacée : c'était l'ancien secret).

### Phase 1 — moteur de prix (module 5)
Back : `market/MarketPriceEngine`, `MarketEngineProperties`, `PriceSteps`, `AssetPriceTick`, `AssetPriceTickRepository`,
`MarketTickController` (nouveaux), `market/AssetRepository`, `agent/ticker/MarketTickerService` ⚠ (lecture du cache),
`agent/ticker/MarketTickerController` ⚠ (le bandeau affiche le prix du moteur), `trading/AssetPriceProvider` ⚠ (commentaire),
`application.properties`, tests `PriceStepsTest`, `MarketPriceEngineTest`, `trading/PriceMovementTriggerTest`.
Front : `modules/market/LiveQuotes.tsx` (nouveau), `MarketPage.tsx`, `modules/trading/TradingPage.tsx` ⚠,
`layout/MarketTicker.tsx` ⚠, `modules/dashboard/DashboardPage.tsx` ⚠, `api/endpoints.ts`.

### Phase 6 — SL/TP selon la volatilité (module 2)
Back : `trading/ProtectionCalculator` (nouveau), `RiskService`, `RiskController`, `TradingDtos`, `TradingProperties`,
test `ProtectionCalculatorTest`. Front : `TradingPage.tsx`, `api/endpoints.ts`.

### Phase 3 — deux niveaux (module 1)
Back : `user/level/UserLevelService`, `UserLevelAssessment`, `UserLevelSchemaMigration` (nouveau), `dto/UserLevelDtos`,
`progression/ProgressionService` ⚠ (`tradingAssessment`), test `UserLevelServiceTest`.
Front : `modules/auth/TraderLevels.tsx` (nouveau), `ProfilePage.tsx`, `modules/admin/AdminUsersPage.tsx`, `api/endpoints.ts`.

### Phase 4 — données fictives (front)
`modules/blockchain/BlockchainPage.tsx` (réécrit sur le vrai registre), `modules/agent/components/AuditPanel.tsx` ⚠
(agent facultatif), `shared/DemoBanner.tsx`, `shared/PromptDialog.tsx` (nouveaux), `DashboardPage.tsx`,
`EducationPage.tsx`, `MarketPage.tsx`, `StressPage.tsx`, `AnalysisPage.tsx`, `auth/PasskeysCard.tsx`,
`auth/ProfileExtras.tsx`, `layout/navConfig.tsx`, `i18n/dictionary.ts` ; `mockData` supprimés : blockchain, education, dashboard.

### Phase 7 — fusion `module1-ui-upgrade` (front)
Fusion du commit `73254c2`. Conflits résolus : `ProfilePage.tsx` (design d'Ahmed + photo, préférences, carte
publique, politique de mot de passe de main, deux niveaux), `RegisterPage.tsx` (confirmation + jauge + reCAPTCHA),
`AdminUsersPage.tsx` (version de main, plus riche, avec les boîtes de confirmation d'Ahmed).

### Phase 2 — portefeuille unique
Back : `progression/WalletService` (réécrit), `WalletMigration` (nouveau), `ProgressionRules`, `ProgressionService`,
`LeaderboardService`, `TraderProfile`, `ProgressionController`, `dto/ProgressionDtos`, `user/account/AccountService` ⚠ (texte),
tests `WalletServiceTest`, `WalletFixture`, `AdminWalletServiceTest`, `LeaderboardServiceTest`, `ProgressionRulesTest`.
Front : textes et seuils « 1 000 $ » → 100 000 $ (`progression/*`, `modules/agent/components/*`, `EditBalanceDialog`,
`AdminUsersPage`, `DashboardTop`, `AcademyPage`).

## Endpoints

| Méthode | URL | | Rôle |
|---|---|---|---|
| GET | `/api/market/ticks?symbol=&hours=` | nouveau | Flux de cotations (1 point / minute / actif, 7 jours) |
| GET | `/api/agents/ticker` | modifié | Les actifs tradables affichent le prix du moteur |
| POST | `/api/trading/risk/suggest-protection` | nouveau | SL / TP proposés + σ, source, perte max, explication |
| GET | `/api/users/me/level`, `/api/admin/users/{id}/level` | modifié | + `activityLevel/Score`, `tradingLevel/Score`, `levelSource`, `displayRule` |
| GET | `/api/progression/me`, `/leaderboard`, `/api/admin/progression/scores` | modifié | Solde = solde unique |
| — | `/api/ledger/*` | inchangé | Utilisé par la page « Registre d'audit » |

## Décisions de conception

- **Prix** : un pas toutes les 3 s sur un thread dédié. Ancré sur le cours réel du bandeau (Yahoo / CoinGecko)
  quand il a moins de 10 min : prix = cours réel × exp(écart), écart à retour vers 0 (borné à ±5 %). Sinon marche
  aléatoire sans dérive. Le temps est accéléré ×60 (`market.engine.speed`) pour que les prix bougent en
  démonstration. Aucun appel réseau dans le thread des prix ; un second thread rafraîchit les cours réels.
- **Niveau affiché** : niveau de trading s'il existe, sinon niveau IA ; le score renvoyé est celui du niveau affiché.
- **SL/TP** : SL à 2 σ journalières, TP à 2 × la distance du SL, arrondis en s'éloignant du prix d'entrée.
- **Portefeuille** : tout mouvement passe par `WalletService` sous le verrou du module 2 et est tracé
  (`WALLET_MOVE`) dans le registre d'audit après le commit.

## Scénario de recette (exécuté le 16/11/2026, reCAPTCHA désactivé en local)

| # | Étape | Résultat observé |
|---|---|---|
| 1 | Inscription puis connexion | 200 puis 200 (jeton reçu) |
| 2 | Prix d'AAPL identique et qui bouge | API 193,95 · formulaire 193,95 · page Marché 194,23 (un pas d'écart). 7 s plus tard : 193,56 / 193,43. Sur 20 lectures simultanées après la relecture chaque seconde : 15 identiques, 5 décalées de moins d'une seconde. Bandeau : **non vérifiable dans l'environnement de test** (Yahoo / CoinGecko bloqués), le code y affiche le même prix |
| 3 | Achat de 10 AAPL avec SL/TP suggérés | Suggestion SL 186,12 / TP 208,06 (σ 1,89 %/jour, modèle, perte max 76,94 $). Ticket OPEN à 193,43, frais 1,93 $. Solde 100 000 → 98 063,73 $, **le même solde dans le jeu** |
| 4 | P&L latent qui bouge, déclenchement automatique | P&L latent −0,89 / +1,69 / +2,72 / +1,91 $ toutes les 3,5 s. Un 2ᵉ ordre (SL/TP serrés) fermé tout seul en ~6 s : TAKE_PROFIT, P&L +1,41 $ |
| 5 | Le pilote automatique emprunte | 20 000 $ empruntés : trading 98 063,20 → 78 063,20, jeu 78 063,20. Arrêt : 98 063,20 des deux côtés |
| 6 | `GET /api/users/me/level` | `levelSource` TRADING, niveau Débutant score 0, `tradingLevel` Débutant 0, `activityLevel` null (pas assez de connexions) : score et niveau cohérents |
| 7 | Registre d'audit | 9 blocs, 30 enregistrements, vérification : chaîne valide, 0 problème. Bandeau « démonstration » : Marché 2, Analyse 2, Stress 1, Formation 1 |
| 8 | Plus de secret dans le dépôt | Plus de secret JWT ni de mot de passe admin ; reste la valeur publique de développement de `LEDGER_SECRET` (voir limites) |

Pour l'étape 5, le niveau Intermédiaire (exigé par le pilote) a été donné directement en base pour la préparation du test.

## Limites connues (honnêtes)

- L'ancien secret JWT et `admin123` sont dans l'**historique Git** : à considérer comme compromis.
- `LEDGER_SECRET` garde une valeur de développement par défaut (avertissement au démarrage) : la changer
  automatiquement invaliderait la vérification des blocs déjà scellés. À définir avant le premier bloc en production.
- Les parties jouées avant la fusion gardent leurs montants en base 1 000 $ dans l'historique (courbe du tableau de bord).
- Les règles de niveau du jeu calculent encore en `double` à partir du solde unique (BigDecimal) ; l'argent lui-même est en BigDecimal.
- Le test `contextLoads` écrit dans la base locale `marketsim_db` (comportement existant, non modifié).
- `window.confirm` reste à 4 endroits hors périmètre (Académie, pilote, suppression d'agent, recommencer).
- L'admin peut ouvrir la page Trading (existant, non traité ici).

## Ce qui reste (hors périmètre)

- **Module 3** : indicateurs et backtest réels sur la page Analyse (une branche `module3-analysis` existe).
- **Module 5** : prédictions LSTM / Prophet, historique des prédictions, vrai NLP des actualités (branche `module5-market-prediction-sentiment`).
- **Module 7** : stress tests et actuariat réels (résultats des banques).
- **Blockchain** : ancrage du registre sur Ethereum Sepolia ou Hyperledger Fabric, certificats.
