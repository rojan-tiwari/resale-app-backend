/**
 * <b>Listings</b> (delivered in Phases 3 and 4).
 *
 * <p>Phase 3: create, edit, publish, take down and read listings, with a status state machine ({@code ListingStatusPolicy}), ownership
 * checks ({@code ListingAccess}) and photo upload through the {@code StorageService} abstraction. Phase 4 adds the public search
 * (native SQL: PostgreSQL full-text and trigram matching) and the home page lists. Owns {@code listings} and {@code listing_images}.
 */
package com.resalemarket.listing;
