package com.resalemarket.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** The steps every account test starts with: create an account, sign in, call something as that person. */
public final class TestAccounts {

    public static final String PASSWORD = "correct horse battery";
    public static final String REFRESH_COOKIE = "refresh_token";

    private TestAccounts() {
    }

    public static void register(MockMvc mockMvc, JsonMapper json, String email, String password, String displayName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("displayName", displayName);
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    public static ResultActions login(MockMvc mockMvc, JsonMapper json, String email, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** Signs in and returns the full result (access token in the body, refresh token in the cookie). */
    public static MvcResult signIn(MockMvc mockMvc, JsonMapper json, String email, String password) throws Exception {
        return login(mockMvc, json, email, password).andExpect(status().isOk()).andReturn();
    }

    public static ResultActions refresh(MockMvc mockMvc, String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new Cookie(REFRESH_COOKIE, refreshToken))
                .header("X-Requested-With", "XMLHttpRequest"));
    }

    public static String accessTokenOf(MvcResult login) throws Exception {
        return JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
    }

    public static String userIdOf(MvcResult login) throws Exception {
        return JsonPath.read(login.getResponse().getContentAsString(), "$.user.id");
    }

    public static String bearer(MvcResult login) throws Exception {
        return "Bearer " + accessTokenOf(login);
    }

    /** The refresh token the server put into the {@code Set-Cookie} header of a login or refresh. */
    public static String refreshTokenOf(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("Set-Cookie header").isNotNull().startsWith(REFRESH_COOKIE + "=");
        return setCookie.substring((REFRESH_COOKIE + "=").length(), setCookie.indexOf(';'));
    }
}
