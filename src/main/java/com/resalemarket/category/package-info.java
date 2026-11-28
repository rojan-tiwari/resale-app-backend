/**
 * <b>Categories</b> (read API delivered in Phase 3; admin management in Phase 6).
 *
 * <p>Two-level category tree stored in PostgreSQL and a public read API. Owns {@code categories}. Other modules ask
 * {@link com.resalemarket.category.CategoryService} whether a listing may be filed under a category and for category names.
 */
package com.resalemarket.category;
