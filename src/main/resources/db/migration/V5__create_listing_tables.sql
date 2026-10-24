-- =============================================================================
-- V5: Listings and their images
--
-- Lifecycle:  DRAFT -> PENDING_REVIEW -> ACTIVE <-> RESERVED -> SOLD
--             REJECTED / ARCHIVED are reachable from the states the service allows.
-- (The valid transitions are enforced by the service layer; the database guards
--  the invariants that must hold for every state.)
-- =============================================================================

CREATE TABLE listings (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id        UUID          NOT NULL,
    category_id      INTEGER       NOT NULL,
    subcategory_id   INTEGER,
    title            VARCHAR(150)  NOT NULL,
    description      TEXT          NOT NULL,
    price            NUMERIC(12,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'NPR',
    price_type       VARCHAR(15)   NOT NULL DEFAULT 'FIXED',
    item_condition   VARCHAR(20)   NOT NULL,
    province_id      INTEGER       NOT NULL,
    district_id      INTEGER       NOT NULL,
    municipality_id  INTEGER,
    locality         VARCHAR(120),                    -- free-text area / tole, e.g. "Baneshwor"
    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    is_featured      BOOLEAN       NOT NULL DEFAULT FALSE,
    featured_until   TIMESTAMPTZ,
    view_count       BIGINT        NOT NULL DEFAULT 0,
    rejection_reason VARCHAR(500),
    published_at     TIMESTAMPTZ,
    sold_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,

    -- Weighted full-text document: title (A) outranks description (B).
    -- 'simple' config: no stemming, which suits brand/model names and mixed Nepali/English text.
    search_vector    TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(description, '')), 'B')
    ) STORED,

    CONSTRAINT fk_listings_seller FOREIGN KEY (seller_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_listings_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT,
    -- The subcategory must be a child of the chosen category.
    CONSTRAINT fk_listings_subcategory FOREIGN KEY (subcategory_id, category_id)
        REFERENCES categories (id, parent_id) ON DELETE RESTRICT,
    CONSTRAINT fk_listings_province FOREIGN KEY (province_id) REFERENCES provinces (id) ON DELETE RESTRICT,
    -- District must belong to the province; municipality must belong to the district.
    CONSTRAINT fk_listings_district FOREIGN KEY (district_id, province_id)
        REFERENCES districts (id, province_id) ON DELETE RESTRICT,
    CONSTRAINT fk_listings_municipality FOREIGN KEY (municipality_id, district_id)
        REFERENCES municipalities (id, district_id) ON DELETE RESTRICT,
    -- Target of conversations' composite FK (guarantees conversations.seller_id = listing owner).
    CONSTRAINT uq_listings_id_seller UNIQUE (id, seller_id),

    CONSTRAINT chk_listings_title CHECK (char_length(btrim(title)) >= 3),
    CONSTRAINT chk_listings_description CHECK (char_length(description) <= 5000),
    CONSTRAINT chk_listings_price CHECK (price >= 0),
    CONSTRAINT chk_listings_currency CHECK (currency = 'NPR'),     -- relax when more currencies are supported
    CONSTRAINT chk_listings_price_type CHECK (price_type IN ('FIXED', 'NEGOTIABLE')),
    CONSTRAINT chk_listings_condition CHECK (
        item_condition IN ('NEW', 'LIKE_NEW', 'GOOD', 'FAIR', 'FOR_PARTS')),
    CONSTRAINT chk_listings_status CHECK (
        status IN ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'RESERVED', 'SOLD', 'REJECTED', 'ARCHIVED')),
    CONSTRAINT chk_listings_view_count CHECK (view_count >= 0),
    CONSTRAINT chk_listings_sold_at CHECK (status <> 'SOLD' OR sold_at IS NOT NULL),
    CONSTRAINT chk_listings_published_at CHECK (
        status NOT IN ('ACTIVE', 'RESERVED', 'SOLD') OR published_at IS NOT NULL)
);

-- "My listings" dashboard, filtered by status.
CREATE INDEX idx_listings_seller_status ON listings (seller_id, status, created_at DESC);

-- Public discovery paths. They are partial indexes over publicly visible rows only
-- (ACTIVE + RESERVED), so they stay small and every public query must repeat the predicate.
CREATE INDEX idx_listings_public_feed ON listings (published_at DESC)
    WHERE status IN ('ACTIVE', 'RESERVED');
CREATE INDEX idx_listings_public_category ON listings (category_id, published_at DESC)
    WHERE status IN ('ACTIVE', 'RESERVED');
CREATE INDEX idx_listings_public_subcategory ON listings (subcategory_id, published_at DESC)
    WHERE status IN ('ACTIVE', 'RESERVED') AND subcategory_id IS NOT NULL;
CREATE INDEX idx_listings_public_province ON listings (province_id, published_at DESC)
    WHERE status IN ('ACTIVE', 'RESERVED');
CREATE INDEX idx_listings_public_district ON listings (district_id, published_at DESC)
    WHERE status IN ('ACTIVE', 'RESERVED');
CREATE INDEX idx_listings_public_municipality ON listings (municipality_id, published_at DESC)
    WHERE status IN ('ACTIVE', 'RESERVED') AND municipality_id IS NOT NULL;
CREATE INDEX idx_listings_public_price ON listings (price)
    WHERE status IN ('ACTIVE', 'RESERVED');
CREATE INDEX idx_listings_featured ON listings (published_at DESC)
    WHERE is_featured AND status = 'ACTIVE';

-- Admin moderation queue.
CREATE INDEX idx_listings_pending_review ON listings (created_at)
    WHERE status = 'PENDING_REVIEW';

-- Keyword search: full-text (ranked) and trigram (substring / typo tolerant).
CREATE INDEX idx_listings_search_vector ON listings USING GIN (search_vector);
CREATE INDEX idx_listings_title_trgm ON listings USING GIN (title gin_trgm_ops);

-- updated_at is NOT bumped when only view_count changes (atomic view increments).
CREATE TRIGGER trg_listings_updated_at
    BEFORE UPDATE ON listings
    FOR EACH ROW
    WHEN (OLD.view_count IS NOT DISTINCT FROM NEW.view_count)
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE listing_images (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id    UUID         NOT NULL,
    storage_key   VARCHAR(255) NOT NULL,              -- opaque key understood by the ImageStorage implementation
    content_type  VARCHAR(50)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    width         INTEGER,
    height        INTEGER,
    sort_order    INTEGER      NOT NULL,              -- 0-based display order, max 8 images per listing
    is_primary    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_listing_images_listing FOREIGN KEY (listing_id) REFERENCES listings (id) ON DELETE CASCADE,
    CONSTRAINT uq_listing_images_storage_key UNIQUE (storage_key),
    -- Deferrable so that images can be re-ordered (swapped) inside a single transaction.
    CONSTRAINT uq_listing_images_order UNIQUE (listing_id, sort_order) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_listing_images_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT chk_listing_images_size CHECK (size_bytes > 0),
    CONSTRAINT chk_listing_images_dimensions CHECK ((width IS NULL OR width > 0) AND (height IS NULL OR height > 0)),
    -- 8 images maximum, enforced by the database as well as the service.
    CONSTRAINT chk_listing_images_sort_order CHECK (sort_order BETWEEN 0 AND 7)
);

-- At most one primary image per listing.
CREATE UNIQUE INDEX ux_listing_images_primary ON listing_images (listing_id) WHERE is_primary;
