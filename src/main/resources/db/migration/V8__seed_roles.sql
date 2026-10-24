-- =============================================================================
-- V8: Seed roles
--
-- "Guest" is not a stored role: it is simply an unauthenticated request.
-- The first admin account is created by the Phase 2 bootstrap using environment
-- variables, never by a migration (no credentials in source control).
-- =============================================================================

INSERT INTO roles (name, description) VALUES
    ('ROLE_USER',  'Registered marketplace user'),
    ('ROLE_ADMIN', 'Marketplace administrator with moderation permissions');
