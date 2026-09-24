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
