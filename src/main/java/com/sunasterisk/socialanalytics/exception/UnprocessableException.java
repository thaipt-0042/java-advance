package com.sunasterisk.socialanalytics.exception;

/**
 * A valid request that the application refuses to carry out (e.g. an export too large). Mapped to 422
 * {@code VALIDATION} with an empty {@code errors}, like other 422 errors not tied to one field.
 */
public class UnprocessableException extends RuntimeException {

    public UnprocessableException(String message) {
        super(message);
    }
}
