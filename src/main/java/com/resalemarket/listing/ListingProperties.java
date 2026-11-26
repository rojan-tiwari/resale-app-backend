package com.resalemarket.listing;

import com.resalemarket.common.ratelimit.RateLimit;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Listing settings, bound from {@code app.listing.*}.
 *
 * @param requireReview whether a seller's new listing must be approved by an administrator before it goes live. {@code false}
 *                      (the default) publishes at once and relies on reports for moderation; {@code true} sends it to
 *                      {@code PENDING_REVIEW} first. (Edits to a live listing are not reviewed again.)
 * @param maxImageSize  the largest accepted photo; keep it at or below {@code spring.servlet.multipart.max-file-size}, which is
 *                      the hard stop of the web server
 * @param rateLimits    how often one person may create listings and upload photos
 */
@ConfigurationProperties(prefix = "app.listing")
public record ListingProperties(
        @DefaultValue("false") boolean requireReview,
        @DefaultValue("5MB") DataSize maxImageSize,
        @DefaultValue RateLimits rateLimits) {

    public ListingProperties {
        if (maxImageSize.toBytes() < 1) {
            throw new IllegalArgumentException("app.listing.max-image-size must be positive.");
        }
        rateLimits = rateLimits != null ? rateLimits : new RateLimits(null, null);   // @DefaultValue only applies when binding from configuration
    }

    /**
     * Both are counted per signed-in user. The defaults suit a busy seller and stop a script from filling the disk with drafts
     * and photos.
     *
     * @param create      creating listings
     * @param imageUpload upload requests (one request may carry several photos)
     */
    public record RateLimits(RateLimit create, RateLimit imageUpload) {

        public RateLimits {
            create = create != null ? create : new RateLimit(30, Duration.ofHours(1));
            imageUpload = imageUpload != null ? imageUpload : new RateLimit(120, Duration.ofHours(1));
        }
    }
}
