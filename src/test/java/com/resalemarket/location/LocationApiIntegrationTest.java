package com.resalemarket.location;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** The location endpoints end to end: HTTP -> security -> controller -> service -> JPA -> seeded PostgreSQL. */
@IntegrationTest
class LocationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listsAllSevenProvincesInOrderWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/locations/provinces"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Koshi"))
                .andExpect(jsonPath("$[2].name").value("Bagmati"));
    }

    @Test
    void listsTheThirteenDistrictsOfBagmati() throws Exception {
        mockMvc.perform(get("/api/v1/locations/provinces/3/districts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13))
                .andExpect(jsonPath("$[*].name", hasItem("Kathmandu")))
                .andExpect(jsonPath("$[0].provinceId").value(3));
    }

    @Test
    void listsMunicipalitiesWithMajorCitiesFirst() throws Exception {
        Integer kathmanduDistrict = jdbc.queryForObject(
                "SELECT id FROM districts WHERE name = 'Kathmandu'", Integer.class);

        mockMvc.perform(get("/api/v1/locations/districts/{id}/municipalities", kathmanduDistrict))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Kathmandu"))
                .andExpect(jsonPath("$[0].type").value("METROPOLITAN"))
                .andExpect(jsonPath("$[0].major").value(true))
                .andExpect(jsonPath("$[0].districtId").value(kathmanduDistrict));
    }

    @Test
    void unknownProvinceIsAProblemJson404() throws Exception {
        mockMvc.perform(get("/api/v1/locations/provinces/999/districts"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Province with id '999' was not found."));
    }

    @Test
    void nonNumericIdIsABadRequestProblem() throws Exception {
        mockMvc.perform(get("/api/v1/locations/provinces/abc/districts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
