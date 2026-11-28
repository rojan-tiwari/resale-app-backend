/**
 * <b>Messaging</b> (Phase 5).
 *
 * <p>Buyer-to-seller conversations about a listing, REST + polling first (WebSocket-ready design), unread counts, spam throttling.
 * Owns {@code conversations} and {@code messages}. Listings and people are reached only through {@code ListingService} and
 * {@code UserService}; both people of a conversation see it, anybody else gets a 404.
 */
package com.resalemarket.messaging;
