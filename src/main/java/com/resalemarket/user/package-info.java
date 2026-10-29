/**
 * <b>Users and profiles</b> (Phase 2, in progress: account creation, the database-backed
 * {@code UserDetailsService} and the lookups the sign-in flow needs are built).
 *
 * <p>Accounts, roles, the editable profile, and the public seller profile (which exposes only intentionally
 * public data; the phone number only when {@code show_phone} consent is set).
 * Owns {@code users}, {@code roles}, {@code user_roles} and {@code user_profiles}.
 */
package com.resalemarket.user;
