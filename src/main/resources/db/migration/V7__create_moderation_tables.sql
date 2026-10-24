-- =============================================================================
-- V7: Reporting, moderation and admin audit trail
-- =============================================================================

CREATE TABLE listing_reports (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id      UUID          NOT NULL,
    reporter_id     UUID          NOT NULL,
    reason          VARCHAR(30)   NOT NULL,
    description     VARCHAR(1000),
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    reviewed_by     UUID,
    reviewed_at     TIMESTAMPTZ,
    resolution_note VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_listing_reports_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE,
    CONSTRAINT fk_listing_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_listing_reports_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_listing_reports_reason CHECK (
        reason IN ('SCAM_FRAUD', 'MISLEADING_DESCRIPTION', 'PROHIBITED_ITEM', 'DUPLICATE', 'SPAM', 'OTHER')),
    CONSTRAINT chk_listing_reports_status CHECK (
        status IN ('OPEN', 'UNDER_REVIEW', 'RESOLVED', 'DISMISSED')),
    -- "Other" is meaningless without an explanation.
    CONSTRAINT chk_listing_reports_other_needs_description CHECK (
        reason <> 'OTHER' OR (description IS NOT NULL AND char_length(btrim(description)) > 0)),
    CONSTRAINT chk_listing_reports_reviewed CHECK (
        status NOT IN ('RESOLVED', 'DISMISSED') OR reviewed_at IS NOT NULL)
);

-- A user may not file several unresolved reports against the same listing.
CREATE UNIQUE INDEX ux_listing_reports_open_per_reporter
    ON listing_reports (listing_id, reporter_id)
    WHERE status IN ('OPEN', 'UNDER_REVIEW');

CREATE INDEX idx_listing_reports_status_created ON listing_reports (status, created_at DESC);
CREATE INDEX idx_listing_reports_listing ON listing_reports (listing_id);
CREATE INDEX idx_listing_reports_reporter ON listing_reports (reporter_id);

CREATE TRIGGER trg_listing_reports_updated_at
    BEFORE UPDATE ON listing_reports
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Append-only record of important administrative actions. actor_email is a snapshot
-- so the trail stays readable even if the account is later anonymised.
CREATE TABLE admin_audit_logs (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    UUID,
    actor_email VARCHAR(254),
    action      VARCHAR(60)  NOT NULL,                  -- e.g. USER_SUSPENDED, LISTING_REJECTED
    target_type VARCHAR(40)  NOT NULL,                  -- e.g. USER, LISTING, CATEGORY, REPORT
    target_id   VARCHAR(64),
    details     JSONB,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_admin_audit_logs_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_admin_audit_logs_created ON admin_audit_logs (created_at DESC);
CREATE INDEX idx_admin_audit_logs_actor ON admin_audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_admin_audit_logs_target ON admin_audit_logs (target_type, target_id);
