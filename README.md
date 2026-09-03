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
Yahoo Finance API  ·  Gemini AI  ·  News API
```

## Features

- **Real-time Market Data** — Nifty 50, Sensex, Bank Nifty, stock quotes via Yahoo Finance
- **Technical Analysis** — RSI, MACD, SMA/EMA, Bollinger Bands, ATR, Support/Resistance
- **Portfolio Tracking** — P&L, CAGR, allocation, risk metrics with live prices
- **AI Analyst** — Gemini-powered stock analysis, market summaries, portfolio review
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
# Edit .env — set DB credentials, JWT_SECRET, GEMINI_API_KEY
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
| `GEMINI_API_KEY` | Recommended | Google Gemini API key for AI features |
| `NEWS_API_KEY` | Optional | NewsAPI.org key for news feed |
| `REDIS_HOST` | ✓ | Redis host |

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
│       ├── ai/            — Gemini integration
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
