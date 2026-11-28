package com.resalemarket.common.storage;

/** The bytes are not an acceptable image. The message is written for the person who uploaded it and contains no file content. */
public class InvalidImageException extends Exception {

    public InvalidImageException(String message) {
        super(message);
    }
}
