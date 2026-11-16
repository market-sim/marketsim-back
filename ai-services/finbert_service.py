"""
MarketSim AI - module 5 : service FinBERT local (analyse de sentiment des titres d'actualites financieres).

Contrat attendu par le backend Spring (FinBertHttpClient) :
  GET  /health                -> 200 {"status": "UP", "model": ..., "revision": ...} quand le modele est charge
                                 503 tant qu'il se charge (premier lancement : ~440 Mo telecharges une fois)
  POST /internal/v1/analyze   {"text": "..."} ->
       {"status": "ANALYZED", "sentimentLabel": "POSITIVE|NEGATIVE|NEUTRAL",
        "positiveProbability": p, "negativeProbability": n, "neutralProbability": u,   (somme = 1)
        "sentimentScore": p - n, "modelName": "ProsusAI/finbert", "modelVersion": "<revision>",
        "analyzedAt": "2026-...Z", "errorCode": null}
       ou {"status": "UNAVAILABLE" | "INSUFFICIENT_DATA", "errorCode": "...", "analyzedAt": ...}

N'ecoute que sur 127.0.0.1 (jamais expose au reseau). Dependances : torch, transformers (requirements.txt).
"""
import json
import os
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = int(os.environ.get("MARKET_NLP_PORT", "8090"))
MODEL_ID = os.environ.get("FINBERT_MODEL_ID", "ProsusAI/finbert")
# meme revision que app.market.finbert.revision du backend
REVISION = os.environ.get("FINBERT_REVISION", "b3e74f0635049fa34e60ecd12d49d1717d23821c")
MAX_CHARS = 2000

state = {"ready": False, "error": None, "revision": None, "tokenizer": None, "model": None}
lock = threading.Lock()


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def load_model():
    """Charge FinBERT a la revision epinglee ; si elle n'existe pas sur Hugging Face, charge la revision
    principale et annonce sa VRAIE revision (jamais une fausse)."""
    try:
        from transformers import AutoModelForSequenceClassification, AutoTokenizer
        try:
            tok = AutoTokenizer.from_pretrained(MODEL_ID, revision=REVISION)
            mdl = AutoModelForSequenceClassification.from_pretrained(MODEL_ID, revision=REVISION)
            rev = REVISION
        except Exception as e:  # revision introuvable
            print(f"[FinBERT] revision {REVISION} indisponible ({e.__class__.__name__}) : chargement de 'main'", flush=True)
            tok = AutoTokenizer.from_pretrained(MODEL_ID)
            mdl = AutoModelForSequenceClassification.from_pretrained(MODEL_ID)
            rev = getattr(mdl.config, "_commit_hash", None) or "main"
        mdl.eval()
        with lock:
            state.update(tokenizer=tok, model=mdl, revision=rev, ready=True, error=None)
        print(f"[FinBERT] pret : {MODEL_ID} @ {rev} sur http://{HOST}:{PORT}", flush=True)
    except Exception as e:
        with lock:
            state.update(error=f"{e.__class__.__name__}: {e}")
        print(f"[FinBERT] ERREUR de chargement : {e}", file=sys.stderr, flush=True)


def analyze(text):
    text = (text or "").strip()
    if len(text) < 3 or not any(c.isalpha() for c in text):
        return {"status": "INSUFFICIENT_DATA", "errorCode": "TEXT_TOO_SHORT", "analyzedAt": now_iso()}
    with lock:
        ready, tok, mdl, rev = state["ready"], state["tokenizer"], state["model"], state["revision"]
    if not ready:
        return {"status": "UNAVAILABLE", "errorCode": "MODEL_LOADING" if state["error"] is None else "MODEL_LOAD_FAILED",
                "analyzedAt": now_iso()}
    import torch
    inputs = tok(text[:MAX_CHARS], return_tensors="pt", truncation=True, max_length=512)
    with torch.no_grad():
        probs = torch.softmax(mdl(**inputs).logits[0], dim=-1).tolist()
    by_label = {mdl.config.id2label[i].lower(): float(p) for i, p in enumerate(probs)}
    pos, neg, neu = by_label.get("positive", 0.0), by_label.get("negative", 0.0), by_label.get("neutral", 0.0)
    total = pos + neg + neu or 1.0
    pos, neg, neu = pos / total, neg / total, neu / total
    if pos >= neg and pos >= neu:
        label = "POSITIVE"
    elif neg >= pos and neg >= neu:
        label = "NEGATIVE"
    else:
        label = "NEUTRAL"
    return {"status": "ANALYZED", "sentimentLabel": label,
            "positiveProbability": round(pos, 6), "negativeProbability": round(neg, 6),
            "neutralProbability": round(1 - round(pos, 6) - round(neg, 6), 6),
            "sentimentScore": round(pos - neg, 6), "modelName": MODEL_ID, "modelVersion": rev,
            "analyzedAt": now_iso(), "errorCode": None}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            with lock:
                ok = state["ready"]
                body = {"status": "UP" if ok else ("ERROR" if state["error"] else "LOADING"),
                        "model": MODEL_ID, "revision": state["revision"], "error": state["error"]}
            self._send(200 if ok else 503, body)
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/internal/v1/analyze":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(min(length, 100_000)) or b"{}")
            self._send(200, analyze(payload.get("text")))
        except Exception as e:
            self._send(200, {"status": "UNAVAILABLE", "errorCode": "NLP_ERROR", "analyzedAt": now_iso(),
                             "message": str(e)[:200]})

    def log_message(self, fmt, *args):  # journal discret
        pass


if __name__ == "__main__":
    threading.Thread(target=load_model, daemon=True).start()
    print(f"[FinBERT] demarrage sur http://{HOST}:{PORT} (chargement du modele {MODEL_ID}...)", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
