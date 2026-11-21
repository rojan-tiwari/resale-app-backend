package com.resalemarket.common.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the web app lives, bound from {@code app.frontend.*}. The API needs it to build links that go into e-mails
 * (the password-reset link opens a page of the web app, not of the API).
 *
 * @param url the web app's origin, such as {@code https://resalemarket.example}; a trailing slash is dropped
 */
@ConfigurationProperties(prefix = "app.frontend")
public record FrontendProperties(@DefaultValue("http://localhost:4200") String url) {

    public FrontendProperties {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("app.frontend.url (FRONTEND_URL) is not a valid URL.", ex);
        }
        if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("app.frontend.url (FRONTEND_URL) must be an http(s) URL such as https://example.com.");
        }
        url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** An absolute link to a page of the web app; {@code path} starts with a slash. */
    public String link(String path) {
        return url + path;
    }
}
