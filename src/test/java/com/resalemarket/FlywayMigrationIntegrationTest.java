package com.resalemarket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.resalemarket.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the real Flyway migrations on a real PostgreSQL and proves the schema enforces the rules the
 * application relies on. Each test rolls back, and each constraint test triggers exactly one violation
 * (PostgreSQL aborts the transaction after the first error).
 */
@IntegrationTest
@Transactional
class FlywayMigrationIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "users", "roles", "user_roles", "user_profiles", "refresh_tokens", "password_reset_tokens",
            "provinces", "districts", "municipalities", "categories", "listings", "listing_images",
            "favorites", "conversations", "messages", "notifications", "listing_reports", "admin_audit_logs");

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ migrations & seed data

    @Test
    void everyMigrationAppliedSuccessfully() {
        Integer failed = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class);
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(10);
    }

    @Test
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).containsAll(EXPECTED_TABLES);
    }

    @Test
    void seedDataIsLoaded() {
        assertThat(count("roles")).isEqualTo(2);
        assertThat(count("provinces")).isEqualTo(7);
        assertThat(count("districts")).isEqualTo(77);
        assertThat(count("categories WHERE parent_id IS NULL")).isEqualTo(14);
        assertThat(count("categories WHERE parent_id IS NOT NULL")).isGreaterThan(50);
    }

    @Test
    void requiredNepaliCitiesExistAsMajorCities() {
        List<String> majorCities = jdbc.queryForList("SELECT name FROM municipalities WHERE is_major", String.class);

        assertThat(majorCities).contains(
                "Kathmandu", "Lalitpur", "Bhaktapur", "Pokhara", "Bharatpur",
                "Butwal", "Biratnagar", "Dharan", "Hetauda", "Nepalgunj");
    }

    // ------------------------------------------------------------------ integrity rules

    @Test
    void emailMustBeLowerCase() {
        assertThatThrownBy(() -> insertUser("Mixed@Example.com")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void emailMustBeUnique() {
        insertUser("seller@example.com");

        assertThatThrownBy(() -> insertUser("seller@example.com")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listingSubcategoryMustBelongToItsCategory() {
        UUID seller = insertUser("seller@example.com");

        // "Cars" is a subcategory of Vehicles, not of Mobiles & Tablets.
        assertThatThrownBy(() -> insertListing(seller, "mobiles-tablets", "cars", "Kathmandu"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listingDistrictMustBelongToItsProvince() {
        UUID seller = insertUser("seller@example.com");
        Integer gandaki = id("provinces", "name", "Gandaki");

        // Kathmandu district is in Bagmati, not Gandaki.
        assertThatThrownBy(() -> insertListing(seller, "vehicles", null, "Kathmandu", gandaki))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void validListingIsAccepted() {
        UUID seller = insertUser("seller@example.com");

        UUID listing = insertListing(seller, "mobiles-tablets", "mobile-phones", "Kathmandu");

        assertThat(listing).isNotNull();
    }

    @Test
    void favoriteIsUniquePerUserAndListing() {
        UUID seller = insertUser("seller@example.com");
        UUID buyer = insertUser("buyer@example.com");
        UUID listing = insertListing(seller, "vehicles", "cars", "Kathmandu");
        jdbc.update("INSERT INTO favorites (user_id, listing_id) VALUES (?, ?)", buyer, listing);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO favorites (user_id, listing_id) VALUES (?, ?)", buyer, listing))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listingCannotHaveMoreThanEightImages() {
        UUID seller = insertUser("seller@example.com");
        UUID listing = insertListing(seller, "vehicles", "cars", "Kathmandu");

        // sort_order is 0-based: 8 would be the ninth image.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO listing_images (listing_id, storage_key, content_type, size_bytes, sort_order) "
                                + "VALUES (?, 'k/ninth.jpg', 'image/jpeg', 1000, 8)",
                        listing))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void buyerCannotStartAConversationAboutTheirOwnListing() {
        UUID seller = insertUser("seller@example.com");
        UUID listing = insertListing(seller, "vehicles", "cars", "Kathmandu");

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO conversations (listing_id, buyer_id, seller_id) VALUES (?, ?, ?)",
                        listing, seller, seller))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoriesAreLimitedToTwoLevels() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO categories (parent_id, name, slug) "
                                + "SELECT id, 'Too deep', 'too-deep' FROM categories WHERE slug = 'mobile-phones'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ helpers

    private int count(String tableAndFilter) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + tableAndFilter, Integer.class);
        return count == null ? 0 : count;
    }

    private Integer id(String table, String column, String value) {
        return jdbc.queryForObject("SELECT id FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    private UUID insertUser(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash) VALUES (?, 'not-a-real-hash') RETURNING id",
                UUID.class,
                email);
    }

    private UUID insertListing(UUID seller, String categorySlug, String subcategorySlug, String districtName) {
        return insertListing(seller, categorySlug, subcategorySlug, districtName, null);
    }

    /** Inserts a minimal valid listing; {@code provinceOverride} lets a test supply a deliberately wrong province. */
    private UUID insertListing(
            UUID seller, String categorySlug, String subcategorySlug, String districtName, Integer provinceOverride) {
        Integer category = id("categories", "slug", categorySlug);
        Integer subcategory = subcategorySlug == null ? null : id("categories", "slug", subcategorySlug);
        Integer district = id("districts", "name", districtName);
        Integer province = provinceOverride != null
                ? provinceOverride
                : jdbc.queryForObject("SELECT province_id FROM districts WHERE id = ?", Integer.class, district);

        return jdbc.queryForObject(
                "INSERT INTO listings (seller_id, category_id, subcategory_id, title, description, price, "
                        + "item_condition, province_id, district_id) "
                        + "VALUES (?, ?, ?, 'Test listing', 'A test listing', 1000, 'GOOD', ?, ?) RETURNING id",
                UUID.class,
                seller, category, subcategory, province, district);
    }
}
