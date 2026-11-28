package com.resalemarket.common.storage;

/** There is no file under the requested key. */
public class StorageObjectNotFoundException extends StorageException {

    public StorageObjectNotFoundException(String key) {
        super("No stored file under key '" + key + "'.");
    }
}
