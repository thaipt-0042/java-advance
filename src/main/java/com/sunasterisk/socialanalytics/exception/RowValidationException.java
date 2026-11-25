package com.sunasterisk.socialanalytics.exception;

import java.util.Comparator;
import java.util.List;

/** Rows of an uploaded file are invalid; nothing was saved. Mapped to 422 with the errors by row. */
public class RowValidationException extends RuntimeException {

    private final List<RowError> errors;

    public RowValidationException(String message, List<RowError> errors) {
        super(message);
        this.errors = errors.stream().sorted(Comparator.comparingInt(RowError::row)).toList();
    }

    /** Errors sorted by row. */
    public List<RowError> getErrors() {
        return errors;
    }
}
