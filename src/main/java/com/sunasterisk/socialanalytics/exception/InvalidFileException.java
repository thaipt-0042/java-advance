package com.sunasterisk.socialanalytics.exception;

/** An uploaded file that can't be processed as a whole (not a readable .xlsx, wrong columns, no data). Mapped to 400. */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }

    public InvalidFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
