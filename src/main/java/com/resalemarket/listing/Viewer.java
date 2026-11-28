package com.resalemarket.listing;

import com.resalemarket.listing.entity.Listing;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Who is asking, as far as listings care: nobody (a guest), a signed-in member, or an administrator. Built from the verified access
 * token, never from anything in the request body.
 *
 * @param userId the signed-in person, or {@code null} for a guest
 * @param admin  holds {@code ROLE_ADMIN}
 */
public record Viewer(UUID userId, boolean admin) {

    public static final Viewer ANONYMOUS = new Viewer(null, false);

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /** {@code authentication} is {@code null} or anonymous on a public endpoint without a token. */
    public static Viewer of(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
            return ANONYMOUS;
        }
        boolean admin = authentication.getAuthorities().stream().anyMatch(granted -> ADMIN_AUTHORITY.equals(granted.getAuthority()));
        return new Viewer(UUID.fromString(authentication.getName()), admin);
    }

    public boolean owns(Listing listing) {
        return userId != null && listing.isOwnedBy(userId);
    }
}
