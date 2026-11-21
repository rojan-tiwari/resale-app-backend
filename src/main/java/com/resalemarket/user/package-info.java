/**
 * <b>Users and profiles</b> (Phase 2, done).
 *
 * <p>Accounts, roles, the editable profile (name, phone, whether to show it, bio, place), the public member profile (which
 * exposes only intentionally public data; the phone number only when {@code show_phone} consent is set), the database-backed
 * {@code UserDetailsService} and the wrong-password / lock-out bookkeeping the sign-in flow needs.
 * Owns {@code users}, {@code roles}, {@code user_roles} and {@code user_profiles}.
 */
package com.resalemarket.user;
