package com.resalemarket.common.storage;

/** The storage could not do what was asked (disk full, bad key, permission problem). An unexpected failure, so it ends as a 500. */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
