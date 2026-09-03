-- ============================================================
-- Indian Markets AI Platform — PostgreSQL Schema
-- ============================================================

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- for full-text stock search

-- ─── Roles ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS roles (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

INSERT INTO roles (name) VALUES ('ROLE_USER'), ('ROLE_ADMIN'), ('ROLE_PREMIUM')
ON CONFLICT DO NOTHING;

-- ─── Users ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    email          VARCHAR(150) NOT NULL UNIQUE,
    password       VARCHAR(255) NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

-- ─── Refresh Tokens ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    token      VARCHAR(512) NOT NULL UNIQUE,
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    revoked    BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_rt_user ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_rt_token ON refresh_tokens(token);

-- ─── Stocks ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS stocks (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(20)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    exchange        VARCHAR(10)  NOT NULL DEFAULT 'NSE',
    sector          VARCHAR(50),
    industry        VARCHAR(50),
    current_price   NUMERIC(18,2),
    open_price      NUMERIC(18,2),
    high_price      NUMERIC(18,2),
    low_price       NUMERIC(18,2),
    previous_close  NUMERIC(18,2),
    change          NUMERIC(18,2),
    change_percent  NUMERIC(8,4),
    volume          BIGINT,
    market_cap      NUMERIC(20,2),
    pe              NUMERIC(8,2),
    pb              NUMERIC(8,2),
    dividend_yield  NUMERIC(8,4),
    week_high_52    NUMERIC(18,2),
    week_low_52     NUMERIC(18,2),
    last_updated    TIMESTAMP,
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_stocks_symbol ON stocks(symbol);
CREATE INDEX IF NOT EXISTS idx_stocks_sector ON stocks(sector);
CREATE INDEX IF NOT EXISTS idx_stocks_trgm   ON stocks USING gin(name gin_trgm_ops);

-- ─── Market Indices ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS market_indices (
    id             BIGSERIAL PRIMARY KEY,
    symbol         VARCHAR(30)  NOT NULL UNIQUE,
    name           VARCHAR(100) NOT NULL,
    value          NUMERIC(18,2),
    change         NUMERIC(18,2),
    change_percent NUMERIC(8,4),
    open           NUMERIC(18,2),
    high           NUMERIC(18,2),
    low            NUMERIC(18,2),
    previous_close NUMERIC(18,2),
    last_updated   TIMESTAMP
);

INSERT INTO market_indices (symbol, name) VALUES
    ('NIFTY50',   'Nifty 50'),
    ('BANKNIFTY', 'Bank Nifty'),
    ('SENSEX',    'Sensex'),
    ('NIFTYMIDCAP','Nifty Midcap 50')
ON CONFLICT DO NOTHING;

-- ─── Price History ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS price_history (
    id        BIGSERIAL PRIMARY KEY,
    symbol    VARCHAR(20) NOT NULL,
    date      DATE        NOT NULL,
    open      NUMERIC(18,2),
    high      NUMERIC(18,2),
    low       NUMERIC(18,2),
    close     NUMERIC(18,2),
    adj_close NUMERIC(18,2),
    volume    BIGINT,
    UNIQUE (symbol, date)
);

CREATE INDEX IF NOT EXISTS idx_ph_symbol_date ON price_history(symbol, date DESC);

-- ─── Portfolios ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS portfolios (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_portfolios_user ON portfolios(user_id);

-- ─── Holdings ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS holdings (
    id            BIGSERIAL PRIMARY KEY,
    portfolio_id  BIGINT NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    symbol        VARCHAR(20)  NOT NULL,
    name          VARCHAR(200) NOT NULL,
    quantity      NUMERIC(18,4) NOT NULL,
    average_cost  NUMERIC(18,2) NOT NULL,
    current_price NUMERIC(18,2),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_holdings_portfolio ON holdings(portfolio_id);
CREATE INDEX IF NOT EXISTS idx_holdings_symbol    ON holdings(symbol);

-- ─── Transactions ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS transactions (
    id               BIGSERIAL PRIMARY KEY,
    holding_id       BIGINT NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
    type             VARCHAR(10) NOT NULL CHECK (type IN ('BUY','SELL')),
    quantity         NUMERIC(18,4) NOT NULL,
    price            NUMERIC(18,2) NOT NULL,
    charges          NUMERIC(18,2) DEFAULT 0,
    transaction_date DATE NOT NULL,
    notes            VARCHAR(500),
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_txn_holding ON transactions(holding_id);
CREATE INDEX IF NOT EXISTS idx_txn_date    ON transactions(transaction_date);

-- ─── News ────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS news (
    id             BIGSERIAL PRIMARY KEY,
    url            VARCHAR(512) NOT NULL UNIQUE,
    title          VARCHAR(500) NOT NULL,
    description    TEXT,
    source         VARCHAR(200),
    related_symbol VARCHAR(50),
    published_at   TIMESTAMP NOT NULL,
    sentiment      VARCHAR(10) DEFAULT 'NEUTRAL' CHECK (sentiment IN ('POSITIVE','NEGATIVE','NEUTRAL')),
    image_url      VARCHAR(500),
    fetched_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_news_published ON news(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_news_symbol    ON news(related_symbol);
CREATE INDEX IF NOT EXISTS idx_news_sentiment ON news(sentiment);

-- ─── AI History ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ai_history (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    query_type     VARCHAR(30) NOT NULL,
    prompt         TEXT NOT NULL,
    response       TEXT NOT NULL,
    related_symbol VARCHAR(50),
    created_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_user    ON ai_history(user_id);
CREATE INDEX IF NOT EXISTS idx_ai_created ON ai_history(created_at DESC);
