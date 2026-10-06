# Indian Markets AI Platform

AI-powered stock market intelligence and portfolio analytics for Indian investors.

## Architecture

```
Frontend (React 19 + TypeScript + Tailwind)
    ↓  REST API
Backend (Java 21 + Spring Boot 3.3)
    ↓
PostgreSQL + Redis
    ↓
Yahoo Finance API  ·  Local LLM (Ollama) or Gemini  ·  News API
```

## Features

- **Real-time Market Data** — Nifty 50, Sensex, Bank Nifty, stock quotes via Yahoo Finance
- **Technical Analysis** — RSI, MACD, SMA/EMA, Bollinger Bands, ATR, Support/Resistance
- **Portfolio Tracking** — P&L, CAGR, allocation, risk metrics with live prices
- **AI Analyst** — stock analysis, market summaries, portfolio review and a second-opinion rating, on a local Ollama model by default (no API key)
- **Market News** — Sentiment-classified news feed
- **Bloomberg-style dark UI** — Responsive trading dashboard

## Quick Start (Local)

### Prerequisites
- Java 21, Maven 3.9+
- Node 20+, npm
- PostgreSQL 15+
- Redis 7+

### 1. Clone & configure

```bash
git clone <repo>
cd indian-markets-ai-platform
cp .env.example .env
# Edit .env — set DB credentials and JWT_SECRET.
# No AI key needed: the default provider is a local Ollama model (see "AI layer" below).
```

### 2. Database

```bash
psql -U postgres -c "CREATE DATABASE marketai_db;"
psql -U postgres -c "CREATE USER marketai WITH PASSWORD 'marketai_pass';"
psql -U postgres -c "GRANT ALL ON DATABASE marketai_db TO marketai;"
```

### 3. Backend

```bash
cd backend
mvn spring-boot:run
# Starts at http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
```

### 4. Frontend

```bash
cd frontend
npm install
npm run dev
# Starts at http://localhost:5173
```

## Secrets and production deployment

Plaintext secrets (`.env`, `deploy/.env`, `backend/gmail.env`) are git-ignored and never committed.
The only secret file in git is the encrypted bundle `config/secrets.tar.gpg` (AES-256, GnuPG).
At deploy time the bundle is decrypted automatically into `.tmp-secrets/`, handed to Docker, and
deleted again. Nothing is baked into an image layer.

### One-time local setup: encrypt your secrets

```bash
# fill in the three files first (deploy/init-secrets.sh generates most of deploy/.env)
export DEPLOY_SECRET_KEY="$(openssl rand -base64 32)"   # save this in a password manager NOW
./scripts/secrets.sh encrypt                            # writes config/secrets.tar.gpg
git add config/secrets.tar.gpg && git commit -m "Update encrypted secrets" && git push
```

If `DEPLOY_SECRET_KEY` is not set, `encrypt` asks for a passphrase on the terminal (twice, hidden).
Commands: `encrypt`, `decrypt-auto` (needs `DEPLOY_SECRET_KEY`, never prompts), `clean`, and
`run CMD...` (decrypts, runs CMD with the variables exported, cleans up; handy for local dev,
e.g. `./scripts/secrets.sh run sh -c 'cd backend && mvn spring-boot:run'`).

### Set `DEPLOY_SECRET_KEY` where the deploy runs

* **GitHub Actions (recommended):** repo Settings → Secrets and variables → Actions → add
  `DEPLOY_SECRET_KEY`, `SSH_PRIVATE_KEY`, `SSH_KNOWN_HOSTS`, `SSH_HOST`, `SSH_USER` as secrets and
  `DEPLOY_PATH` (the checkout directory on the server) as a variable, ideally in a protected
  `production` environment. `.github/workflows/deploy.yml` sends the key to the server over the SSH
  channel's stdin; it is never echoed, written to disk or put on a command line.
* **Manually on the server:** `export DEPLOY_SECRET_KEY=...` in the session (or load it from your
  process manager's secret store), then run the deploy command. Do not put it in a file in the repo.

### Deploy with one command

```bash
DEPLOY_SECRET_KEY='...' ./deploy.sh
```

`deploy.sh` runs `git pull --ff-only`, `scripts/secrets.sh decrypt-auto`, then
`docker compose -f deploy/docker-compose.prod.yml --env-file .tmp-secrets/deploy.env up -d --build --remove-orphans`,
and removes `.tmp-secrets/` on exit, success or failure (including Ctrl+C).

### Run it locally exactly like production

Local and production use the same compose file, the same encrypted secrets and the same
`deploy.sh`, so there is nothing to keep in sync. The only value that legitimately differs is the
public hostname, which you pass in the environment (a shell value overrides the file):

```bash
DEPLOY_SECRET_KEY='...' DOMAIN=localhost SKIP_PULL=1 ./deploy.sh      # local
DEPLOY_SECRET_KEY='...' ./deploy.sh                                   # production (DOMAIN in deploy/.env)
```

Keep the production `DOMAIN` in `deploy/.env`. Everything else (database, Redis, JWT and encryption
keys, mail, Gemini, Gmail OAuth, sign-up allow-list) comes from the same files in both places.

### Rotate a secret or the key

1. Edit the plaintext file(s) locally (restore them first with `scripts/secrets.sh run`-style
   decrypt if you only have the bundle: `DEPLOY_SECRET_KEY=... ./scripts/secrets.sh decrypt-auto`,
   then copy from `.tmp-secrets/` to `.env`, `deploy/.env`, `backend/gmail.env`, and `clean`).
2. To change the **passphrase**, set a new `DEPLOY_SECRET_KEY` and run `encrypt` again; update the
   GitHub secret. The old passphrase still opens older commits of the bundle, so rotate any secret
   that mattered (database/Redis passwords, JWT secret, API keys) at the source too.
3. Commit the new `config/secrets.tar.gpg` and deploy.

### Recovery and key loss

**There is no recovery for a lost `DEPLOY_SECRET_KEY`.** The bundle cannot be decrypted without
it. Keep it in a password manager plus one offline copy. If it is lost, recreate the secrets from
their sources (new database/Redis passwords, new JWT and encryption keys, new API keys) and encrypt
a new bundle. Note: losing `PDF_PASSWORD_ENC_KEY` (not just the passphrase) makes saved statement
passwords unreadable, and changing `JWT_SECRET` signs everyone out.

### One-time server setup

1. Install Docker (with the compose plugin) and GnuPG (`apt-get install gnupg`), then clone the repo
   to the deploy path and make sure `git pull` works there (read-only deploy key).
2. Create a dedicated SSH key pair for CI, add the public key to the server user's
   `authorized_keys`, and store the private key and `ssh-keyscan <host>` output in GitHub secrets.
3. Add the GitHub secrets and variable listed above.
4. Run `DEPLOY_SECRET_KEY=... ./deploy.sh` once by hand to confirm it works.

## Docker Deployment

```bash
# Copy and fill .env
cp .env.example .env

# Start everything
docker compose up -d

# Frontend: http://localhost:3000
# Backend:  http://localhost:8080
# Swagger:  http://localhost:8080/swagger-ui.html
```

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `DB_HOST` | ✓ | PostgreSQL host |
| `DB_NAME` | ✓ | Database name |
| `DB_USER` | ✓ | Database user |
| `DB_PASSWORD` | ✓ | Database password |
| `JWT_SECRET` | ✓ | JWT signing key (min 64 chars) |
| `LLM_PROVIDER` | Optional | `ollama` (default, local, free) \| `gemini` \| `none` |
| `OLLAMA_MODEL` | Optional | Local model name, default `qwen2.5:7b` — **change this to switch models** |
| `OLLAMA_BASE_URL` | Optional | Default `http://localhost:11434` |
| `GEMINI_API_KEY` | Optional | Only for `LLM_PROVIDER=gemini`. Free key: https://aistudio.google.com/apikey |
| `GEMINI_MODEL` | Optional | Default `gemini-3.6-flash` |
| `NEWS_API_KEY` | Optional | NewsAPI.org key for news feed |
| `REDIS_HOST` | ✓ | Redis host |

## AI layer

AI here is **additive, never load-bearing**: it supplies semantics (classification, narrative,
a second-opinion rating) while every calculation, duplicate check and database write is done in
deterministic Java. Set `LLM_PROVIDER=none` and the platform behaves identically minus the AI
commentary. A model that is missing or unreachable yields an error or an empty result — never a
placeholder that could be mistaken for a financial fact.

**The default is local and free** — no API key:

```bash
brew install ollama        # then, in another shell: ollama serve
ollama pull qwen2.5:7b
```

### Changing the local model

Pull it, point `OLLAMA_MODEL` at it, restart the backend. Nothing else changes.

```bash
ollama pull qwen2.5:14b
OLLAMA_MODEL=qwen2.5:14b ./backend/start.sh
```

| Model | Size | Good for |
|---|---|---|
| `qwen2.5:7b` | ~4.7 GB | Default. Bulk email classification — a full sync is hundreds of calls |
| `qwen2.5:14b` | ~9.0 GB | Better reasoning and prose; slower per call |
| `llama3.1:8b`, `mistral:7b`, `phi4` | varies | Any Ollama chat model that can return strict JSON |

A replacement model must support a system message and be able to emit strict JSON (the
extraction and classification paths request `format: json`). Temperature is pinned to 0, so the
same email always classifies the same way.

Where AI is used: Gmail email classification (`EmailIntelAgent` — results below
`LLM_MIN_CONFIDENCE` go to the review queue instead of importing), PDF-statement transaction
extraction (`AiEmailExtractor`), the AI second opinion on the Analyst View, and the AI Market
Copilot. Every call is recorded in the `ai_audit_trail` table with provider, model and latency.

## API Documentation

Swagger UI available at `/swagger-ui.html` after starting the backend.

### Key endpoints

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Register user |
| POST | `/api/auth/login` | Login, get JWT |
| GET | `/api/market/overview` | Nifty/Sensex/BankNifty data |
| GET | `/api/market/quote/{symbol}` | Real-time stock quote |
| GET | `/api/technical/{symbol}` | Full technical analysis |
| GET | `/api/portfolios/{id}/summary` | Portfolio P&L with live prices |
| POST | `/api/ai/analyse-stock` | AI stock analysis |
| POST | `/api/ai/chat` | Free-form AI chat |

## Running Tests

```bash
cd backend
mvn test
```

## Project Structure

```
indian-markets-ai-platform/
├── backend/
│   └── src/main/java/com/marketai/
│       ├── auth/          — JWT auth, user management
│       ├── market/        — Stock data, Yahoo Finance
│       ├── technical/     — RSI, MACD, MA, BB, ATR
│       ├── portfolio/     — Holdings, P&L, transactions
│       ├── ai/            — LLM layer (Ollama + Gemini providers, audit, review queue)
│       ├── news/          — News feed, sentiment
│       └── common/        — Config, exceptions, security
├── frontend/
│   └── src/
│       ├── pages/         — Dashboard, Stock, Portfolio, Auth
│       ├── components/    — Reusable UI components
│       ├── api/           — Typed API clients
│       ├── store/         — Zustand state
│       └── types/         — TypeScript interfaces
├── database/
│   ├── schema.sql         — PostgreSQL schema + indexes
│   └── seed.sql           — Nifty 50 stock seed data
├── docker-compose.yml
└── .env.example
```

## Roadmap

- [ ] WebSocket real-time price streaming
- [ ] Mutual fund tracking (CAMS/KFintech import)
- [ ] Options chain analysis
- [ ] Backtesting engine
- [ ] Mobile app (React Native)
- [ ] FII/DII flow integration
- [ ] Portfolio XIRR calculation
- [ ] Price alerts (email/push)
