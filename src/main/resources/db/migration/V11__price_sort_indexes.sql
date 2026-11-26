-- =============================================================================
-- V11: indexes that serve "sort by price" (Phase 4)
--
-- V5 had one partial index on (price). Sorting by price needs a tie-break, because many listings share a price (round prices such as
-- Rs. 1000 are normal), and the sort order is `price, published_at DESC, id`. With an index on price alone PostgreSQL has to read the
-- whole group of listings with the same price and sort them (measured on 500 000 listings: 33 ms for the cheapest price, and
-- ~150 ms with a cold cache, because 15 000 listings share the lowest price). With the tie-break inside the index it stops after the
-- page it needs (0.05 ms). One index per direction, because a backward scan would reverse the date order as well.
--
-- Both stay PARTIAL over the publicly visible statuses, like the other discovery indexes: every public query repeats the predicate.
-- Price range filters (`price >= :min`) use them too, since price is the leading column.
-- =============================================================================

DROP INDEX idx_listings_public_price;

CREATE INDEX idx_listings_public_price_asc ON listings (price ASC, published_at DESC, id)
    WHERE status IN ('ACTIVE', 'RESERVED');
CREATE INDEX idx_listings_public_price_desc ON listings (price DESC, published_at DESC, id)
    WHERE status IN ('ACTIVE', 'RESERVED');
