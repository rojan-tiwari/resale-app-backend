-- =============================================================================
-- V6: Favorites, messaging and notifications
-- =============================================================================

CREATE TABLE favorites (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    listing_id UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE,
    -- A listing can be saved only once per user.
    CONSTRAINT uq_favorites_user_listing UNIQUE (user_id, listing_id)
);

CREATE INDEX idx_favorites_user_created ON favorites (user_id, created_at DESC);
CREATE INDEX idx_favorites_listing ON favorites (listing_id);

-- One conversation per (listing, buyer). seller_id is denormalised from the listing and
-- kept honest by the composite FK, so participant checks never need a join.
CREATE TABLE conversations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id      UUID        NOT NULL,
    buyer_id        UUID        NOT NULL,
    seller_id       UUID        NOT NULL,
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_conversations_listing FOREIGN KEY (listing_id, seller_id)
        REFERENCES listings (id, seller_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversations_buyer FOREIGN KEY (buyer_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversations_seller FOREIGN KEY (seller_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT uq_conversations_listing_buyer UNIQUE (listing_id, buyer_id),
    -- A buyer cannot open a conversation about their own listing.
    CONSTRAINT chk_conversations_distinct_parties CHECK (buyer_id <> seller_id)
);

CREATE INDEX idx_conversations_buyer ON conversations (buyer_id, last_message_at DESC);
CREATE INDEX idx_conversations_seller ON conversations (seller_id, last_message_at DESC);

CREATE TRIGGER trg_conversations_updated_at
    BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Messages are immutable once sent (only read_at is ever updated), hence no updated_at.
CREATE TABLE messages (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID         NOT NULL,
    sender_id       UUID         NOT NULL,
    body            VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ,
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_sender FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_messages_body CHECK (char_length(btrim(body)) > 0)
);

-- Keyset pagination of a conversation's history: (created_at, id) descending.
CREATE INDEX idx_messages_conversation_created ON messages (conversation_id, created_at DESC, id DESC);
-- Unread badge counts: unread messages in a conversation not sent by the viewer.
CREATE INDEX idx_messages_unread ON messages (conversation_id, sender_id) WHERE read_at IS NULL;
-- Spam throttling: "how many messages did this user send recently".
CREATE INDEX idx_messages_sender_created ON messages (sender_id, created_at DESC);

-- type is validated by the application enum so new notification kinds need no migration.
CREATE TABLE notifications (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL,
    type           VARCHAR(40)  NOT NULL,
    title          VARCHAR(150) NOT NULL,
    body           VARCHAR(500),
    reference_type VARCHAR(30),                         -- e.g. LISTING, CONVERSATION, REPORT
    reference_id   UUID,
    data           JSONB,
    read_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_notifications_reference CHECK (
        (reference_type IS NULL AND reference_id IS NULL)
        OR (reference_type IS NOT NULL AND reference_id IS NOT NULL))
);

CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
