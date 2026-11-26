package com.resalemarket.common.storage;

import org.springframework.core.io.Resource;

/**
 * Where uploaded files live. Business code depends on this interface only: today the implementation is
 * {@link LocalDiskStorageService}; an S3-compatible one can replace it without touching the modules that use it.
 *
 * <p>A <b>key</b> is an opaque, server-generated name such as {@code listing-images/6f1c...e2.jpg} (lower-case letters, digits,
 * {@code . _ -} and {@code /} between segments). Keys are never derived from anything a client sent, and implementations refuse
 * any key that does not have that shape, so a key can never point outside the storage.
 */
public interface StorageService {

    /**
     * Stores the bytes under {@code key}. The file appears all at once: a reader never sees half of it.
     *
     * @throws StorageException if the key is malformed or the bytes could not be written
     */
    void store(String key, byte[] content);

    /**
     * Opens the file stored under {@code key}.
     *
     * @throws StorageObjectNotFoundException if there is nothing under that key
     * @throws StorageException               if the key is malformed
     */
    Resource load(String key);

    /** Removes the file. Removing something that is not there is not an error. */
    void delete(String key);
}
