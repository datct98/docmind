-- =============================================================================
-- AGENT BRAIN: PRODUCTION POSTGRESQL + PGVECTOR SCHEMA
-- Implements the 8 core tables from the Memory Layer Architecture Specification
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- 1. Bảng tenants
CREATE TABLE IF NOT EXISTS tenants (
    id VARCHAR(64) PRIMARY KEY,
    shop_name VARCHAR(255) NOT NULL,
    platform_type VARCHAR(50) NOT NULL DEFAULT 'multi_channel',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    persona_config JSONB NOT NULL DEFAULT '{}',
    llm_settings JSONB NOT NULL DEFAULT '{}',
    policy_config JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Bảng customers
CREATE TABLE IF NOT EXISTS customers (
    id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    external_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    honorific VARCHAR(32),
    preferred_language VARCHAR(10) DEFAULT 'vi',
    preferred_contact VARCHAR(50),
    purchase_stage VARCHAR(50) DEFAULT 'UNKNOWN',
    trust_level NUMERIC(4,3) DEFAULT 0.500,
    last_interaction_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, external_id)
);

-- 3. Bảng conversations
CREATE TABLE IF NOT EXISTS conversations (
    id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id),
    customer_id VARCHAR(128) NOT NULL REFERENCES customers(id),
    status VARCHAR(50) NOT NULL DEFAULT 'BOT_ACTIVE',
    human_takeover BOOLEAN NOT NULL DEFAULT FALSE,
    clarification_count INT NOT NULL DEFAULT 0,
    negative_feedback_count INT NOT NULL DEFAULT 0,
    last_intent VARCHAR(100),
    last_message_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 4. Bảng messages
CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL REFERENCES conversations(id),
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    sender VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    normalized_content TEXT,
    intent VARCHAR(100),
    sentiment VARCHAR(50),
    metadata JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 5. Bảng memory_events
CREATE TABLE IF NOT EXISTS memory_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128),
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 6. Bảng customer_memory_facts
CREATE TABLE IF NOT EXISTS customer_memory_facts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    fact_key VARCHAR(100) NOT NULL,
    fact_value TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    confidence NUMERIC(4,3) NOT NULL DEFAULT 0.500,
    evidence_message_ids BIGINT[] DEFAULT '{}',
    effective_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_to TIMESTAMPTZ,
    last_confirmed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    superseded_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memory_facts_tenant_customer_active
    ON customer_memory_facts (tenant_id, customer_id, is_active);

-- 7. Bảng conversation_summaries
CREATE TABLE IF NOT EXISTS conversation_summaries (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    summary_text TEXT NOT NULL,
    sentiment VARCHAR(50),
    intent_summary TEXT,
    objections JSONB NOT NULL DEFAULT '[]',
    purchase_stage VARCHAR(50),
    next_best_action VARCHAR(100),
    memory_embedding VECTOR(768),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 8. Bảng memory_feedback
CREATE TABLE IF NOT EXISTS memory_feedback (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    memory_type VARCHAR(50) NOT NULL,
    memory_id BIGINT NOT NULL,
    feedback_type VARCHAR(50) NOT NULL,
    comment TEXT,
    created_by VARCHAR(128),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
