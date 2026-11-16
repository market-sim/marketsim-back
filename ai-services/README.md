# Services IA du module 5

## FinBERT (analyse de sentiment des actualités) — port 8090

Double-clique `Lancer-FinBERT.bat` (Windows). Le premier lancement crée un environnement `.venv`, installe
`torch` (version CPU) et `transformers`, puis télécharge le modèle `ProsusAI/finbert` (~440 Mo, une seule fois).
Quand la fenêtre affiche `[FinBERT] pret`, le bouton « Analyze with FinBERT » de la page Marché fonctionne.

- Contrat : `GET /health`, `POST /internal/v1/analyze {"text": "..."}` (voir `finbert_service.py`).
- N'écoute que sur `127.0.0.1` ; aucune donnée ne quitte le PC (le modèle tourne en local).
- Révision épinglée : `FINBERT_REVISION` (même valeur que `app.market.finbert.revision`). Si elle n'existe pas sur
  Hugging Face, le service charge la révision principale et annonce sa **vraie** révision au backend.

## Prévisions (ARIMA + Holt + naïf) — port 8091, facultatif

Sans service Python sur le port 8091, le backend utilise son **moteur local Java** (`LocalForecastEngine`) :
mêmes modèles (naïf, dérive, Holt, ARIMA(1,1,0)), sélection par walk-forward MAE/RMSE, sur les vraies données
historiques. La version du modèle enregistrée indique le moteur utilisé (`ensemble-v1+java-local` ou `+python`).
