@echo off
chcp 65001 >nul
title MarketSim FinBERT - NE PAS FERMER
cd /d "%~dp0"

REM Service FinBERT local du module 5 (analyse de sentiment des actualites), port 8090.
REM Premier lancement : cree un environnement .venv, installe torch (CPU) et transformers, puis telecharge
REM le modele ProsusAI/finbert (~440 Mo, une seule fois). Les lancements suivants sont rapides.

set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY if exist "%USERPROFILE%\anaconda3\python.exe" set "PY="%USERPROFILE%\anaconda3\python.exe""
if not defined PY if exist "%USERPROFILE%\miniconda3\python.exe" set "PY="%USERPROFILE%\miniconda3\python.exe""
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY="%LOCALAPPDATA%\Programs\Python\Python312\python.exe""
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" set "PY="%LOCALAPPDATA%\Programs\Python\Python311\python.exe""
if not defined PY (
    echo [ERREUR] Python 3.10 ou plus introuvable.
    echo Installe Python depuis https://www.python.org/downloads/ en cochant "Add python.exe to PATH", puis relance.
    pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo Creation de l environnement Python .venv ...
    %PY% -m venv .venv
    if errorlevel 1 (
        echo [ERREUR] Impossible de creer l environnement .venv
        pause
        exit /b 1
    )
)

".venv\Scripts\python.exe" -c "import torch, transformers" >nul 2>nul
if errorlevel 1 (
    echo Installation de torch ^(CPU^) et transformers : quelques minutes la premiere fois...
    ".venv\Scripts\python.exe" -m pip install --upgrade pip
    ".venv\Scripts\python.exe" -m pip install torch --index-url https://download.pytorch.org/whl/cpu
    ".venv\Scripts\python.exe" -m pip install -r requirements.txt
    if errorlevel 1 (
        echo [ERREUR] Installation des dependances impossible ^(connexion internet ?^)
        pause
        exit /b 1
    )
)

echo Demarrage de FinBERT sur http://127.0.0.1:8090 ...
".venv\Scripts\python.exe" finbert_service.py
pause
