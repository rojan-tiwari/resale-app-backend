/**
 * File storage abstraction (delivered in Phase 3).
 *
 * <p>Business code depends on the {@link com.resalemarket.common.storage.StorageService} interface; a local-disk implementation
 * is used in development and an S3-compatible one can replace it without touching business logic. {@link
 * com.resalemarket.common.storage.ImageInspector} decides from the bytes whether an upload really is a JPEG, PNG or WebP.
 */
package com.resalemarket.common.storage;
