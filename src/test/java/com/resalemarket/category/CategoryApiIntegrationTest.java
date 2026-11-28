package com.resalemarket.category;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resalemarket.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The category endpoints end to end against the seeded PostgreSQL. */
@IntegrationTest
class CategoryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theTreeIsPublicCacheableAndOrderedWithSubcategories() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
                .andExpect(jsonPath("$.length()").value(14))
                .andExpect(jsonPath("$[0].name").value("Mobiles & Tablets"))
                .andExpect(jsonPath("$[0].slug").value("mobiles-tablets"))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[0].icon").value("smartphone"))
                .andExpect(jsonPath("$[0].children[0].name").value("Mobile Phones"))
                .andExpect(jsonPath("$[0].children[0].parentId").value(jdbc.queryForObject(
                        "SELECT id FROM categories WHERE slug = 'mobiles-tablets'", Integer.class)))
                .andExpect(jsonPath("$[0].children[0].children", empty()))
                .andExpect(jsonPath("$[13].name").value("Other"));
    }

    @Test
    void everyRootHasItsSubcategoriesAndNoneAppearAtTheTopLevel() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(jsonPath("$[*].name", not(hasItem("Mobile Phones"))))
                .andExpect(jsonPath("$[*].name", hasItem("Vehicles")));
        Integer total = jdbc.queryForObject("SELECT count(*) FROM categories WHERE parent_id IS NOT NULL", Integer.class);
        String body = mockMvc.perform(get("/api/v1/categories")).andReturn().getResponse().getContentAsString();
        int children = com.jayway.jsonpath.JsonPath.<java.util.List<Integer>>read(body, "$[*].children.length()").stream()
                .mapToInt(Integer::intValue).sum();
        org.assertj.core.api.Assertions.assertThat(children).isEqualTo(total);
    }

    @Test
    void oneCategoryComesWithItsChildren() throws Exception {
        Integer id = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'vehicles'", Integer.class);

        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
                .andExpect(jsonPath("$.name").value("Vehicles"))
                .andExpect(jsonPath("$.children[0].parentId").value(id));
    }

    @Test
    void aSubcategoryHasNoChildren() throws Exception {
        Integer id = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'mobile-phones'", Integer.class);

        mockMvc.perform(get("/api/v1/categories/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mobile Phones"))
                .andExpect(jsonPath("$.parentId").isNumber())
                .andExpect(jsonPath("$.children", empty()));
    }

    @Test
    void anUnknownCategoryIsAProblemJson404() throws Exception {
        mockMvc.perform(get("/api/v1/categories/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Category with id '999999' was not found."));
        mockMvc.perform(get("/api/v1/categories/abc")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void anInactiveCategoryDisappearsWithItsChildren() throws Exception {
        Integer vehicles = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'vehicles'", Integer.class);
        Integer aChild = jdbc.queryForObject("SELECT id FROM categories WHERE parent_id = ? LIMIT 1", Integer.class, vehicles);
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", vehicles);
        try {
            mockMvc.perform(get("/api/v1/categories"))
                    .andExpect(jsonPath("$.length()").value(13))
                    .andExpect(jsonPath("$[*].name", not(hasItem("Vehicles"))));
            mockMvc.perform(get("/api/v1/categories/{id}", vehicles)).andExpect(status().isNotFound());
            // The child is active itself, but its parent is hidden, so it is hidden too.
            mockMvc.perform(get("/api/v1/categories/{id}", aChild)).andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE categories SET is_active = TRUE WHERE id = ?", vehicles);
        }
    }

    @Test
    void anInactiveSubcategoryIsLeftOutOfItsParent() throws Exception {
        Integer tablets = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'tablets'", Integer.class);
        jdbc.update("UPDATE categories SET is_active = FALSE WHERE id = ?", tablets);
        try {
            mockMvc.perform(get("/api/v1/categories"))
                    .andExpect(jsonPath("$[0].children[*].name", not(hasItem("Tablets"))))
                    .andExpect(jsonPath("$[0].children[*].name", hasItem("Mobile Phones")));
            mockMvc.perform(get("/api/v1/categories/{id}", tablets)).andExpect(status().isNotFound());
        } finally {
            jdbc.update("UPDATE categories SET is_active = TRUE WHERE id = ?", tablets);
        }
    }

    @Test
    void categoriesCannotBeChangedThroughThePublicApi() throws Exception {
        mockMvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hack\"}"))
                .andExpect(status().isUnauthorized());
    }
}
