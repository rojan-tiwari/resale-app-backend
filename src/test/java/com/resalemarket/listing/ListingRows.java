package com.resalemarket.listing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts listings straight into the table, so a search test can choose every value (price, date, condition, views, status)
 * without going through the API and uploading a photo for each one. Only for tests of what is <em>read</em>.
 */
public final class ListingRows {

    private final JdbcTemplate jdbc;
    private final UUID sellerId;

    public ListingRows(JdbcTemplate jdbc, UUID sellerId) {
        this.jdbc = jdbc;
        this.sellerId = sellerId;
    }

    /** The values of one listing, with sensible defaults: a live, fixed-price phone in Kathmandu, posted a day ago. */
    public static final class Row {
        String title = "Plain phone";
        String description = "A phone in working condition, nothing special about it.";
        BigDecimal price = new BigDecimal("1000");
        String priceType = "FIXED";
        String condition = "GOOD";
        String category = "mobiles-tablets";
        String subcategory = "mobile-phones";
        int provinceId = 3;
        String district = "Kathmandu";
        boolean municipality = false;
        String status = "ACTIVE";
        int postedDaysAgo = 1;
        long views = 0;
        boolean featured = false;
        Integer featuredDays = null;        // null with featured = no end date

        public Row title(String value) { title = value; return this; }
        public Row description(String value) { description = value; return this; }
        public Row price(String value) { price = new BigDecimal(value); return this; }
        public Row negotiable() { priceType = "NEGOTIABLE"; return this; }
        public Row condition(String value) { condition = value; return this; }
        public Row category(String category, String subcategory) { this.category = category; this.subcategory = subcategory; return this; }
        public Row place(int provinceId, String district) { this.provinceId = provinceId; this.district = district; return this; }
        public Row withMunicipality() { municipality = true; return this; }
        public Row status(String value) { status = value; return this; }
        public Row postedDaysAgo(int days) { postedDaysAgo = days; return this; }
        public Row views(long value) { views = value; return this; }
        public Row featured() { featured = true; return this; }
        public Row featuredFor(int days) { featured = true; featuredDays = days; return this; }
    }

    public static Row row() {
        return new Row();
    }

    public UUID insert(Row row) {
        Integer category = jdbc.queryForObject("SELECT id FROM categories WHERE slug = ?", Integer.class, row.category);
        Integer subcategory = jdbc.queryForObject("SELECT id FROM categories WHERE slug = ?", Integer.class, row.subcategory);
        Integer district = jdbc.queryForObject("SELECT id FROM districts WHERE name = ? AND province_id = ?", Integer.class, row.district, row.provinceId);
        Integer municipality = row.municipality
                ? jdbc.queryForObject("SELECT id FROM municipalities WHERE district_id = ? ORDER BY is_major DESC, id LIMIT 1", Integer.class, district)
                : null;
        OffsetDateTime published = OffsetDateTime.now(ZoneOffset.UTC).minusDays(row.postedDaysAgo);
        OffsetDateTime featuredUntil = row.featuredDays == null ? null : OffsetDateTime.now(ZoneOffset.UTC).plusDays(row.featuredDays);
        boolean live = row.status.equals("ACTIVE") || row.status.equals("RESERVED") || row.status.equals("SOLD");
        return jdbc.queryForObject(
                "INSERT INTO listings (seller_id, category_id, subcategory_id, title, description, price, price_type, item_condition, "
                        + "province_id, district_id, municipality_id, status, published_at, sold_at, view_count, is_featured, featured_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                UUID.class,
                sellerId, category, subcategory, row.title, row.description, row.price, row.priceType, row.condition,
                row.provinceId, district, municipality, row.status, live ? published : null,
                row.status.equals("SOLD") ? published : null, row.views, row.featured, featuredUntil);
    }

    public UUID insert() {
        return insert(row());
    }
}
