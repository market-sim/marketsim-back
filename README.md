# MarketSim AI — Backend (Spring Boot)

API REST du simulateur de salle de marché **MarketSim AI** (Esprit — PIF 2026-2027).
Le frontend React est dans un autre repo : `marketsim-frontend`.

## Stack

- Java 17+ · Spring Boot 4 · Maven (wrapper inclus, pas besoin d'installer Maven)
- Spring Data JPA + **MySQL (XAMPP)**
- Spring Security + **JWT**
- Lombok

## Lancer le projet

1. **XAMPP** → démarrer **MySQL** (port 3306, user `root`, sans mot de passe).
   La base `marketsim_db` et les tables sont créées **automatiquement** au premier lancement.
2. Ouvrir le dossier dans **IntelliJ** (il détecte Maven) et lancer `MarketsimBackendApplication`,
   ou en ligne de commande :

   ```bash
   ./mvnw spring-boot:run
   ```

3. L'API tourne sur **http://localhost:8081/api**

> Port 8081 (et pas 8080) car 8080 est souvent occupé (Oracle, Tomcat de XAMPP...).
> Pour changer : `server.port` dans `src/main/resources/application.properties`.
> Si ton MySQL a un mot de passe, change `spring.datasource.password` (sans le commit !).

### Compte admin créé automatiquement

| Email | Mot de passe |
|---|---|
| `admin@marketsim.tn` | `admin123` |

Au premier lancement, des données de démo sont aussi ajoutées (actifs, cours, scénarios de stress).

## Organisation du code (un package = un module)

```
src/main/java/tn/esprit/marketsim/
├── config/       SecurityConfig (JWT, CORS, rôles), DataInitializer (données de démo)
├── security/     JwtService, JwtAuthFilter
├── common/       Gestion des erreurs (GlobalExceptionHandler)
├── user/         Module 1 — Utilisateurs & Administration
├── trading/      Module 2 — Trading & Gestion du Risque
├── analysis/     Module 3 — Analyse Financière & Backtesting
├── education/    Module 4 — Formation & Pédagogie
├── market/       Module 5 — Marché, Prédiction & Sentiment
├── agent/        Module 6 — Agent de Trading Intelligent
└── stress/       Module 7 — Stress Testing Bancaire & Actuariat
```

Chaque module suit le même schéma : **Entity → Repository → Service → Controller**.
Copiez ce schéma pour ajouter vos entités (ex : `Quiz`, `Bank`, `InsuranceContract`, `AgentDecision`...).

👉 **Chaque membre travaille uniquement dans le package de son module** → presque aucun conflit Git.

## Endpoints

Toutes les routes (sauf `/api/auth/**`) demandent le header `Authorization: Bearer <token>`.

| Module | Méthode & URL | Description |
|---|---|---|
| 1 | `POST /api/auth/register` | Inscription → `{ token, user }` |
| 1 | `POST /api/auth/login` | Connexion → `{ token, user }` |
| 1 | `GET/PUT /api/users/me` | Profil connecté |
| 1 | `GET /api/admin/users` | Liste des comptes (ADMIN) |
| 1 | `PATCH /api/admin/users/{id}/enabled?value=false` | Activer / désactiver (ADMIN) |
| 1 | `DELETE /api/admin/users/{id}` | Supprimer (ADMIN) |
| 2 | `GET/POST /api/trading/orders`, `GET/PUT/DELETE /api/trading/orders/{id}` | Ordres de l'utilisateur |
| 3 | `GET/POST /api/analysis/strategies`, `GET/PUT/DELETE .../{id}` | Stratégies |
| 4 | `GET /api/education/courses` (+ POST/PUT/DELETE réservés ADMIN) | Cours |
| 5 | `GET /api/market/assets` (+ POST/PUT/DELETE réservés ADMIN) | Actifs / cotations |
| 6 | `GET/POST /api/agents`, `PUT/DELETE /api/agents/{id}`, `PATCH /api/agents/{id}/toggle` | Agents IA |
| 7 | `GET/POST /api/stress/scenarios`, `GET/PUT/DELETE .../{id}` | Scénarios de stress |

Test rapide (Postman ou curl) :

```bash
curl -X POST http://localhost:8081/api/auth/login -H "Content-Type: application/json" -d "{\"email\":\"admin@marketsim.tn\",\"password\":\"admin123\"}"
```

## Travailler à plusieurs avec Git

1. `git pull` sur `main` avant de commencer.
2. Créer une branche pour son module : `module2-trading`, `module5-market`...
   (GitHub Desktop : *Current branch → New branch*).
3. Commit souvent, puis *Push* et ouvrir une **Pull Request** vers `main`.
4. Ne jamais commit `target/`, ni un mot de passe MySQL personnel.

## Partie IA

Les modèles IA (LSTM, NLP, RL, SHAP...) se font en général en **Python** (FastAPI/Flask) dans un service à part.
Le backend Spring les appelle en HTTP (`RestClient`) — les endroits prévus sont marqués `TODO (IA module X)`.
