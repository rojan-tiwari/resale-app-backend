-- =============================================================================
-- V1: Extensions and shared database functions
-- =============================================================================

-- Trigram matching powers fast ILIKE / similarity search on listing titles.
-- pg_trgm is a "trusted" extension (PostgreSQL 13+), so the database owner can create it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Keeps updated_at correct even for writes that bypass JPA (SQL scripts, admin fixes).
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
