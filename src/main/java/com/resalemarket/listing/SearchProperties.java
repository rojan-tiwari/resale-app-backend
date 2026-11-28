package com.resalemarket.listing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Search and home-page settings, bound from {@code app.search.*}.
 *
 * @param popularCacheTtl how long the "most viewed recently" list is reused. It is the same for every visitor and costs a sort of
 *                        about thirty thousand rows (it cannot use an index: the view counter changes on every page view, and an
 *                        index on it would turn every view into index writes), so it is computed at most once per this long.
 *                        {@code 0s} switches the cache off.
 */
@ConfigurationProperties(prefix = "app.search")
public record SearchProperties(@DefaultValue("5m") Duration popularCacheTtl) {

    public SearchProperties {
        if (popularCacheTtl == null || popularCacheTtl.isNegative()) {
            throw new IllegalArgumentException("app.search.popular-cache-ttl must not be negative.");
        }
    }
}
