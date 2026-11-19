package com.sunasterisk.socialanalytics.exception;

/** The request clashes with existing data (e.g. a duplicate business key). Mapped to 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
